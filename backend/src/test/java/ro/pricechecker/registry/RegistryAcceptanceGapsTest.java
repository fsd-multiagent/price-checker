package ro.pricechecker.registry;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import java.lang.reflect.Constructor;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Eufrosina (QA), story 1-5 round 1: checks the existing suite did not cover. Each name carries its
 * acceptance criterion ID (T-1). No retailer is contacted (T-3).
 */
@Tag("AC-1.5.1")
@Tag("AC-1.5.2")
@Tag("AC-1.5.4")
class RegistryAcceptanceGapsTest {

  private static final JavaClasses MAIN =
      new ClassFileImporter()
          .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
          .importPackages("ro.pricechecker");

  /** AC 1.5.1: one parser. Only the registry package may parse the register. */
  @Test
  void ac_1_5_1_onlyRegistryPackageParsesTheRegister() {
    noClasses()
        .that()
        .resideOutsideOfPackage("ro.pricechecker.registry..")
        .should()
        .callMethod(SourceRegister.class, "parse", String.class)
        .because("AD-8: one registry parser reads Domain and Status")
        .allowEmptyShould(true)
        .check(MAIN);
  }

  /** AC 1.5.1: a parse error in the bundled register leaves no domain approved. */
  @Test
  void ac_1_5_1_brokenRegisterApprovesNothingEvenWithApprovedRow() {
    String broken =
        """
        ## Retailers

        | Retailer | Domain | Status | Limits |
        |---|---|---|---|
        | A | `a.example` | Approved 2026-11-20 | 1 request / 2 s, concurrency 1 |
        | B | `b.example` | Approved 2026-11-20 |
        """;
    SourceRegister register = SourceRegister.parse(broken);
    assertThat(register.errors()).isNotEmpty();
    assertThat(register.approvedDomains()).isEmpty();
    assertThat(register.isApproved("a.example")).isFalse();
  }

  /**
   * AC 1.5.2: the shipped config holds the two Proposed retailers, each disabled, curated, with
   * deadlineMs 4000 and limits no looser than the register's Limits column.
   */
  @Test
  void ac_1_5_2_shippedEntriesAreDisabledCuratedWithRegisterLimits() {
    SourceRegister register =
        SourceRegister.parse(Registry.readResource(Registry.REGISTER_RESOURCE));
    List<RetailerConfig> retailers =
        RegistryYaml.parseRetailers(Registry.readResource(Registry.RETAILERS_RESOURCE));

    assertThat(retailers).hasSize(2);
    assertThat(retailers)
        .allSatisfy(r -> {
          assertThat(register.rows()).as(r.domain()).containsKey(r.domain());
          assertThat(r.adapter()).as(r.domain()).isEqualTo("disabled");
          assertThat(r.enabled()).as(r.domain()).isFalse();
          assertThat(r.resolution()).as(r.domain()).isEqualTo("curated");
          assertThat(r.deadlineMs()).as(r.domain()).isEqualTo(4000);
          RegisterLimits limits = register.limits(r.domain()).orElseThrow();
          assertThat(r.minIntervalMs().longValue())
              .as(r.domain())
              .isGreaterThanOrEqualTo(limits.intervalMs());
          assertThat(r.concurrency()).as(r.domain()).isLessThanOrEqualTo(limits.concurrency());
        });
  }

  /** AC 1.5.4: the registry has no input other than the bundled resources. */
  @Test
  void ac_1_5_4_registryReadsNoEnvironmentPropertyOrInjectedValue() {
    noClasses()
        .that()
        .resideInAPackage("ro.pricechecker.registry..")
        .should()
        .callMethod(System.class, "getenv", String.class)
        .orShould()
        .callMethod(System.class, "getenv")
        .orShould()
        .callMethod(System.class, "getProperty", String.class)
        .orShould()
        .callMethod(System.class, "getProperty", String.class, String.class)
        .orShould()
        .callMethod(System.class, "getProperties")
        .orShould()
        .dependOnClassesThat()
        .haveFullyQualifiedName("org.springframework.core.env.Environment")
        .orShould()
        .dependOnClassesThat()
        .haveFullyQualifiedName("org.springframework.beans.factory.annotation.Value")
        .orShould()
        .dependOnClassesThat()
        .haveFullyQualifiedName(
            "org.springframework.boot.context.properties.ConfigurationProperties")
        .because("AC-9.3: no flag or variable may mark a retailer Approved")
        .check(MAIN);
  }

  /** AC 1.5.4: Spring can only build the registry through its no-argument constructor. */
  @Test
  void ac_1_5_4_onlyPublicConstructorTakesNoArguments() {
    List<Constructor<?>> publicCtors =
        Arrays.stream(Registry.class.getDeclaredConstructors())
            .filter(c -> Modifier.isPublic(c.getModifiers()))
            .toList();
    assertThat(publicCtors)
        .singleElement()
        .satisfies(c -> assertThat(c.getParameterCount()).isZero());
  }

  /** AC 1.5.4: JVM system properties (-D flags) set before start do not change approval. */
  @Test
  void ac_1_5_4_systemPropertiesDoNotChangeApproval() {
    List<String> keys =
        List.of(
            "registry.approved",
            "pricechecker.registry.approved",
            "REGISTRY_APPROVED",
            "registry.register");
    try {
      keys.forEach(k -> System.setProperty(k, "a.example"));
      Registry registry = new Registry();
      SourceRegister bundled =
          SourceRegister.parse(Registry.readResource(Registry.REGISTER_RESOURCE));
      assertThat(registry.isApproved("a.example")).isFalse();
      assertThat(registry.approvedDomains()).isEqualTo(bundled.approvedDomains());
    } finally {
      keys.forEach(System::clearProperty);
    }
  }
}
