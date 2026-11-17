package ro.pricechecker.arch;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("AC-1.1.3")
class RetailerTextScanTest {

  private static String literal(String text) {
    return "class X { String s = \"" + text + "\"; }";
  }

  @Test
  void ac_1_1_3_realSourcesHaveNoDomainsOrSelectorsOutsideRetailer() {
    assertThat(RetailerTextScan.scanDirectory(Path.of("src/main/java"))).isEmpty();
  }

  @Test
  void ac_1_1_3_domainOutsideRetailerIsReported() {
    Map<String, String> sources =
        Map.of("ro/pricechecker/lookup/Bad.java", literal("www.example-shop.ro"));
    assertThat(RetailerTextScan.offendingFiles(sources))
        .containsExactly("ro/pricechecker/lookup/Bad.java");
  }

  @Test
  void ac_1_1_3_compoundSelectorOutsideRetailerIsReported() {
    Map<String, String> sources =
        Map.of("ro/pricechecker/lookup/Bad.java", literal("div.price > span"));
    assertThat(RetailerTextScan.offendingFiles(sources))
        .containsExactly("ro/pricechecker/lookup/Bad.java");
  }

  @Test
  void ac_1_1_3_attributeSelectorOutsideRetailerIsReported() {
    Map<String, String> sources =
        Map.of("ro/pricechecker/extract/Bad.java", literal("[itemprop=price]"));
    assertThat(RetailerTextScan.offendingFiles(sources))
        .containsExactly("ro/pricechecker/extract/Bad.java");
  }

  @Test
  void ac_1_1_3_tagAndBareSelectorsOutsideRetailerAreReported() {
    Map<String, String> sources =
        Map.of(
            "ro/pricechecker/lookup/Tag.java", literal("span.price"),
            "ro/pricechecker/lookup/Bare.java", literal(".product-price"));
    assertThat(RetailerTextScan.offendingFiles(sources))
        .containsExactly("ro/pricechecker/lookup/Bare.java", "ro/pricechecker/lookup/Tag.java");
  }

  @Test
  void ac_1_1_3_retailerRootOrNestedRetailerFolderIsNotExempt() {
    Map<String, String> sources =
        Map.of(
            "ro/pricechecker/retailer/Shared.java", literal("www.example-shop.ro"),
            "ro/pricechecker/api/retailer/Dto.java", literal("www.example-shop.ro"));
    assertThat(RetailerTextScan.offendingFiles(sources))
        .containsExactly(
            "ro/pricechecker/api/retailer/Dto.java", "ro/pricechecker/retailer/Shared.java");
  }

  @Test
  void ac_1_1_3_sameLiteralsInsideRetailerAreNotReported() {
    Map<String, String> sources =
        Map.of(
            "ro/pricechecker/retailer/shopa/Ok.java",
            literal("www.example-shop.ro")
                + literal("div.price > span")
                + literal("[itemprop=price]"));
    assertThat(RetailerTextScan.offendingFiles(sources)).isEmpty();
  }

  @Test
  void ac_1_1_3_ordinaryLiteralsAreNotReported() {
    Map<String, String> sources =
        Map.of(
            "ro/pricechecker/lookup/Ok.java",
            literal("ro.pricechecker.lookup") + literal("java.time.Clock"));
    assertThat(RetailerTextScan.offendingFiles(sources)).isEmpty();
  }
}
