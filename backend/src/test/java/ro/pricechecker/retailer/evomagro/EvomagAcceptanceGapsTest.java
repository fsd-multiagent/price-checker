package ro.pricechecker.retailer.evomagro;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import ro.pricechecker.adapter.AdapterOutcome;
import ro.pricechecker.adapter.Availability;
import ro.pricechecker.adapter.PageFacts;
import ro.pricechecker.adapter.UnavailableReason;
import ro.pricechecker.ean.Ean;

/**
 * Story 2.11 gaps found by Eufrosina (QA): every fixture through the adapter's public {@code
 * fetchAndExtract} (not only {@code EvomagPage}), PRD AC tags next to the story AC tags (T-1), the
 * request not depending on the EAN (AC-10.2), odd price texts staying null (AC-5.5), and the
 * fixtures and test sources staying offline and facts-only (T-2, T-3, S-7).
 */
class EvomagAcceptanceGapsTest {

  private static final URI PRODUCT_URL = URI.create("https://example.test/p/synthetic");
  private static final Ean EAN = Ean.parse("5901234123457");
  private static final Path TEST_SOURCES =
      Path.of("src/test/java/ro/pricechecker/retailer/evomagro");
  private static final Path MAIN_SOURCES =
      Path.of("src/main/java/ro/pricechecker/retailer/evomagro");

  private final EvomagAdapter adapter = new EvomagAdapter();

  private AdapterOutcome fetch(String fixture) {
    return adapter.fetchAndExtract(EAN, PRODUCT_URL, new Fixtures.FixtureHttp(fixture));
  }

  // --- 2.11.1 / AC-3.4: every extraction step through the adapter ---

  @Test
  @Tag("AC-2.11.1")
  @Tag("AC-3.4")
  void ac_2_11_1_ac_3_4_adapterFindsEveryProductFixtureThroughItsStep() {
    Map<String, PageFacts> expected =
        Map.of(
            "product-jsonld.html",
                new PageFacts(129999L, "RON", Availability.IN_STOCK, PRODUCT_URL, "5901234123457"),
            "product-meta.html",
                new PageFacts(84900L, "RON", Availability.IN_STOCK, PRODUCT_URL, null),
            "product-microdata.html",
                new PageFacts(
                    249990L, "RON", Availability.OUT_OF_STOCK, PRODUCT_URL, "5901234123457"),
            "product-mixed.html",
                new PageFacts(10000L, "RON", Availability.IN_STOCK, PRODUCT_URL, null),
            "product-meta-microdata.html",
                new PageFacts(
                    40000L, "RON", Availability.OUT_OF_STOCK, PRODUCT_URL, "5901234123457"));
    expected.forEach((fixture, facts) ->
        assertThat(fetch(fixture)).as(fixture).isEqualTo(new AdapterOutcome.Found(facts)));
  }

  @Test
  @Tag("AC-2.11.1")
  @Tag("AC-3.4")
  void ac_2_11_1_ac_3_4_foundLinkIsTheCuratedProductUrl() {
    URI other = URI.create("https://example.test/p/another");
    assertThat(adapter.fetchAndExtract(EAN, other, new Fixtures.FixtureHttp("product-meta.html")))
        .isInstanceOfSatisfying(
            AdapterOutcome.Found.class, f -> assertThat(f.facts().url()).isEqualTo(other));
  }

  // --- 2.11.2 / AC-5.5 ---

  @Test
  @Tag("AC-2.11.2")
  @Tag("AC-5.5")
  void ac_2_11_2_ac_5_5_adapterGivesUnparseableForPageWithoutFacts() {
    assertThat(fetch("no-product.html"))
        .isEqualTo(new AdapterOutcome.Unavailable(UnavailableReason.UNPARSEABLE));
  }

  @Test
  @Tag("AC-2.11.2")
  @Tag("AC-5.5")
  void ac_2_11_2_ac_5_5_adapterKeepsUnparseablePriceNull() {
    assertThat(fetch("unparseable-price.html"))
        .isEqualTo(
            new AdapterOutcome.Found(
                new PageFacts(null, "RON", Availability.IN_STOCK, PRODUCT_URL, null)));
  }

  @ParameterizedTest
  @ValueSource(strings = {"", "   ", "la cerere", "-", "N/A", "pret: contact"})
  @Tag("AC-2.11.2")
  @Tag("AC-5.5")
  void ac_2_11_2_ac_5_5_oddPriceTextGivesNullPriceNeverZero(String priceText) {
    String html =
        """
        <main itemscope itemtype="https://schema.org/Product">
          <span itemprop="price">%s</span>
          <meta itemprop="priceCurrency" content="RON">
        </main>
        """
            .formatted(priceText);
    assertThat(EvomagPage.extract(html, PRODUCT_URL))
        .isInstanceOfSatisfying(
            AdapterOutcome.Found.class, f -> assertThat(f.facts().priceMinor()).isNull());
  }

  @Test
  @Tag("AC-2.11.2")
  @Tag("AC-5.5")
  void ac_2_11_2_ac_5_5_emptyOrNullPageIsUnparseable() {
    assertThat(EvomagPage.extract("", PRODUCT_URL))
        .isEqualTo(new AdapterOutcome.Unavailable(UnavailableReason.UNPARSEABLE));
    assertThat(EvomagPage.extract(null, PRODUCT_URL))
        .isEqualTo(new AdapterOutcome.Unavailable(UnavailableReason.UNPARSEABLE));
  }

  // --- 2.11.3 / AC-10.2, AC-9.4, T-2, T-3 ---

  @Test
  @Tag("AC-2.11.3")
  @Tag("AC-10.2")
  void ac_2_11_3_ac_10_2_requestDoesNotDependOnTheEan() {
    for (String code : List.of("5901234123457", "96385074", "4006381333931")) {
      Fixtures.FixtureHttp http = new Fixtures.FixtureHttp("product-jsonld.html");
      adapter.fetchAndExtract(Ean.parse(code), PRODUCT_URL, http);
      assertThat(http.requested).as(code).containsExactly(PRODUCT_URL);
      assertThat(http.requested.getFirst().toString()).as(code).doesNotContain(code);
    }
  }

  @Test
  @Tag("AC-2.11.3")
  @Tag("AC-10.2")
  void ac_2_11_3_ac_10_2_mainSourcesUseNoNetworkApiOtherThanRetailerHttp() throws IOException {
    assertThat(sourceText(MAIN_SOURCES))
        .doesNotContain("Jsoup.connect")
        .doesNotContain("java.net.http")
        .doesNotContain("HttpURLConnection")
        .doesNotContain("openConnection")
        .doesNotContain("openStream")
        .doesNotContain("?q=")
        .doesNotContain("sn.q=")
        .doesNotContain("/search");
  }

  @Test
  @Tag("AC-2.11.3")
  @Tag("AC-9.4")
  void ac_2_11_3_ac_9_4_bundledConfigShipsEvomagEnabledOnceApproved() throws IOException {
    List<String> lines = Files.readAllLines(Path.of("..", "config", "retailers.yaml"));
    int start = lines.indexOf("  " + EvomagAdapter.RETAILER_ID + ":");
    assertThat(start).as("evoMAG entry in retailers.yaml").isNotNegative();
    List<String> block =
        lines.subList(start + 1, lines.size()).stream()
            .takeWhile(l -> l.startsWith("    "))
            .map(String::strip)
            .toList();
    assertThat(block)
        .contains("adapter: enabled", "resolution: curated"); // owner approval 2026-11-18
  }

  @Test
  @Tag("AC-2.11.3")
  @Tag("T-3")
  void ac_2_11_3_testSourcesTouchNoNetworkAndNoOtherFixtureDir() throws IOException {
    String self = getClass().getSimpleName() + ".java";
    try (Stream<Path> files = Files.list(TEST_SOURCES)) {
      for (Path file : files.filter(p -> !p.getFileName().toString().equals(self)).toList()) {
        String text = Files.readString(file);
        assertThat(text)
            .as(file.toString())
            .doesNotContain("Jsoup.connect")
            .doesNotContain("java.net.http")
            .doesNotContain("HttpURLConnection")
            .doesNotContain("openConnection")
            .doesNotContain("openStream");
        // Only the evoMAG fixture dir may be named, by constant or by literal.
        assertThat(
                text.replaceAll(
                    "\"fixtures\",\\s*(EvomagAdapter\\.RETAILER_ID|\"evomag\\.ro\")", ""))
            .as(file.toString())
            .doesNotContain("\"fixtures\"")
            .doesNotContainPattern("fixtures/(?!evomag\\.ro/)");
      }
    }
  }

  @Test
  @Tag("AC-2.11.3")
  @Tag("T-2")
  void ac_2_11_3_fixturesHoldFactsOnlyAndLinkNowhereButSchemaOrg() throws IOException {
    try (Stream<Path> files = Files.list(Fixtures.DIR)) {
      for (Path file : files.filter(p -> p.toString().endsWith(".html")).toList()) {
        String html = Files.readString(file).toLowerCase(Locale.ROOT);
        assertThat(html)
            .as(file.toString())
            .doesNotContain("<img")
            .doesNotContain("og:image")
            .doesNotContain("\"image\"")
            .doesNotContain("description")
            .doesNotContain("evomag.ro");
        assertThat(html.replaceAll("https?://schema\\.org[^\"'\\s<]*", ""))
            .as(file.toString())
            .doesNotContain("http://")
            .doesNotContain("https://");
      }
    }
  }

  private static String sourceText(Path dir) throws IOException {
    StringBuilder all = new StringBuilder();
    try (Stream<Path> files = Files.list(dir)) {
      for (Path file : files.toList()) {
        all.append(Files.readString(file)).append('\n');
      }
    }
    return all.toString();
  }
}
