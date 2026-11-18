package ro.pricechecker.retailer.celro;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import ro.pricechecker.adapter.AdapterOutcome;
import ro.pricechecker.adapter.Availability;
import ro.pricechecker.adapter.PageFacts;
import ro.pricechecker.adapter.RetailerHttp;
import ro.pricechecker.adapter.UnavailableReason;
import ro.pricechecker.ean.Ean;

/**
 * Story 2.10, QA additions by Eufrosina (QA): precedence of meta over selectors, unparseable
 * selector price, empty body, URL pass-through and facts-only fixtures (T-1, T-2, T-3, S-7).
 */
class CelroAdapterCoverageTest {

  private static final Path FIXTURES = Path.of("../fixtures/cel.ro").toAbsolutePath().normalize();
  private static final URI PRODUCT_URL = URI.create("https://www.cel.ro/produs-sintetic/");
  private static final Ean EAN = Ean.parse("5941234567899");
  private static final String GTIN = "5941234567899";

  private final CelroAdapter adapter = new CelroAdapter();

  /** Serves one fixture from {@code fixtures/cel.ro/} and records every requested URL. */
  private static RetailerHttp fixture(String name, List<URI> requested) {
    return url -> {
      requested.add(url);
      Path file = FIXTURES.resolve(name).normalize();
      if (!file.startsWith(FIXTURES)) {
        throw new IOException("outside fixtures: " + name);
      }
      return Files.readString(file);
    };
  }

  private AdapterOutcome run(String name, URI url) {
    List<URI> requested = new ArrayList<>();
    AdapterOutcome outcome = adapter.fetchAndExtract(EAN, url, fixture(name, requested));
    assertThat(requested).containsExactly(url);
    return outcome;
  }

  @Test
  @Tag("AC-2.10.1")
  void ac_2_10_1_metaPriceBeatsSelectorPriceAndSelectorsFillStockAndEan() {
    assertThat(run("product-meta-selectors.html", PRODUCT_URL))
        .isEqualTo(
            new AdapterOutcome.Found(
                new PageFacts(84950L, "RON", Availability.IN_STOCK, PRODUCT_URL, GTIN)));
  }

  @Test
  @Tag("AC-2.10.1")
  void ac_2_10_1_pageFactsUrlIsTheRequestedProductUrl() {
    URI other = URI.create("https://www.cel.ro/alt-produs-sintetic/?p=2");
    AdapterOutcome outcome = run("product-jsonld.html", other);
    assertThat(outcome).isInstanceOf(AdapterOutcome.Found.class);
    assertThat(((AdapterOutcome.Found) outcome).facts().url()).isEqualTo(other);
  }

  @Test
  @Tag("AC-2.10.2")
  void ac_2_10_2_unparseableSelectorPriceIsNullNotZero() {
    assertThat(run("product-selectors-bad-price.html", PRODUCT_URL))
        .isEqualTo(
            new AdapterOutcome.Found(
                new PageFacts(null, null, Availability.OUT_OF_STOCK, PRODUCT_URL, GTIN)));
  }

  @Test
  @Tag("AC-2.10.2")
  void ac_2_10_2_emptyBodyIsUnparseable() {
    List<URI> requested = new ArrayList<>();
    RetailerHttp empty =
        url -> {
          requested.add(url);
          return "";
        };
    assertThat(adapter.fetchAndExtract(EAN, PRODUCT_URL, empty))
        .isEqualTo(new AdapterOutcome.Unavailable(UnavailableReason.UNPARSEABLE));
    assertThat(requested).containsExactly(PRODUCT_URL);
  }

  static Stream<Path> htmlFixtures() throws IOException {
    try (Stream<Path> files = Files.list(FIXTURES)) {
      return files.filter(p -> p.toString().endsWith(".html")).sorted().toList().stream();
    }
  }

  @ParameterizedTest
  @MethodSource("htmlFixtures")
  @Tag("AC-2.10.3")
  void ac_2_10_3_fixtureHoldsFactsOnlyAndNoExternalReference(Path file) throws IOException {
    String html = Files.readString(file).toLowerCase(Locale.ROOT);
    assertThat(html)
        .as("%s holds facts only (T-2, S-7)", file.getFileName())
        .doesNotContain("<img", "src=", "href=", "<link", "\"image\"", "\"description\"");
    assertThat(html.length()).as("%s stays small", file.getFileName()).isLessThan(2_000);
  }
}
