package ro.pricechecker.retailer.evomagro;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.util.List;
import java.util.stream.Stream;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.TextNode;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import ro.pricechecker.adapter.AdapterOutcome;
import ro.pricechecker.adapter.Availability;
import ro.pricechecker.adapter.PageFacts;
import ro.pricechecker.ean.Ean;
import ro.pricechecker.fetch.PageStripper;
import ro.pricechecker.registry.KnownEan;
import ro.pricechecker.registry.Registry;

/**
 * Acceptance gaps for story 2.13 that {@link EvomagRealPagesTest} leaves open: the recorded pages
 * are already stripped (S-7, T-2), the generic {@code gtin} property that real pages use is read
 * and checked (C-7), and the curated Known-EAN URLs are product pages, not searches (AC-10.2).
 */
class EvomagLiveEnableGapsTest {

  private static final URI URL = URI.create("https://shop.example/p/1");
  private final EvomagAdapter adapter = new EvomagAdapter();

  private static List<String> realFixtures() throws IOException {
    try (Stream<java.nio.file.Path> files = Files.list(Fixtures.DIR)) {
      return files
          .map(p -> p.getFileName().toString())
          .filter(n -> n.startsWith("real-") && n.endsWith(".html"))
          .sorted()
          .toList();
    }
  }

  private static List<KnownEan> evomagKnownEans() {
    return new Registry()
        .knownEans().stream().filter(k -> k.domain().equals(EvomagAdapter.RETAILER_ID)).toList();
  }

  private AdapterOutcome extract(String ean, String html) {
    return adapter.fetchAndExtract(Ean.parse(ean), URL, url -> html);
  }

  @Test
  @Tag("AC-2.13.1")
  void ac_2_13_1_everyRecordedPageBelongsToAKnownEan() throws IOException {
    List<String> expected =
        evomagKnownEans().stream().map(k -> "real-" + k.ean() + ".html").sorted().toList();
    assertThat(realFixtures()).containsExactlyElementsOf(expected);
  }

  @Test
  @Tag("AC-2.13.1")
  void ac_2_13_1_recordedPagesAreAlreadyStripped() throws IOException {
    for (String fixture : realFixtures()) {
      String html = Fixtures.read(fixture);
      Document doc = Jsoup.parse(html);
      for (Element script : doc.select("script")) {
        assertThat(script.attr("type")).as(fixture + " script").contains("ld");
      }
      assertThat(doc.select("style, iframe, img, video, audio")).as(fixture).isEmpty();
      assertThat(doc.getAllElements().stream().flatMap(e -> e.textNodes().stream()))
          .as(fixture + " text nodes")
          .allSatisfy((TextNode t) -> assertThat(t.text().length()).isLessThanOrEqualTo(200));
      // Stripping again changes no fact the adapter reads.
      String ean = fixture.substring("real-".length(), fixture.length() - ".html".length());
      assertThat(extract(ean, PageStripper.strip(html))).as(fixture).isEqualTo(extract(ean, html));
    }
  }

  @Test
  @Tag("AC-2.13.1")
  void ac_2_13_1_recordedPagesHaveNoPersonalData() throws IOException {
    for (String fixture : realFixtures()) {
      Document doc = Jsoup.parse(Fixtures.read(fixture));
      assertThat(doc.select("[itemprop=review], [itemprop=author], [itemtype*=Review]"))
          .as(fixture)
          .isEmpty();
      assertThat(doc.body().text()).as(fixture).doesNotContain("@");
    }
  }

  @Test
  @Tag("AC-2.13.1")
  void ac_2_13_1_genericGtinPropertyIsRead() {
    String html =
        """
        <div itemscope itemtype="http://schema.org/Product">
          <meta itemprop="gtin" content="740617263442">
          <div itemprop="offers" itemscope itemtype="http://schema.org/Offer">
            <meta itemprop="priceCurrency" content="RON">
            <span itemprop="price" content="10.00"></span>
          </div>
        </div>
        """;
    assertThat(extract("0740617263442", html))
        .isEqualTo(
            new AdapterOutcome.Found(
                new PageFacts(1000L, "RON", Availability.UNKNOWN, URL, "740617263442")));
  }

  @Test
  @Tag("AC-2.13.1")
  void ac_2_13_1_gtinVariantsAreReadAndTheFirstInDocumentOrderWins() {
    for (String property : List.of("gtin8", "gtin12", "gtin13", "gtin14")) {
      String html =
          "<div itemscope itemtype=\"https://schema.org/Product\">"
              + "<meta itemprop=\""
              + property
              + "\" content=\"96385074\"><meta itemprop=\"gtin\" content=\"4006381333931\">"
              + "<span itemprop=\"price\" content=\"1\"></span></div>";
      assertThat(((AdapterOutcome.Found) extract("5099206027282", html)).facts().eanOnPage())
          .as(property)
          .isEqualTo("96385074");
    }
  }

  @Test
  @Tag("AC-2.13.1")
  void ac_2_13_1_realPageReportsItsOwnGtinForAnotherEan() {
    // The adapter reports the page's GTIN; LookupService turns a mismatch into NOT_FOUND (C-7).
    AdapterOutcome outcome = extract("5099206027282", Fixtures.read("real-0740617263442.html"));
    assertThat(outcome).isInstanceOf(AdapterOutcome.Found.class);
    assertThat(((AdapterOutcome.Found) outcome).facts().eanOnPage()).isEqualTo("740617263442");
  }

  @Test
  @Tag("AC-2.13.2")
  void ac_2_13_2_knownEansAreDistinctCuratedProductPages() {
    List<KnownEan> eans = evomagKnownEans();
    assertThat(eans).hasSizeGreaterThanOrEqualTo(2);
    assertThat(eans.stream().map(KnownEan::ean).distinct()).hasSameSizeAs(eans);
    assertThat(eans.stream().map(KnownEan::url).distinct()).hasSameSizeAs(eans);
    for (KnownEan known : eans) {
      URI url = URI.create(known.url());
      assertThat(url.getScheme()).as(known.url()).isEqualTo("https");
      assertThat(url.getHost())
          .as(known.url())
          .isIn(EvomagAdapter.RETAILER_ID, "www." + EvomagAdapter.RETAILER_ID);
      assertThat(url.getQuery()).as(known.url()).isNull();
      assertThat(url.getFragment()).as(known.url()).isNull();
      assertThat(url.getPath()).as(known.url()).endsWith(".html").doesNotContain("search");
    }
  }
}
