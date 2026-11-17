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
  void ac_2_1_2_noProductMetaTagsIsEmpty() {
    assertThat(MetaTags.fromMetaTags(page("<meta name=\"description\" content=\"x\">"))).isEmpty();
    assertThat(MetaTags.fromMetaTags("")).isEmpty();
    assertThat(MetaTags.fromMetaTags(null)).isEmpty();
  }
}
