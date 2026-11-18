package ro.pricechecker.retailer.evomagro;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import ro.pricechecker.adapter.AdapterOutcome;
import ro.pricechecker.adapter.Availability;
import ro.pricechecker.adapter.PageFacts;
import ro.pricechecker.adapter.UnavailableReason;
import ro.pricechecker.extract.ExtractedFacts;

class EvomagPageTest {

  private static final URI URL = URI.create("https://example.test/p/synthetic");
  private static final String GTIN = "5901234123457";

  private static AdapterOutcome extract(String fixture) {
    return EvomagPage.extract(Fixtures.read(fixture), URL);
  }

  // --- each step on its own (C-6) ---

  @Test
  @Tag("AC-2.11.1")
  void ac_2_11_1_structuredDataStepReadsJsonLd() {
    assertThat(EvomagPage.structuredData(Fixtures.read("product-jsonld.html")))
        .contains(new ExtractedFacts(129999L, "RON", ExtractedFacts.Availability.IN_STOCK, GTIN));
  }

  @Test
  @Tag("AC-2.11.1")
  void ac_2_11_1_structuredDataStepIgnoresPagesWithoutJsonLd() {
    assertThat(EvomagPage.structuredData(Fixtures.read("product-microdata.html"))).isEmpty();
  }

  @Test
  @Tag("AC-2.11.1")
  void ac_2_11_1_metaTagsStepReadsProductMetaTags() {
    assertThat(EvomagPage.metaTags(Fixtures.read("product-meta.html")))
        .contains(new ExtractedFacts(84900L, "RON", ExtractedFacts.Availability.IN_STOCK, null));
  }

  @Test
  @Tag("AC-2.11.1")
  void ac_2_11_1_metaTagsStepIgnoresPagesWithoutProductMetaTags() {
    assertThat(EvomagPage.metaTags(Fixtures.read("product-microdata.html"))).isEmpty();
  }

  @Test
  @Tag("AC-2.11.1")
  void ac_2_11_1_microdataStepReadsMicrodata() {
    assertThat(EvomagPage.microdata(Fixtures.read("product-microdata.html")))
        .contains(
            new ExtractedFacts(249990L, "RON", ExtractedFacts.Availability.OUT_OF_STOCK, GTIN));
  }

  @Test
  @Tag("AC-2.11.1")
  void ac_2_11_1_microdataStepFallsBackToElementText() {
    String html =
        """
        <div itemscope itemtype="http://schema.org/Product">
          <span itemprop="price">1.299,99 lei</span>
          <span itemprop="priceCurrency">RON</span>
          <meta itemprop="availability" content="InStock">
          <span itemprop="gtin13">5901234123457</span>
        </div>
        """;
    assertThat(EvomagPage.microdata(html))
        .contains(new ExtractedFacts(129999L, "RON", ExtractedFacts.Availability.IN_STOCK, GTIN));
  }

  @Test
  @Tag("AC-2.11.1")
  void ac_2_11_1_microdataStepPrefersContentOverText() {
    String html =
        """
        <div itemscope itemtype="https://schema.org/Product">
          <span itemprop="price" content="2499.90">2.599,90 lei</span>
          <meta itemprop="availability" content="schema:InStock">
        </div>
        """;
    assertThat(EvomagPage.microdata(html))
        .contains(new ExtractedFacts(249990L, null, ExtractedFacts.Availability.IN_STOCK, null));
  }

  @Test
  @Tag("AC-2.11.2")
  void ac_2_11_2_withoutPriceFirstRecognizedStepWins() {
    String html =
        """
        <script type="application/ld+json">
        {"@type":"Product","offers":{"@type":"Offer",
         "availability":"https://schema.org/InStock"}}
        </script>
        <div itemscope itemtype="https://schema.org/Product">
          <link itemprop="availability" href="https://schema.org/OutOfStock">
        </div>
        """;
    URI url = URI.create("https://example.test/p/synthetic");
    assertThat(EvomagPage.extract(html, url))
        .isEqualTo(
            new AdapterOutcome.Found(new PageFacts(null, null, Availability.IN_STOCK, url, null)));
  }

  @Test
  @Tag("AC-2.11.1")
  void ac_2_11_1_microdataStepNeedsAProductScope() {
    assertThat(EvomagPage.microdata(Fixtures.read("no-product.html"))).isEmpty();
    assertThat(EvomagPage.microdata(Fixtures.read("product-meta.html"))).isEmpty();
    assertThat(EvomagPage.microdata(null)).isEmpty();
  }

  @Test
  @Tag("AC-2.11.1")
  void ac_2_11_1_availabilityMapsBySuffixAfterLastSlash() {
    assertThat(EvomagPage.availabilityOf("https://schema.org/InStock"))
        .isEqualTo(ExtractedFacts.Availability.IN_STOCK);
    assertThat(EvomagPage.availabilityOf("http://schema.org/OutOfStock"))
        .isEqualTo(ExtractedFacts.Availability.OUT_OF_STOCK);
    assertThat(EvomagPage.availabilityOf("https://schema.org/PreOrder"))
        .isEqualTo(ExtractedFacts.Availability.UNKNOWN);
    assertThat(EvomagPage.availabilityOf(null)).isEqualTo(ExtractedFacts.Availability.UNKNOWN);
  }

  @Test
  @Tag("AC-2.11.1")
  void ac_2_11_1_availabilityMapsEveryKnownTerm() {
    for (String term :
        new String[] {
          "https://schema.org/LimitedAvailability", "https://schema.org/OnlineOnly", "in stock"
        }) {
      assertThat(EvomagPage.availabilityOf(term))
          .as(term)
          .isEqualTo(ExtractedFacts.Availability.IN_STOCK);
    }
    for (String term :
        new String[] {
          "https://schema.org/SoldOut", "https://schema.org/Discontinued", "oos", "out of stock"
        }) {
      assertThat(EvomagPage.availabilityOf(term))
          .as(term)
          .isEqualTo(ExtractedFacts.Availability.OUT_OF_STOCK);
    }
  }

  // --- I/O matrix ---

  @Test
  @Tag("AC-2.11.1")
  void ac_2_11_1_jsonLdPageIsFound() {
    assertThat(extract("product-jsonld.html"))
        .isEqualTo(
            new AdapterOutcome.Found(
                new PageFacts(129999L, "RON", Availability.IN_STOCK, URL, GTIN)));
  }

  @Test
  @Tag("AC-2.11.1")
  void ac_2_11_1_metaOnlyPageIsFoundFromMetaStep() {
    assertThat(extract("product-meta.html"))
        .isEqualTo(
            new AdapterOutcome.Found(
                new PageFacts(84900L, "RON", Availability.IN_STOCK, URL, null)));
  }

  @Test
  @Tag("AC-2.11.1")
  void ac_2_11_1_microdataPageIsFoundFromMicrodataStep() {
    assertThat(extract("product-microdata.html"))
        .isEqualTo(
            new AdapterOutcome.Found(
                new PageFacts(249990L, "RON", Availability.OUT_OF_STOCK, URL, GTIN)));
  }

  @Test
  @Tag("AC-2.11.1")
  void ac_2_11_1_jsonLdWinsOverMicrodata() {
    assertThat(extract("product-mixed.html"))
        .isEqualTo(
            new AdapterOutcome.Found(
                new PageFacts(10000L, "RON", Availability.IN_STOCK, URL, null)));
  }

  @Test
  @Tag("AC-2.11.1")
  void ac_2_11_1_microdataWinsOverMetaTags() {
    String html = Fixtures.read("product-meta-microdata.html");
    assertThat(EvomagPage.metaTags(html))
        .get()
        .extracting(ExtractedFacts::priceMinor)
        .isEqualTo(30000L);
    assertThat(extract("product-meta-microdata.html"))
        .isEqualTo(
            new AdapterOutcome.Found(
                new PageFacts(40000L, "RON", Availability.OUT_OF_STOCK, URL, GTIN)));
  }

  @Test
  @Tag("AC-2.11.1")
  void ac_2_11_1_laterStepWithPriceWinsOverEarlierStepWithout() {
    String html =
        """
        <html><head>
        <script type="application/ld+json">
        {"@type": "Product", "offers": {"price": "la cerere", "priceCurrency": "RON"}}
        </script>
        <meta property="product:price:amount" content="10.50">
        </head><body></body></html>
        """;
    assertThat(EvomagPage.extract(html, URL))
        .isEqualTo(
            new AdapterOutcome.Found(new PageFacts(1050L, null, Availability.UNKNOWN, URL, null)));
  }

  @Test
  @Tag("AC-2.11.2")
  void ac_2_11_2_pageWithoutFactsIsUnparseable() {
    assertThat(extract("no-product.html"))
        .isEqualTo(new AdapterOutcome.Unavailable(UnavailableReason.UNPARSEABLE));
  }

  @Test
  @Tag("AC-2.11.2")
  void ac_2_11_2_emptyProductScopeIsUnparseable() {
    String html = "<div itemscope itemtype=\"https://schema.org/Product\"></div>";
    assertThat(EvomagPage.extract(html, URL))
        .isEqualTo(new AdapterOutcome.Unavailable(UnavailableReason.UNPARSEABLE));
  }

  @Test
  @Tag("AC-2.11.2")
  void ac_2_11_2_unparseablePriceGivesNullPriceNeverZero() {
    assertThat(extract("unparseable-price.html"))
        .isEqualTo(
            new AdapterOutcome.Found(new PageFacts(null, "RON", Availability.IN_STOCK, URL, null)));
  }
}
