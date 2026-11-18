package ro.pricechecker.retailer.evomagro;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
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

class EvomagAdapterTest {

  private static final Ean EAN = Ean.parse("5901234123457");
  private static final URI PRODUCT_URL = URI.create("https://example.test/p/synthetic");

  private final EvomagAdapter adapter = new EvomagAdapter();

  @Test
  @Tag("AC-2.11.1")
  void ac_2_11_1_fetchAndExtractReturnsFoundFromFixture() {
    Fixtures.FixtureHttp http = new Fixtures.FixtureHttp("product-jsonld.html");
    assertThat(adapter.fetchAndExtract(EAN, PRODUCT_URL, http))
        .isEqualTo(
            new AdapterOutcome.Found(
                new PageFacts(
                    129999L, "RON", Availability.IN_STOCK, PRODUCT_URL, "5901234123457")));
  }

  @Test
  @Tag("AC-2.11.3")
  void ac_2_11_3_requestsOnlyTheProductUrlOnceAndNeverSearch() {
    for (String fixture :
        List.of(
            "product-jsonld.html",
            "product-meta.html",
            "product-microdata.html",
            "product-meta-microdata.html",
            "product-mixed.html",
            "no-product.html",
            "unparseable-price.html")) {
      Fixtures.FixtureHttp http = new Fixtures.FixtureHttp(fixture);
      adapter.fetchAndExtract(EAN, PRODUCT_URL, http);
      assertThat(http.requested).as(fixture).containsExactly(PRODUCT_URL);
      assertThat(http.requested)
          .allSatisfy(uri -> {
            assertThat(uri.getQuery()).isNull();
            assertThat(uri.getPath()).doesNotContain("/search");
          });
    }
  }

  @Test
  @Tag("AC-2.11.3")
  void ac_2_11_3_httpErrorIsUnavailable() {
    RetailerHttp failing =
        url -> {
          throw new IOException("synthetic failure");
        };
    assertThat(adapter.fetchAndExtract(EAN, PRODUCT_URL, failing))
        .isEqualTo(new AdapterOutcome.Unavailable(UnavailableReason.HTTP_ERROR));
  }

  @Test
  @Tag("AC-2.11.3")
  void ac_2_11_3_retailerIdMatchesConfigAndIsEnabledOnlyBecauseApproved() {
    assertThat(adapter.retailerId()).isEqualTo(EvomagAdapter.RETAILER_ID);
    Registry registry = new Registry();
    assertThat(registry.isApproved(EvomagAdapter.RETAILER_ID))
        .isTrue(); // owner approval 2026-11-18
    assertThat(registry.retailers())
        .filteredOn(r -> r.domain().equals(EvomagAdapter.RETAILER_ID))
        .singleElement()
        .extracting(RetailerConfig::enabled)
        .isEqualTo(true);
  }

  @Test
  @Tag("AC-2.11.3")
  void ac_2_11_3_testsReadOnlyEvomagFixtures() throws IOException {
    assertThat(Fixtures.DIR.normalize()).isEqualTo(Path.of("..", "fixtures", "evomag.ro"));
    try (Stream<Path> files = Files.list(Fixtures.DIR)) {
      assertThat(files.map(p -> p.getFileName().toString()))
          .contains("README.md", "product-jsonld.html", "no-product.html");
    }
  }
}
