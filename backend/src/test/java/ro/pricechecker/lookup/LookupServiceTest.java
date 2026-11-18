package ro.pricechecker.lookup;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import ro.pricechecker.adapter.AdapterOutcome;
import ro.pricechecker.adapter.Availability;
import ro.pricechecker.adapter.PageFacts;
import ro.pricechecker.adapter.RetailerAdapter;
import ro.pricechecker.adapter.RetailerHttp;
import ro.pricechecker.adapter.UnavailableReason;
import ro.pricechecker.cache.ResultCache;
import ro.pricechecker.ean.Ean;
import ro.pricechecker.registry.TestRegistries;

class LookupServiceTest {

  private static final Instant NOW = Instant.parse("2026-11-18T10:15:30Z");
  private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
  private static final RetailerHttp HTTP = url -> "";

  private final List<String> calls = new ArrayList<>();

  private RetailerAdapter adapter(String id, Supplier<AdapterOutcome> outcome) {
    return new RetailerAdapter() {
      @Override
      public String retailerId() {
        return id;
      }

      @Override
      public AdapterOutcome fetchAndExtract(Ean ean, URI productUrl, RetailerHttp http) {
        calls.add(id);
        return outcome.get();
      }
    };
  }

  private LookupService service(Optional<RetailerHttp> http, Supplier<AdapterOutcome> aOutcome) {
    var registry = TestRegistries.mixed();
    var catalog =
        new AdapterCatalog(
            List.of(
                adapter("a.example", aOutcome),
                adapter("b.example", aOutcome),
                adapter("c.example", aOutcome)),
            registry);
    return new LookupService(
        registry,
        catalog,
        http,
        CLOCK,
        TestRegistries.runtimeAllowing(CLOCK, "a.example", "b.example", "c.example"),
        TestRegistries.blockStore(TestRegistries.runtimeAllowing(CLOCK), CLOCK),
        new ResultCache<>(Duration.ofMinutes(15), CLOCK),
        new LookupLog(CLOCK));
  }

  private static Ean ean(String value) {
    return Ean.parse(value);
  }

  @Test
  @Tag("AC-1.6.2")
  void ac_1_6_2_oneRowPerApprovedRetailerAndDisabledIsDisabled() {
    LookupService service =
        service(Optional.of(HTTP), () -> new AdapterOutcome.Unavailable(UnavailableReason.TIMEOUT));
    LookupResult result = service.lookup(ean(TestRegistries.EAN_A));
    assertThat(result.ean()).isEqualTo(TestRegistries.EAN_A);
    assertThat(result.rows())
        .extracting(LookupRow::retailerId, LookupRow::status)
        .containsExactly(
            org.assertj.core.groups.Tuple.tuple("a.example", RowStatus.UNAVAILABLE),
            org.assertj.core.groups.Tuple.tuple("b.example", RowStatus.DISABLED));
    assertThat(calls).containsExactly("a.example");
  }

  @Test
  @Tag("AC-1.6.2")
  void ac_1_6_2_unknownEanIsNotFoundWithoutAdapterCall() {
    LookupService service =
        service(
            Optional.of(HTTP),
            () -> new AdapterOutcome.Found(new PageFacts(1L, "RON", null, null, null)));
    LookupResult result = service.lookup(ean("5901234123457"));
    assertThat(result.rows())
        .extracting(LookupRow::retailerId, LookupRow::status)
        .containsExactly(
            org.assertj.core.groups.Tuple.tuple("a.example", RowStatus.NOT_FOUND),
            org.assertj.core.groups.Tuple.tuple("b.example", RowStatus.DISABLED));
    assertThat(calls).isEmpty();
  }

  @Test
  @Tag("AC-1.6.4")
  void ac_1_6_4_adapterExceptionBecomesUnavailable() {
    LookupService service =
        service(
            Optional.of(HTTP),
            () -> {
              throw new IllegalStateException("boom");
            });
    LookupRow row = service.lookup(ean(TestRegistries.EAN_A)).rows().get(0);
    assertThat(row.status()).isEqualTo(RowStatus.UNAVAILABLE);
    assertThat(row.priceMinor()).isNull();
    assertThat(row.lowest()).isFalse();
  }

  @Test
  @Tag("AC-1.6.4")
  void ac_1_6_4_missingHttpFailsClosedAsUnavailable() {
    LookupService service =
        service(
            Optional.empty(),
            () -> new AdapterOutcome.Found(new PageFacts(1L, "RON", null, null, null)));
    assertThat(service.lookup(ean(TestRegistries.EAN_A)).rows().get(0).status())
        .isEqualTo(RowStatus.UNAVAILABLE);
    assertThat(calls).isEmpty();
  }

  @Test
  @Tag("AC-1.6.4")
  void ac_1_6_4_foundRowCarriesFactsAndClockTime() {
    URI page = URI.create("https://a.example/page");
    LookupService service =
        service(
            Optional.of(HTTP),
            () ->
                new AdapterOutcome.Found(
                    new PageFacts(12345L, "RON", Availability.IN_STOCK, page, null)));
    LookupRow row = service.lookup(ean(TestRegistries.EAN_A)).rows().get(0);
    assertThat(row.status()).isEqualTo(RowStatus.FOUND);
    assertThat(row.priceMinor()).isEqualTo(12345L);
    assertThat(row.availability()).isEqualTo(RowAvailability.IN_STOCK);
    assertThat(row.url()).isEqualTo(page);
    assertThat(row.fetchedAt()).isEqualTo(NOW);
    assertThat(row.lowest()).isTrue();
  }

  @Test
  @Tag("AC-1.6.4")
  void ac_1_6_4_adapterNotFoundBecomesNotFoundRow() {
    LookupService service = service(Optional.of(HTTP), AdapterOutcome.NotFound::new);
    LookupRow row = service.lookup(ean(TestRegistries.EAN_A)).rows().get(0);
    assertThat(row.status()).isEqualTo(RowStatus.NOT_FOUND);
    assertThat(row.url()).isNull();
    assertThat(calls).containsExactly("a.example");
  }

  @Test
  @Tag("AC-1.6.4")
  void ac_1_6_4_foundWithoutUrlOrAvailabilityUsesKnownUrlAndUnknown() {
    LookupService service =
        service(
            Optional.of(HTTP),
            () -> new AdapterOutcome.Found(new PageFacts(100L, "RON", null, null, null)));
    LookupRow row = service.lookup(ean(TestRegistries.EAN_A)).rows().get(0);
    assertThat(row.status()).isEqualTo(RowStatus.FOUND);
    assertThat(row.url()).isEqualTo(URI.create("https://a.example/p/1"));
    assertThat(row.availability()).isEqualTo(RowAvailability.UNKNOWN);
  }
}
