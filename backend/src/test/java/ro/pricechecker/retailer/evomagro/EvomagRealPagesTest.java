package ro.pricechecker.retailer.evomagro;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.nio.file.Files;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import ro.pricechecker.adapter.AdapterOutcome;
import ro.pricechecker.adapter.Availability;
import ro.pricechecker.adapter.PageFacts;
import ro.pricechecker.ean.Ean;
import ro.pricechecker.registry.KnownEan;
import ro.pricechecker.registry.Registry;

/**
 * The adapter against the evoMAG pages recorded in story 2.13 through the shared fetch client and
 * {@code PageStripper} (T-2): every evoMAG Known EAN has a recorded page that gives FOUND, read
 * from its curated URL without any search request (AC 2.13.1, AC 2.13.2).
 */
class EvomagRealPagesTest {

  private final EvomagAdapter adapter = new EvomagAdapter();

  private static List<KnownEan> evomagKnownEans() {
    return new Registry()
        .knownEans().stream().filter(k -> k.domain().equals(EvomagAdapter.RETAILER_ID)).toList();
  }

  @Test
  @Tag("AC-2.13.2")
  void ac_2_13_2_everyKnownEanHasARecordedPageThatIsFound() {
    List<KnownEan> eans = evomagKnownEans();
    assertThat(eans).hasSizeGreaterThanOrEqualTo(2);
    for (KnownEan known : eans) {
      String fixture = "real-" + known.ean() + ".html";
      assertThat(Files.exists(Fixtures.DIR.resolve(fixture))).as(fixture).isTrue();
      URI url = URI.create(known.url());
      Fixtures.FixtureHttp http = new Fixtures.FixtureHttp(fixture);

      AdapterOutcome outcome = adapter.fetchAndExtract(Ean.parse(known.ean()), url, http);

      assertThat(http.requested).as(fixture).containsExactly(url);
      assertThat(url.getQuery()).as("no search request").isNull();
      assertThat(outcome).as(fixture).isInstanceOf(AdapterOutcome.Found.class);
      PageFacts facts = ((AdapterOutcome.Found) outcome).facts();
      assertThat(facts.currency()).as(fixture).isEqualTo("RON");
      assertThat(facts.priceMinor()).as(fixture).isPositive();
      assertThat(facts.availability()).as(fixture).isNotEqualTo(Availability.UNKNOWN);
      assertThat(facts.eanOnPage()).as(fixture).isNotNull();
      assertThat(stripLeadingZeros(facts.eanOnPage()))
          .as(fixture)
          .isEqualTo(stripLeadingZeros(known.ean()));
    }
  }

  @Test
  @Tag("AC-2.13.1")
  void ac_2_13_1_mousePageGivesItsRecordedFacts() {
    URI url = URI.create("https://shop.example/p/mouse");
    assertThat(
            adapter.fetchAndExtract(
                Ean.parse("5099206027282"),
                url,
                new Fixtures.FixtureHttp("real-5099206027282.html")))
        .isEqualTo(
            new AdapterOutcome.Found(
                new PageFacts(5999L, "RON", Availability.IN_STOCK, url, "5099206027282")));
  }

  @Test
  @Tag("AC-2.13.1")
  void ac_2_13_1_ssdPageGivesItsTwelveDigitGtin() {
    URI url = URI.create("https://shop.example/p/ssd");
    assertThat(
            adapter.fetchAndExtract(
                Ean.parse("0740617263442"),
                url,
                new Fixtures.FixtureHttp("real-0740617263442.html")))
        .isEqualTo(
            new AdapterOutcome.Found(
                new PageFacts(49999L, "RON", Availability.IN_STOCK, url, "740617263442")));
  }

  private static String stripLeadingZeros(String digits) {
    return digits.replaceFirst("^0+", "");
  }
}
