package ro.pricechecker.retailer.altexro;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.Socket;
import java.net.URI;
import java.net.URL;
import java.net.URLConnection;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;
import org.jsoup.Jsoup;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import ro.pricechecker.adapter.AdapterOutcome;
import ro.pricechecker.adapter.Availability;
import ro.pricechecker.adapter.PageFacts;
import ro.pricechecker.adapter.RetailerHttp;
import ro.pricechecker.ean.Ean;
import ro.pricechecker.registry.SourceRegister;

/**
 * Story 4.1 gaps found by Eufrosina (QA): fixture provenance and hygiene, the adapter's lack of its
 * own network access, the AD-7 fall-through when an earlier step has facts but no price, and the
 * register Status that keeps Altex out of a Lookup.
 */
class AltexroAcceptanceGapsTest {

  private static final Path REPO_ROOT = Path.of("..");
  private static final Path FIXTURES = REPO_ROOT.resolve("fixtures").resolve("altex.ro");
  private static final Ean EAN = Ean.parse("4006381333931");
  private static final URI PRODUCT_URL = URI.create("https://altex.ro/p/synthetic-1");

  private final List<URI> requested = new ArrayList<>();

  private RetailerHttp serving(String html) {
    return url -> {
      requested.add(url);
      return html;
    };
  }

  private static List<Path> htmlFixtures() throws IOException {
    try (Stream<Path> files = Files.list(FIXTURES)) {
      return files.filter(p -> p.getFileName().toString().endsWith(".html")).sorted().toList();
    }
  }

  @Test
  @Tag("AC-4.1.1")
  void ac_4_1_1_everyFixtureIsDocumentedAsSynthetic() throws IOException {
    String readme = Files.readString(FIXTURES.resolve("README.md"));
    assertThat(readme).contains("None was fetched from altex.ro");
    List<Path> fixtures = htmlFixtures();
    assertThat(fixtures).isNotEmpty();
    assertThat(fixtures).allSatisfy(p -> assertThat(readme).contains("`" + p.getFileName() + "`"));
  }

  @Test
  @Tag("AC-4.1.1")
  void ac_4_1_1_fixturesHoldFactsOnlyAndNoAltexAddresses() throws IOException {
    for (Path p : htmlFixtures()) {
      String html = Files.readString(p);
      String lower = html.toLowerCase(Locale.ROOT);
      assertThat(lower)
          .as("%s holds no page address from the retailer (T-2, T-3)", p.getFileName())
          .doesNotContain("altex.ro");
      assertThat(lower)
          .as("%s holds facts only (T-2, S-7)", p.getFileName())
          .doesNotContain("<img", "og:image", "og:title", "og:description", "\"description\"")
          .doesNotContain("\"name\"", "\"image\"");
      assertThat(Jsoup.parse(html).select("script[src], link[href], iframe"))
          .as("%s loads nothing", p.getFileName())
          .isEmpty();
    }
  }

  @Test
  @Tag("AC-4.1.1")
  void ac_4_1_1_adapterHasNoNetworkAccessOfItsOwn() {
    JavaClasses classes =
        new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("ro.pricechecker.retailer.altexro");
    assertThat(classes).isNotEmpty();
    noClasses()
        .should()
        .dependOnClassesThat()
        .resideInAnyPackage("java.net.http..", "okhttp3..", "org.apache.hc..")
        .orShould()
        .dependOnClassesThat()
        .belongToAnyOf(URL.class, URLConnection.class, HttpURLConnection.class, Socket.class)
        .orShould()
        .callMethod(Jsoup.class, "connect", String.class)
        .as("the Altex Adapter fetches only through the injected RetailerHttp (T-3)")
        .check(classes);
  }

  @Test
  @Tag("AC-4.1.1")
  void ac_4_1_1_laterStepWithPriceWinsOverEarlierStepWithFactsOnly() {
    String html =
        """
        <html><head>
        <script type="application/ld+json">
        {"@context":"https://schema.org","@type":"Product","gtin13":"4006381333931",
         "offers":{"@type":"Offer","availability":"https://schema.org/InStock"}}
        </script>
        <meta property="product:price:amount" content="1299.99">
        <meta property="product:price:currency" content="RON">
        </head><body><div class="Price-current">9 lei</div></body></html>
        """;

    AdapterOutcome outcome = new AltexroAdapter().fetchAndExtract(EAN, PRODUCT_URL, serving(html));

    assertThat(outcome)
        .isInstanceOfSatisfying(
            AdapterOutcome.Found.class,
            f -> {
              assertThat(f.facts().priceMinor()).isEqualTo(129999L);
              assertThat(f.facts().currency()).isEqualTo("RON");
            });
    assertThat(requested).containsExactly(PRODUCT_URL);
  }

  @Test
  @Tag("AC-4.1.1")
  void ac_4_1_1_noPriceInAnyStepKeepsTheFirstStepWithFacts() {
    String html =
        """
        <html><head>
        <script type="application/ld+json">
        {"@context":"https://schema.org","@type":"Product","gtin13":"4006381333931",
         "offers":{"@type":"Offer","availability":"https://schema.org/InStock"}}
        </script>
        </head><body><div class="Status-stock">Stoc epuizat</div></body></html>
        """;

    AdapterOutcome outcome = new AltexroAdapter().fetchAndExtract(EAN, PRODUCT_URL, serving(html));

    assertThat(outcome)
        .isEqualTo(
            new AdapterOutcome.Found(
                new PageFacts(null, null, Availability.IN_STOCK, PRODUCT_URL, "4006381333931")));
  }

  @Test
  @Tag("AC-4.1.2")
  void ac_4_1_2_registerKeepsAltexNotChecked() throws IOException {
    SourceRegister register =
        SourceRegister.parse(Files.readString(REPO_ROOT.resolve("docs/source-register.md")));

    assertThat(register.rows()).containsKey("altex.ro");
    assertThat(register.rows().get("altex.ro").status()).isEqualTo("Not checked");
    assertThat(register.isApproved("altex.ro")).isFalse();
    assertThat(register.approvedDomains()).doesNotContain("altex.ro");
  }
}
