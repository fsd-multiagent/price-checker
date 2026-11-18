package ro.pricechecker.retailer.celro;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import ro.pricechecker.adapter.AdapterOutcome;
import ro.pricechecker.adapter.Availability;
import ro.pricechecker.adapter.PageFacts;
import ro.pricechecker.adapter.RetailerHttp;
import ro.pricechecker.ean.Ean;
import ro.pricechecker.registry.KnownEan;
import ro.pricechecker.registry.Registry;

/**
 * Story 2.12: pages recorded from cel.ro through {@code FetchClient}, stripped by {@code
 * PageStripper} and cut to their fact elements (T-2, S-7). One page per cel.ro Known EAN.
 */
class CelroRecordedPagesTest {

  private static final Path FIXTURES = Path.of("../fixtures/cel.ro").toAbsolutePath().normalize();

  private final CelroAdapter adapter = new CelroAdapter();

  private static String recorded(String ean) throws IOException {
    return Files.readString(FIXTURES.resolve("recorded-" + ean + ".html"));
  }

  @ParameterizedTest
  @CsvSource({"5099206027282, 7900", "0740617261219, 38800"})
  @Tag("AC-2.12.1")
  void ac_2_12_1_recordedPageGivesPriceAvailabilityAndLink(String ean, long priceMinor)
      throws IOException {
    URI url = URI.create("https://www.cel.ro/recorded-" + ean + "/");
    List<URI> requested = new ArrayList<>();
    String html = recorded(ean);
    RetailerHttp http =
        u -> {
          requested.add(u);
          return html;
        };

    AdapterOutcome outcome = adapter.fetchAndExtract(Ean.parse(ean), url, http);

    assertThat(requested).containsExactly(url);
    // The recorded pages carry no EAN, so eanOnPage stays null and the row stays FOUND (AD-11).
    assertThat(outcome)
        .isEqualTo(
            new AdapterOutcome.Found(
                new PageFacts(priceMinor, "RON", Availability.IN_STOCK, url, null)));
  }

  @ParameterizedTest
  @CsvSource({"5099206027282, 7900", "0740617261219, 38800"})
  @Tag("AC-2.12.1")
  void ac_2_12_1_realSelectorsReadPriceAndCurrencyWord(String ean, long priceMinor)
      throws IOException {
    assertThat(CelroAdapter.fromSelectors(recorded(ean)))
        .hasValueSatisfying(f -> {
          assertThat(f.priceMinor()).isEqualTo(priceMinor);
          assertThat(f.currency()).isEqualTo("RON");
          // The served stock element is empty (filled by script on cel.ro).
          assertThat(f.availability().name()).isEqualTo("UNKNOWN");
          assertThat(f.gtin()).isNull();
        });
  }

  @Test
  @Tag("AC-2.12.2")
  void ac_2_12_2_everyCelroKnownEanHasARecordedFoundPage() throws IOException {
    Map<String, String> known =
        new Registry()
            .knownEans().stream()
                .filter(k -> k.domain().equals(CelroAdapter.RETAILER_ID))
                .collect(Collectors.toMap(KnownEan::ean, KnownEan::url));
    assertThat(known).hasSizeGreaterThanOrEqualTo(2);
    for (Map.Entry<String, String> e : known.entrySet()) {
      URI url = URI.create(e.getValue());
      String html = recorded(e.getKey());
      AdapterOutcome outcome = adapter.fetchAndExtract(Ean.parse(e.getKey()), url, u -> html);
      assertThat(outcome).as(e.getKey()).isInstanceOf(AdapterOutcome.Found.class);
      PageFacts facts = ((AdapterOutcome.Found) outcome).facts();
      assertThat(facts.priceMinor()).as(e.getKey()).isNotNull();
      assertThat(facts.availability()).as(e.getKey()).isNotEqualTo(Availability.UNKNOWN);
      assertThat(facts.url()).as(e.getKey()).isEqualTo(url);
    }
  }
}
