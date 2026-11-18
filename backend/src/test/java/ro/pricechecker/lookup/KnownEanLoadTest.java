package ro.pricechecker.lookup;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import ro.pricechecker.adapter.AdapterOutcome;
import ro.pricechecker.adapter.Availability;
import ro.pricechecker.adapter.PageFacts;
import ro.pricechecker.adapter.RetailerAdapter;
import ro.pricechecker.adapter.RetailerHttp;
import ro.pricechecker.adapter.UnavailableReason;
import ro.pricechecker.ean.Ean;
import ro.pricechecker.registry.MutableClock;
import ro.pricechecker.registry.Registry;
import ro.pricechecker.registry.TestRegistries;

/** AC 3.5.2: a Known EAN load uses the Lookup normalization and never the cache. */
@Tag("AC-3.5.2")
class KnownEanLoadTest {

  private static final Instant NOW = Instant.parse("2026-11-18T10:00:00Z");
  private static final URI URL = URI.create("https://a.example/p/1");

  private final MutableClock clock = new MutableClock(NOW);

  private KnownEanLoad loader(AdapterOutcome outcome) {
    RetailerAdapter adapter =
        new RetailerAdapter() {
          @Override
          public String retailerId() {
            return "a.example";
          }

          @Override
          public AdapterOutcome fetchAndExtract(Ean ean, URI productUrl, RetailerHttp http) {
            try {
              http.get(productUrl);
            } catch (java.io.IOException e) {
              return new AdapterOutcome.Unavailable(UnavailableReason.HTTP_ERROR);
            }
            return outcome;
          }
        };
    Registry registry = TestRegistries.twoEnabled(4000);
    AdapterCatalog catalog =
        new AdapterCatalog(
            List.of(adapter, StubAdapters.noop("b.example"), StubAdapters.noop("c.example")),
            registry);
    return new KnownEanLoad(catalog, clock, new LookupLog(clock));
  }

  private static AdapterOutcome found(String currency) {
    return new AdapterOutcome.Found(
        new PageFacts(1999L, currency, Availability.IN_STOCK, null, TestRegistries.EAN_A));
  }

  @Test
  void ac_3_5_2_foundPageIsNormalizedLikeALookup() {
    LookupRow row = loader(found("RON")).load("a.example", TestRegistries.EAN_A, URL, url -> "p");

    assertThat(row.status()).isEqualTo(RowStatus.FOUND);
    assertThat(row.priceMinor()).isEqualTo(1999L);
    assertThat(row.availability()).isEqualTo(RowAvailability.IN_STOCK);
    assertThat(row.url()).isEqualTo(URL);
    assertThat(row.fetchedAt()).isEqualTo(NOW);
  }

  @Test
  void ac_3_5_2_otherCurrencyHasNoPrice() {
    LookupRow row = loader(found("EUR")).load("a.example", TestRegistries.EAN_A, URL, url -> "p");

    assertThat(row.status()).isEqualTo(RowStatus.FOUND);
    assertThat(row.priceMinor()).isNull();
  }

  @Test
  void ac_3_5_2_unavailableOutcomeOrFailingSourceIsUnavailable() {
    LookupRow unavailable =
        loader(new AdapterOutcome.Unavailable(UnavailableReason.TIMEOUT))
            .load("a.example", TestRegistries.EAN_A, URL, url -> "p");
    LookupRow failing =
        loader(found("RON"))
            .load(
                "a.example",
                TestRegistries.EAN_A,
                URL,
                url -> {
                  throw new java.io.IOException("down");
                });

    assertThat(unavailable.status()).isEqualTo(RowStatus.UNAVAILABLE);
    assertThat(failing.status()).isEqualTo(RowStatus.UNAVAILABLE);
  }

  @Test
  void ac_3_5_2_unknownRetailerOrInvalidEanIsUnavailable() {
    KnownEanLoad load = loader(found("RON"));

    assertThat(load.load("z.example", TestRegistries.EAN_A, URL, url -> "p").status())
        .isEqualTo(RowStatus.UNAVAILABLE);
    assertThat(load.load("a.example", "123", URL, url -> "p").status())
        .isEqualTo(RowStatus.UNAVAILABLE);
  }

  @Test
  void ac_3_5_2_sameEanLoadedTwiceSendsTwice() {
    AtomicInteger sends = new AtomicInteger();
    KnownEanLoad load = loader(found("RON"));

    load.load("a.example", TestRegistries.EAN_A, URL, url -> "p" + sends.incrementAndGet());
    load.load("a.example", TestRegistries.EAN_A, URL, url -> "p" + sends.incrementAndGet());

    assertThat(sends).hasValue(2);
  }
}
