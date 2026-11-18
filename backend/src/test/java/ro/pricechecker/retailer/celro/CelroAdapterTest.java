package ro.pricechecker.retailer.celro;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
import ro.pricechecker.registry.Registry;
import ro.pricechecker.registry.RetailerConfig;

/** Story 2.10: the cel.ro adapter against synthetic fixtures only (T-2, T-3). */
class CelroAdapterTest {

  private static final Path FIXTURES = Path.of("../fixtures/cel.ro").toAbsolutePath().normalize();
  private static final URI PRODUCT_URL = URI.create("https://www.cel.ro/produs-sintetic/");
  private static final Ean EAN = Ean.parse("5941234567899");
  private static final String GTIN = "5941234567899";

  private final CelroAdapter adapter = new CelroAdapter();

  /** Serves one file from {@code fixtures/cel.ro/} and records every requested URL. */
  private static final class FixtureHttp implements RetailerHttp {
    private final String fixture;
    private final List<URI> requested = new ArrayList<>();

    FixtureHttp(String fixture) {
      this.fixture = fixture;
    }

    @Override
    public String get(URI url) throws IOException {
      requested.add(url);
      Path file = FIXTURES.resolve(fixture).normalize();
      if (!file.startsWith(FIXTURES)) {
        throw new IOException("outside fixtures: " + fixture);
      }
      return Files.readString(file);
    }
  }

  private AdapterOutcome run(FixtureHttp http) {
    AdapterOutcome outcome = adapter.fetchAndExtract(EAN, PRODUCT_URL, http);
    assertThat(http.requested).containsExactly(PRODUCT_URL);
    return outcome;
  }

  @Test
  @Tag("AC-2.10.1")
  void ac_2_10_1_retailerIdIsCelro() {
    assertThat(adapter.retailerId()).isEqualTo("cel.ro");
  }

  @Test
  @Tag("AC-2.10.1")
  void ac_2_10_1_jsonLdPageGivesFacts() {
    assertThat(run(new FixtureHttp("product-jsonld.html")))
        .isEqualTo(
            new AdapterOutcome.Found(
                new PageFacts(129999L, "RON", Availability.IN_STOCK, PRODUCT_URL, GTIN)));
  }

  @Test
  @Tag("AC-2.10.1")
  void ac_2_10_1_metaOnlyPageGivesFacts() {
    assertThat(run(new FixtureHttp("product-meta.html")))
        .isEqualTo(
            new AdapterOutcome.Found(
                new PageFacts(84950L, "RON", Availability.OUT_OF_STOCK, PRODUCT_URL, GTIN)));
  }

  @Test
  @Tag("AC-2.10.1")
  void ac_2_10_1_selectorsOnlyPageGivesFacts() {
    assertThat(run(new FixtureHttp("product-selectors.html")))
        .isEqualTo(
            new AdapterOutcome.Found(
                new PageFacts(129999L, "RON", Availability.IN_STOCK, PRODUCT_URL, GTIN)));
  }

  @Test
  @Tag("AC-2.10.1")
  void ac_2_10_1_earlierStepWinsAndLaterStepFillsMissingFields() {
    assertThat(run(new FixtureHttp("product-mixed.html")))
        .isEqualTo(
            new AdapterOutcome.Found(
                new PageFacts(129999L, "RON", Availability.IN_STOCK, PRODUCT_URL, GTIN)));
  }

  @Test
  @Tag("AC-2.10.1")
  void ac_2_10_1_negatedOrPlainStockText() {
    assertThat(CelroAdapter.stockOf("Nu este în stoc").name()).isEqualTo("OUT_OF_STOCK");
    assertThat(CelroAdapter.stockOf("In stoc").name()).isEqualTo("IN_STOCK");
    assertThat(CelroAdapter.stockOf("Livrare nu se aplica").name()).isEqualTo("UNKNOWN");
  }

  @Test
  @Tag("AC-2.10.2")
  void ac_2_10_2_unparseablePriceIsNullNeverZero() {
    AdapterOutcome outcome = run(new FixtureHttp("product-bad-price.html"));
    assertThat(outcome)
        .isEqualTo(
            new AdapterOutcome.Found(
                new PageFacts(null, "RON", Availability.IN_STOCK, PRODUCT_URL, GTIN)));
  }

  @Test
  @Tag("AC-2.10.2")
  void ac_2_10_2_pageWithoutProductFactsIsUnparseable() {
    assertThat(run(new FixtureHttp("no-product.html")))
        .isEqualTo(new AdapterOutcome.Unavailable(UnavailableReason.UNPARSEABLE));
  }

  @Test
  @Tag("AC-2.10.2")
  void ac_2_10_2_transportFailureIsHttpError() {
    List<URI> requested = new ArrayList<>();
    RetailerHttp failing =
        url -> {
          requested.add(url);
          throw new IOException("synthetic transport failure");
        };
    assertThat(adapter.fetchAndExtract(EAN, PRODUCT_URL, failing))
        .isEqualTo(new AdapterOutcome.Unavailable(UnavailableReason.HTTP_ERROR));
    assertThat(requested).containsExactly(PRODUCT_URL);
  }

  @Test
  @Tag("AC-2.10.1")
  void ac_2_10_1_selectorStepIsSeparateAndNeedsCurrencyWordForRon() {
    assertThat(
            CelroAdapter.fromSelectors(
                "<div id=\"product-price\">1.299,99</div><div id=\"info_stoc\">Stoc"
                    + " epuizat</div>"))
        .hasValueSatisfying(f -> {
          assertThat(f.priceMinor()).isEqualTo(129999L);
          assertThat(f.currency()).isNull();
          assertThat(f.availability().name()).isEqualTo("OUT_OF_STOCK");
        });
    assertThat(CelroAdapter.fromSelectors("<p>nimic</p>")).isEmpty();
    assertThat(CelroAdapter.stockOf("Indisponibil").name()).isEqualTo("OUT_OF_STOCK");
    assertThat(CelroAdapter.stockOf("La comandă").name()).isEqualTo("UNKNOWN");
  }

  @Test
  @Tag("AC-2.10.3")
  void ac_2_10_3_fixturesAreLocalFilesOnly() throws IOException {
    try (var files = Files.list(FIXTURES)) {
      assertThat(files.map(p -> p.getFileName().toString()))
          .contains(
              "product-jsonld.html",
              "product-meta.html",
              "product-selectors.html",
              "product-bad-price.html",
              "no-product.html");
    }
    FixtureHttp escaping = new FixtureHttp("../../config/retailers.yaml");
    assertThatThrownBy(() -> escaping.get(PRODUCT_URL)).isInstanceOf(IOException.class);
  }

  @Test
  @Tag("AC-2.10.3")
  void ac_2_10_3_celroEnabledInRetailersYamlOnlyBecauseApproved() {
    Registry registry = new Registry();
    assertThat(registry.isApproved("cel.ro")).isTrue(); // owner approval 2026-11-18
    assertThat(registry.retailers())
        .filteredOn(r -> r.domain().equals("cel.ro"))
        .singleElement()
        .extracting(RetailerConfig::adapter)
        .isEqualTo("enabled");
  }
}
