package ro.pricechecker.retailer.altexro;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import ro.pricechecker.adapter.AdapterOutcome;
import ro.pricechecker.adapter.Availability;
import ro.pricechecker.adapter.PageFacts;
import ro.pricechecker.adapter.RetailerHttp;
import ro.pricechecker.adapter.UnavailableReason;
import ro.pricechecker.ean.Ean;
import ro.pricechecker.extract.ExtractedFacts;

/** AC 4.1.1: the Altex Adapter against synthetic fixtures and a fake {@link RetailerHttp} (T-3). */
@Tag("AC-4.1.1")
class AltexroAdapterTest {

  private static final Path FIXTURES = Path.of("..", "fixtures", "altex.ro");
  private static final Ean EAN = Ean.parse("4006381333931");
  private static final URI PRODUCT_URL = URI.create("https://altex.ro/p/synthetic-1");
  private static final ExtractedFacts EXPECTED =
      new ExtractedFacts(129999L, "RON", ExtractedFacts.Availability.IN_STOCK, "4006381333931");

  private final AltexroAdapter adapter = new AltexroAdapter();
  private final List<URI> requested = new ArrayList<>();

  private static String fixture(String name) {
    try {
      return Files.readString(FIXTURES.resolve(name));
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
  }

  private RetailerHttp serving(String name) {
    String body = fixture(name);
    return url -> {
      requested.add(url);
      return body;
    };
  }

  private AdapterOutcome run(String fixtureName) {
    return adapter.fetchAndExtract(EAN, PRODUCT_URL, serving(fixtureName));
  }

  private static PageFacts expectedFound() {
    return new PageFacts(129999L, "RON", Availability.IN_STOCK, PRODUCT_URL, "4006381333931");
  }

  @Test
  void ac_4_1_1_idIsTheRegisterDomain() {
    assertThat(adapter.retailerId()).isEqualTo("altex.ro");
  }

  @Test
  void ac_4_1_1_jsonLdPageWins() {
    assertThat(run("product-jsonld.html")).isEqualTo(new AdapterOutcome.Found(expectedFound()));
    assertThat(requested).containsExactly(PRODUCT_URL);
  }

  @Test
  void ac_4_1_1_metaOnlyPage() {
    assertThat(run("product-meta.html")).isEqualTo(new AdapterOutcome.Found(expectedFound()));
    assertThat(requested).containsExactly(PRODUCT_URL);
  }

  @Test
  void ac_4_1_1_selectorOnlyPage() {
    assertThat(run("product-selectors.html")).isEqualTo(new AdapterOutcome.Found(expectedFound()));
    assertThat(requested).containsExactly(PRODUCT_URL);
  }

  @Test
  void ac_4_1_1_noFactsIsUnparseable() {
    assertThat(run("no-product.html"))
        .isEqualTo(new AdapterOutcome.Unavailable(UnavailableReason.UNPARSEABLE));
    assertThat(requested).containsExactly(PRODUCT_URL);
  }

  @Test
  void ac_4_1_1_unparseablePriceIsNullNeverZero() {
    assertThat(run("unparseable-price.html"))
        .isEqualTo(
            new AdapterOutcome.Found(
                new PageFacts(null, "RON", Availability.IN_STOCK, PRODUCT_URL, "4006381333931")));
  }

  @Test
  void ac_4_1_1_httpFailureIsHttpError() {
    RetailerHttp failing =
        url -> {
          requested.add(url);
          throw new IOException("synthetic failure");
        };
    assertThat(adapter.fetchAndExtract(EAN, PRODUCT_URL, failing))
        .isEqualTo(new AdapterOutcome.Unavailable(UnavailableReason.HTTP_ERROR));
    assertThat(requested).containsExactly(PRODUCT_URL);
  }

  @Test
  void ac_4_1_1_structuredDataStepReadsOnlyJsonLd() {
    assertThat(AltexroAdapter.fromStructuredData(fixture("product-jsonld.html")))
        .contains(EXPECTED);
    assertThat(AltexroAdapter.fromStructuredData(fixture("product-meta.html"))).isEmpty();
    assertThat(AltexroAdapter.fromStructuredData(fixture("product-selectors.html"))).isEmpty();
    assertThat(AltexroAdapter.fromStructuredData(fixture("no-product.html"))).isEmpty();
  }

  @Test
  void ac_4_1_1_metaTagsStepReadsOnlyMetaTags() {
    assertThat(AltexroAdapter.fromMetaTags(fixture("product-meta.html"))).contains(EXPECTED);
    assertThat(AltexroAdapter.fromMetaTags(fixture("product-selectors.html"))).isEmpty();
    assertThat(AltexroAdapter.fromMetaTags(fixture("no-product.html"))).isEmpty();
  }

  @Test
  void ac_4_1_1_selectorsStepReadsAltexMarkup() {
    assertThat(AltexroAdapter.fromSelectors(fixture("product-selectors.html"))).contains(EXPECTED);
    assertThat(AltexroAdapter.fromSelectors(fixture("no-product.html"))).isEmpty();
    assertThat(AltexroAdapter.fromSelectors(null)).isEmpty();
  }

  @Test
  void ac_4_1_1_selectorsStepUnparseablePriceIsNull() {
    assertThat(AltexroAdapter.fromSelectors(fixture("unparseable-price.html")))
        .hasValueSatisfying(f -> assertThat(f.priceMinor()).isNull());
  }

  @Test
  void ac_4_1_1_selectorsStepReadsOutOfStock() {
    String html =
        "<div class=\"Price-current\">99 lei</div><div class=\"Status-stock\">Stoc epuizat</div>";
    assertThat(AltexroAdapter.fromSelectors(html))
        .contains(new ExtractedFacts(9900L, "RON", ExtractedFacts.Availability.OUT_OF_STOCK, null));
  }
}
