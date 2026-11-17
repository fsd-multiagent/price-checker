package ro.pricechecker.extract;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import ro.pricechecker.extract.ExtractedFacts.Availability;

@Tag("AC-2.1.1")
class StructuredDataTest {

  static final ExtractedFacts EXPECTED =
      new ExtractedFacts(129999L, "RON", Availability.IN_STOCK, "5901234123457");

  static final String PRODUCT_PAGE =
      page(
          """
          {"@context": "https://schema.org", "@type": "Product",
           "name": "Test kettle", "description": "A long description", "image": "k.jpg",
           "gtin13": "5901234123457",
           "offers": {"@type": "Offer", "price": "1299.99", "priceCurrency": "RON",
                      "availability": "https://schema.org/InStock"}}
          """);

  private static String page(String... jsonLdBlocks) {
    StringBuilder html = new StringBuilder("<html><head><title>t</title>");
    for (String block : jsonLdBlocks) {
      html.append("<script type=\"application/ld+json\">").append(block).append("</script>");
    }
    return html.append("</head><body><p>hello</p></body></html>").toString();
  }

  @Test
  void ac_2_1_1_readsProductWithOffer() {
    assertThat(StructuredData.fromJsonLd(PRODUCT_PAGE)).contains(EXPECTED);
  }

  @Test
  void ac_2_1_1_resultHoldsOnlyFacts() {
    assertThat(
            Arrays.stream(ExtractedFacts.class.getRecordComponents()).map(RecordComponent::getName))
        .containsExactly("priceMinor", "currency", "availability", "gtin");
  }

  @Test
  void ac_2_1_1_readsArrayRootAndTypeArray() {
    String html =
        page(
            """
            [{"@type": "BreadcrumbList"},
             {"@type": ["Thing", "schema:Product"], "gtin13": "5901234123457",
              "offers": [{"price": 1299.99, "priceCurrency": "RON",
                          "availability": "InStock"},
                         {"price": 5, "priceCurrency": "EUR"}]}]
            """);
    assertThat(StructuredData.fromJsonLd(html)).contains(EXPECTED);
  }

  @Test
  void ac_2_1_1_readsGraphAndAggregateOffer() {
    String html =
        page(
            """
            {"@context": "https://schema.org", "@graph": [
              {"@type": "WebPage"},
              {"@type": "Product", "gtin": 5901234123457,
               "offers": {"@type": "AggregateOffer", "lowPrice": "1299.99",
                          "highPrice": "1500", "priceCurrency": "RON",
                          "availability": "http://schema.org/InStock"}}]}
            """);
    assertThat(StructuredData.fromJsonLd(html)).contains(EXPECTED);
  }

  @Test
  void ac_2_1_1_readsOutOfStockAndUnknownAvailability() {
    String out =
        page(
            """
            {"@type": "Product", "offers": {"price": 10, "priceCurrency": "RON",
             "availability": "https://schema.org/OutOfStock"}}
            """);
    String unknown = page("{\"@type\": \"Product\", \"offers\": {\"price\": \"10\"}}");
    assertThat(StructuredData.fromJsonLd(out))
        .contains(new ExtractedFacts(1000L, "RON", Availability.OUT_OF_STOCK, null));
    assertThat(StructuredData.fromJsonLd(unknown))
        .contains(new ExtractedFacts(1000L, null, Availability.UNKNOWN, null));
  }

  @Test
  void ac_2_1_1_badPriceIsNullNeverZero() {
    String html =
        page(
            "{\"@type\": \"Product\", \"offers\": {\"price\": \"0\", \"priceCurrency\": \"RON\"}}");
    assertThat(StructuredData.fromJsonLd(html))
        .contains(new ExtractedFacts(null, "RON", Availability.UNKNOWN, null));
  }

  @Test
  void ac_2_1_1_noJsonLdIsEmpty() {
    assertThat(StructuredData.fromJsonLd("<html><body>nothing</body></html>")).isEmpty();
    assertThat(StructuredData.fromJsonLd("")).isEmpty();
    assertThat(StructuredData.fromJsonLd(null)).isEmpty();
  }

  @Test
  void ac_2_1_1_nonProductTypesAreEmpty() {
    String html = page("{\"@type\": \"Organization\", \"name\": \"x\"}", "[1, \"two\", null]");
    assertThat(StructuredData.fromJsonLd(html)).isEmpty();
  }

  @Test
  void ac_2_1_1_malformedBlockIsSkippedAndNextIsTried() {
    String html =
        page(
            "{\"@type\": \"Product\", \"offers\": ",
            """
            {"@type": "Product", "gtin13": "5901234123457",
             "offers": {"price": "1299.99", "priceCurrency": "RON",
                        "availability": "https://schema.org/InStock"}}
            """);
    assertThat(StructuredData.fromJsonLd(html)).contains(EXPECTED);
  }

  @Test
  void ac_2_1_1_ignoresOtherScriptTypes() {
    String html =
        "<script type=\"application/json\">{\"@type\": \"Product\"}</script>"
            + "<script>var x = {\"@type\": \"Product\"};</script>";
    assertThat(StructuredData.fromJsonLd(html)).isEmpty();
  }

  @ParameterizedTest
  @ValueSource(strings = {"gtin", "gtin14", "gtin12", "gtin8"})
  void ac_2_1_1_readsOtherGtinKeys(String key) {
    String gtin = key.equals("gtin8") ? "96385074" : "05901234123457";
    String html = page("{\"@type\": \"Product\", \"" + key + "\": \"" + gtin + "\"}");
    assertThat(StructuredData.fromJsonLd(html))
        .contains(new ExtractedFacts(null, null, Availability.UNKNOWN, gtin));
  }

  @Test
  void ac_2_1_1_prefersGtin13OverOtherKeys() {
    String html =
        page(
            """
            {"@type": "Product", "gtin8": "96385074", "gtin13": "5901234123457"}
            """);
    assertThat(StructuredData.fromJsonLd(html).map(ExtractedFacts::gtin)).contains("5901234123457");
  }

  @Test
  void ac_2_1_1_acceptsJsonLdTypeWithCharsetParameter() {
    String html =
        "<script type=\"Application/LD+JSON; charset=utf-8\">"
            + "{\"@type\": \"Product\", \"offers\": {\"price\": \"10\"}}</script>";
    assertThat(StructuredData.fromJsonLd(html))
        .contains(new ExtractedFacts(1000L, null, Availability.UNKNOWN, null));
  }

  @Test
  void ac_2_1_1_nullPriceFallsBackToLowPrice() {
    String html =
        page("{\"@type\": \"Product\", \"offers\": {\"price\": null, \"lowPrice\": \"7,50\"}}");
    assertThat(StructuredData.fromJsonLd(html).map(ExtractedFacts::priceMinor)).contains(750L);
  }

  @ParameterizedTest
  @CsvSource({
    "https://schema.org/LimitedAvailability, IN_STOCK",
    "https://schema.org/OnlineOnly, IN_STOCK",
    "https://schema.org/SoldOut, OUT_OF_STOCK",
    "https://schema.org/Discontinued, OUT_OF_STOCK",
    "https://schema.org/PreOrder, UNKNOWN",
    "maybe, UNKNOWN"
  })
  void ac_2_1_1_mapsSchemaAvailabilityTerms(String term, Availability expected) {
    String html =
        page("{\"@type\": \"Product\", \"offers\": {\"availability\": \"" + term + "\"}}");
    assertThat(StructuredData.fromJsonLd(html).map(ExtractedFacts::availability))
        .contains(expected);
  }

  @Test
  void ac_2_1_1_productWithoutOffersHasNoPriceFacts() {
    String html = page("{\"@type\": \"Product\", \"gtin13\": \"5901234123457\", \"offers\": []}");
    assertThat(StructuredData.fromJsonLd(html))
        .contains(new ExtractedFacts(null, null, Availability.UNKNOWN, "5901234123457"));
  }

  @Test
  void ac_2_1_1_nonPriceOfferValuesAreNull() {
    String html =
        page(
            """
            {"@type": "Product", "offers": {"price": {"value": 5}, "priceCurrency": 978,
             "availability": true}}
            """);
    assertThat(StructuredData.fromJsonLd(html))
        .contains(new ExtractedFacts(null, null, Availability.UNKNOWN, null));
  }

  @Test
  void ac_2_1_1_invalidCurrencyAndGtinBecomeNull() {
    String html =
        page(
            """
            {"@type": "Product", "gtin13": "12AB", "offers": {"price": "5",
             "priceCurrency": "lei"}}
            """);
    assertThat(StructuredData.fromJsonLd(html))
        .contains(new ExtractedFacts(500L, null, Availability.UNKNOWN, null));
  }
}
