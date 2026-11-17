package ro.pricechecker.arch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("AC-1.1.2")
class ArchitectureTest {

  private static final String ROOT = "ro.pricechecker";
  private static final String FIXTURES = ROOT + ".archfixtures";

  private static void check(String root) {
    JavaClasses classes = new ClassFileImporter().importPackages(root);
    for (ArchRule rule : ArchitectureRules.all(root)) {
      rule.check(classes);
    }
  }

  private static void assertViolation(String fixture) {
    assertThatThrownBy(() -> check(FIXTURES + "." + fixture)).isInstanceOf(AssertionError.class);
  }

  @Test
  void ac_1_1_2_realCodePassesAllRules() {
    JavaClasses main =
        new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages(ROOT);
    assertThat(main.contain(ro.pricechecker.PriceCheckerApplication.class)).isTrue();
    List<ArchRule> rules = ArchitectureRules.all(ROOT);
    for (ArchRule rule : rules) {
      rule.check(main);
    }
  }

  @Test
  void ac_1_1_2_allowedArrowPasses() {
    assertThatCode(() -> check(FIXTURES + ".allowed")).doesNotThrowAnyException();
  }

  @Test
  void ac_1_1_2_cacheUsingLookupFails() {
    assertViolation("cacheuseslookup");
  }

  @Test
  void ac_1_1_2_featureUsingRootLevelClassFails() {
    assertViolation("featuretoroot");
  }

  @Test
  void ac_1_1_2_retailerUsingRetailerFails() {
    assertViolation("retailertoretailer");
  }

  @Test
  void ac_1_1_2_retailerUsingRegistryFails() {
    assertViolation("retailertoregistry");
  }

  @Test
  void ac_1_1_2_lookupImportingRetailerClassFails() {
    assertViolation("lookuptoretailer");
  }

  @Test
  void ac_1_1_2_extractUsingFetchFails() {
    assertViolation("extracttofetch");
  }

  @Test
  void ac_1_1_2_apiBypassingLookupToFetchFails() {
    assertViolation("apitofetch");
  }

  @Test
  void ac_1_1_2_httpClientOutsideFetchFails() {
    assertViolation("httpoutside");
  }

  @Test
  void ac_1_1_2_urlOpenConnectionOutsideFetchFails() {
    assertViolation("urloutside");
  }

  @Test
  void ac_1_1_2_unknownAppPackageFails() {
    assertViolation("unknownpackage");
  }

  @Test
  void ac_1_1_2_httpClientInsideFetchPasses() {
    assertThatCode(() -> check(FIXTURES + ".httpinside")).doesNotThrowAnyException();
  }

  @Test
  @Tag("AC-2.1.4")
  void ac_2_1_4_extractReadingAFileFails() {
    assertViolation("extractnetwork");
  }

  @Test
  @Tag("AC-2.1.4")
  void ac_2_1_4_extractUsingNioOrNetFails() {
    assertViolation("extractnio");
  }

  @Test
  @Tag("AC-2.1.4")
  void ac_2_1_4_extractUsingJsoupConnectFails() {
    assertViolation("extractjsoup");
  }

  @Test
  @Tag("AC-2.1.4")
  void ac_2_1_4_extractResolvingUriWithoutOpeningItPasses() {
    JavaClasses fixture = new ClassFileImporter().importPackages(FIXTURES + ".extracturi");
    assertThatCode(() ->
            ArchitectureRules.extractHasNoNetworkAccess(FIXTURES + ".extracturi").check(fixture))
        .doesNotThrowAnyException();
  }

  @Test
  @Tag("AC-2.1.4")
  void ac_2_1_4_realExtractHasNoNetworkAccess() {
    JavaClasses main =
        new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages(ROOT + ".extract");
    assertThat(main.contain(ro.pricechecker.extract.StructuredData.class)).isTrue();
    ArchitectureRules.extractHasNoNetworkAccess(ROOT).check(main);
  }
}
