package ro.pricechecker.arch;

import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAPackage;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAnyPackage;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.type;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaMethodCall;
import com.tngtech.archunit.lang.ArchRule;
import java.net.URI;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** The AD-1 rules, parameterized by root package so fixtures can prove each one fails. */
final class ArchitectureRules {

  /** Allowed outgoing arrows from the spine paradigm diagram; a package may always use itself. */
  static final Map<String, Set<String>> ALLOWED =
      Map.of(
          "api", Set.of("lookup", "ean", "registry"),
          "ean", Set.of(),
          "adapter", Set.of("ean"),
          "lookup", Set.of("ean", "cache", "registry", "adapter"),
          "cache", Set.of(),
          "registry", Set.of(),
          "fetch", Set.of("adapter", "registry"),
          "extract", Set.of(),
          "health", Set.of("lookup", "registry", "fetch"));

  /** Feature packages (relative to the root) other than {@code retailer.<id>}. */
  static final List<String> FEATURES = List.copyOf(ALLOWED.keySet());

  /** What a {@code retailer.<id>} package may use (never another retailer). */
  static final Set<String> RETAILER_ALLOWED = Set.of("adapter", "ean", "extract", "fetch");

  /** The only package allowed to use an HTTP client. */
  private static final String HTTP_OWNER = "fetch";

  private static final String[] HTTP_CLIENT_PACKAGES = {
    "java.net.http..",
    "org.apache.hc..",
    "org.apache.http..",
    "okhttp3..",
    "org.springframework.web.client..",
    "org.springframework.http.client..",
    "javax.net..",
    "org.springframework.web.reactive.function.client.."
  };

  private static final Set<String> HTTP_CLIENT_CLASSES =
      Set.of(
          "java.net.HttpURLConnection",
          "java.net.URLConnection",
          "java.net.Socket",
          "org.jsoup.Connection",
          "org.jsoup.helper.HttpConnection");

  private ArchitectureRules() {}

  static List<ArchRule> all(String root) {
    List<ArchRule> rules = new ArrayList<>();
    for (String feature : FEATURES) {
      rules.add(featureMayOnlyUse(root, feature, ALLOWED.get(feature)));
    }
    rules.add(onlyKnownPackages(root));
    rules.add(retailerMayOnlyUse(root));
    rules.add(retailersAreIndependent(root));
    rules.add(httpClientOnlyInFetch(root));
    rules.add(extractHasNoNetworkAccess(root));
    return rules;
  }

  /**
   * Any app class (under {@code root}) outside the given packages. Root-level wiring classes and
   * unknown packages count as forbidden, so only the diagram's arrows are allowed.
   */
  private static DescribedPredicate<JavaClass> appClassOutside(String root, List<String> packages) {
    String[] allowed = packages.stream().map(p -> root + "." + p + "..").toArray(String[]::new);
    return resideInAPackage(root + "..")
        .and(DescribedPredicate.not(resideInAnyPackage(allowed)))
        .as("app classes outside " + packages);
  }

  static ArchRule featureMayOnlyUse(String root, String feature, Set<String> allowed) {
    List<String> permitted = new ArrayList<>(allowed);
    permitted.add(feature);
    return noClasses()
        .that()
        .resideInAPackage(root + "." + feature + "..")
        .should()
        .dependOnClassesThat(appClassOutside(root, permitted))
        .as("package " + feature + " may only use itself and " + allowed)
        .allowEmptyShould(true);
  }

  /** Every app class sits in the root package, a feature package or {@code retailer.<id>}. */
  static ArchRule onlyKnownPackages(String root) {
    List<String> known = new ArrayList<>();
    known.add(root);
    known.add(root + ".retailer..");
    FEATURES.forEach(f -> known.add(root + "." + f + ".."));
    return classes()
        .that()
        .resideInAPackage(root + "..")
        .should()
        .resideInAnyPackage(known.toArray(String[]::new))
        .as("app classes live only in the root, feature or retailer packages")
        .allowEmptyShould(true);
  }

  static ArchRule retailerMayOnlyUse(String root) {
    List<String> permitted = new ArrayList<>(RETAILER_ALLOWED);
    permitted.add("retailer");
    return noClasses()
        .that()
        .resideInAPackage(root + ".retailer..")
        .should()
        .dependOnClassesThat(appClassOutside(root, permitted))
        .as("retailer packages may only use " + RETAILER_ALLOWED)
        .allowEmptyShould(true);
  }

  static ArchRule retailersAreIndependent(String root) {
    return slices()
        .matching(root + ".retailer.(*)..")
        .should()
        .notDependOnEachOther()
        .as("retailer.<id> packages never depend on each other")
        .allowEmptyShould(true);
  }

  private static DescribedPredicate<JavaMethodCall> jsoupConnect() {
    return DescribedPredicate.describe(
        "Jsoup.connect",
        call ->
            call.getTargetOwner().getName().equals("org.jsoup.Jsoup")
                && call.getName().equals("connect"));
  }

  /** {@code URL#openConnection} (any overload), {@code openStream} and {@code getContent}. */
  private static DescribedPredicate<JavaMethodCall> urlFetch() {
    return DescribedPredicate.describe(
        "URL network access",
        call ->
            call.getTargetOwner().isEquivalentTo(URL.class)
                && Set.of("openConnection", "openStream", "getContent").contains(call.getName()));
  }

  static ArchRule httpClientOnlyInFetch(String root) {
    return noClasses()
        .that()
        .resideOutsideOfPackage(root + "." + HTTP_OWNER + "..")
        .should()
        .dependOnClassesThat(
            resideInAnyPackage(HTTP_CLIENT_PACKAGES)
                .or(
                    DescribedPredicate.describe(
                        "an HTTP client class", c -> HTTP_CLIENT_CLASSES.contains(c.getName()))))
        .orShould()
        .callMethodWhere(jsoupConnect())
        .orShould()
        .callMethodWhere(urlFetch())
        .as("no HTTP client outside fetch")
        .allowEmptyShould(true);
  }

  /** {@code extract} parses strings only: no network, no files, no jsoup connection (AD-7). */
  static ArchRule extractHasNoNetworkAccess(String root) {
    return noClasses()
        .that()
        .resideInAPackage(root + ".extract..")
        .should()
        .dependOnClassesThat(
            resideInAnyPackage(
                    "java.net..", "java.nio.file..", "java.nio.channels..", "org.jsoup.helper..")
                .and(DescribedPredicate.not(type(URI.class)))
                .or(
                    DescribedPredicate.describe(
                        "a file or jsoup connection class",
                        c ->
                            c.getName().startsWith("java.io.File")
                                || c.getName().equals("org.jsoup.Connection"))))
        .orShould()
        .callMethodWhere(jsoupConnect())
        .as("extract has no network or file access")
        .allowEmptyShould(true);
  }
}
