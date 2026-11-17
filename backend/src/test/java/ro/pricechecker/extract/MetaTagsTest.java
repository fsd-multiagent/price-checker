package ro.pricechecker.extract;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import ro.pricechecker.extract.ExtractedFacts.Availability;

@Tag("AC-2.1.2")
class MetaTagsTest {

  private static String page(String metas) {
    return "<html><head><title>t</title>" + metas + "</head><body>hello</body></html>";
  }

  @Test
  void ac_2_1_2_readsProductMetaTags() {
    String html =
        page(
            """
            <meta property="og:title" content="Test kettle">
            <meta property="product:price:amount" content="1299.99">
            <meta property="product:price:currency" content="RON">
            <meta property="product:availability" content="in stock">
            <meta property="product:ean" content="5901234123457">
            """);
    assertThat(MetaTags.fromMetaTags(html)).contains(StructuredDataTest.EXPECTED);
  }

  @Test
  void ac_2_1_2_sameProductGivesSameFactsAsJsonLd() {
    String html =
        page(
            """
            <meta name="og:price:amount" content="1.299,99">
            <meta name="og:price:currency" content="RON">
            <meta name="og:availability" content="instock">
            <meta name="product:ean" content="5901234123457">
            """);
    assertThat(MetaTags.fromMetaTags(html))
        .isEqualTo(StructuredData.fromJsonLd(StructuredDataTest.PRODUCT_PAGE))
        .isPresent();
  }

  @Test
  void ac_2_1_2_readsOutOfStock() {
    String html =
        page(
            """
            <meta property="product:price:amount" content="10">
            <meta property="product:availability" content="out of stock">
            """);
    assertThat(MetaTags.fromMetaTags(html))
        .contains(new ExtractedFacts(1000L, null, Availability.OUT_OF_STOCK, null));
  }

  @Test
  void ac_2_1_2_badPriceIsNullNeverZero() {
    String html = page("<meta property=\"product:price:amount\" content=\"0,00\">");
    assertThat(MetaTags.fromMetaTags(html))
        .contains(new ExtractedFacts(null, null, Availability.UNKNOWN, null));
  }

  @Test
  void ac_2_1_2_helpersAreSeparateFunctions() {
    String metaOnly =
        page(
            """
            <meta property="product:price:amount" content="1299.99">
            <meta property="product:price:currency" content="RON">
            """);
    assertThat(StructuredData.fromJsonLd(metaOnly)).isEmpty();
    assertThat(MetaTags.fromMetaTags(metaOnly)).isPresent();
    assertThat(MetaTags.fromMetaTags(StructuredDataTest.PRODUCT_PAGE)).isEmpty();
  }

  @Test
  void ac_2_1_2_productTagsWinOverOgTags() {
    String html =
        page(
            """
            <meta property="og:price:amount" content="5">
            <meta property="og:price:currency" content="EUR">
            <meta property="product:price:amount" content="1299.99">
            <meta property="product:price:currency" content="RON">
            """);
    assertThat(MetaTags.fromMetaTags(html))
        .contains(new ExtractedFacts(129999L, "RON", Availability.UNKNOWN, null));
  }

  @Test
  void ac_2_1_2_keysAreCaseInsensitiveAndEmptyContentIsSkipped() {
    String html =
        page(
            """
            <meta property="Product:Price:Amount" content="  ">
            <meta property="PRODUCT:PRICE:AMOUNT" content="12,50">
            <meta property="product:availability" content="">
            """);
    assertThat(MetaTags.fromMetaTags(html))
        .contains(new ExtractedFacts(1250L, null, Availability.UNKNOWN, null));
  }

  @Test
  void ac_2_1_2_invalidCurrencyAndGtinBecomeNull() {
    String html =
        page(
            """
            <meta property="product:price:amount" content="5">
            <meta property="product:price:currency" content="lei">
            <meta property="product:ean" content="12AB">
            """);
    assertThat(MetaTags.fromMetaTags(html))
        .contains(new ExtractedFacts(500L, null, Availability.UNKNOWN, null));
  }

  @Test
  void ac_2_1_2_noProductMetaTagsIsEmpty() {
    assertThat(MetaTags.fromMetaTags(page("<meta name=\"description\" content=\"x\">"))).isEmpty();
    assertThat(MetaTags.fromMetaTags("")).isEmpty();
    assertThat(MetaTags.fromMetaTags(null)).isEmpty();
  }
}
