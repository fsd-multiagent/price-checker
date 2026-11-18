package ro.pricechecker.lookup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.groups.Tuple.tuple;

import java.lang.reflect.RecordComponent;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import ro.pricechecker.adapter.AdapterOutcome;
import ro.pricechecker.adapter.Availability;
import ro.pricechecker.adapter.PageFacts;
import ro.pricechecker.adapter.RetailerAdapter;
import ro.pricechecker.adapter.RetailerHttp;
import ro.pricechecker.adapter.UnavailableReason;
import ro.pricechecker.cache.ResultCache;
import ro.pricechecker.ean.Ean;
import ro.pricechecker.registry.BlockStore;
import ro.pricechecker.registry.MutableClock;
import ro.pricechecker.registry.Registry;
import ro.pricechecker.registry.RuntimeSwitch;
import ro.pricechecker.registry.TestRegistries;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Story 2.8: parallel execution, deadlines, AD-10 precedence, normalization and the cache. */
class LookupExecutionTest {

  private static final Instant T0 = Instant.parse("2026-11-18T10:15:30Z");
  private static final Duration TTL = Duration.ofMinutes(15);
  private static final RetailerHttp HTTP = url -> "";
  private static final Ean EAN = Ean.parse(TestRegistries.EAN_A);

  private final MutableClock clock = new MutableClock(T0);
  private final RuntimeSwitch runtime =
      TestRegistries.runtimeAllowing(clock, "a.example", "b.example", "c.example");
  private final BlockStore blocks = TestRegistries.blockStore(runtime, clock);
  private final List<String> calls = Collections.synchronizedList(new ArrayList<>());
  private final List<URI> urls = Collections.synchronizedList(new ArrayList<>());

  private RetailerAdapter adapter(String id, Supplier<AdapterOutcome> outcome) {
    return new RetailerAdapter() {
      @Override
      public String retailerId() {
        return id;
      }

      @Override
      public AdapterOutcome fetchAndExtract(Ean ean, URI productUrl, RetailerHttp http) {
        calls.add(id);
        urls.add(productUrl);
        return outcome.get();
      }
    };
  }

  private LookupService service(Registry registry, Map<String, Supplier<AdapterOutcome>> outcomes) {
    List<RetailerAdapter> list = new ArrayList<>();
    for (String id : List.of("a.example", "b.example", "c.example")) {
      list.add(adapter(id, outcomes.getOrDefault(id, AdapterOutcome.NotFound::new)));
    }
    return new LookupService(
        registry,
        new AdapterCatalog(list, registry),
        Optional.of(id -> HTTP),
        clock,
        runtime,
        blocks,
        new ResultCache<>(TTL, clock),
        new LookupLog(clock));
  }

  private LookupService serviceA(Supplier<AdapterOutcome> a) {
    return service(TestRegistries.mixed(), Map.of("a.example", a));
  }

  private static Supplier<AdapterOutcome> found(PageFacts facts) {
    return () -> new AdapterOutcome.Found(facts);
  }

  private static PageFacts ron(long price) {
    return new PageFacts(price, "RON", Availability.IN_STOCK, null, EAN.value());
  }

  private static LookupRow rowA(LookupResult result) {
    return result.rows().stream()
        .filter(r -> r.retailerId().equals("a.example"))
        .findFirst()
        .orElseThrow();
  }

  // --- AC 2.8.1 -------------------------------------------------------------------------------

  @Test
  @Tag("AC-2.8.1")
  void ac_2_8_1_retailersRunAtOnceEachOnAVirtualThread() {
    CyclicBarrier barrier = new CyclicBarrier(2);
    List<Boolean> virtual = Collections.synchronizedList(new ArrayList<>());
    Supplier<AdapterOutcome> meetTheOther =
        () -> {
          virtual.add(Thread.currentThread().isVirtual());
          try {
            // Each waits for the other: both finish only if they run at the same time.
            barrier.await(10, TimeUnit.SECONDS);
          } catch (Exception e) {
            return new AdapterOutcome.Unavailable(UnavailableReason.TIMEOUT);
          }
          return new AdapterOutcome.Found(ron(100));
        };
    LookupService service =
        service(
            TestRegistries.twoEnabled(20_000),
            Map.of("a.example", meetTheOther, "b.example", meetTheOther));

    LookupResult result = service.lookup(EAN);

    assertThat(result.rows())
        .extracting(LookupRow::retailerId, LookupRow::status)
        .containsExactlyInAnyOrder(
            tuple("a.example", RowStatus.FOUND), tuple("b.example", RowStatus.FOUND));
    assertThat(virtual).containsExactly(true, true);
  }

  // --- AC 2.8.2 -------------------------------------------------------------------------------

  @Test
  @Tag("AC-2.8.2")
  void ac_2_8_2_onlyTheRowPastItsDeadlineIsUnavailableAndItsLoadGoesOn() throws Exception {
    CountDownLatch release = new CountDownLatch(1);
    CountDownLatch finished = new CountDownLatch(1);
    AtomicBoolean interrupted = new AtomicBoolean();
    Supplier<AdapterOutcome> hanging =
        () -> {
          try {
            release.await(30, TimeUnit.SECONDS);
          } catch (InterruptedException e) {
            interrupted.set(true);
          } finally {
            finished.countDown();
          }
          return new AdapterOutcome.Found(ron(100));
        };
    LookupService service =
        service(
            TestRegistries.twoEnabled(50),
            Map.of("a.example", hanging, "b.example", found(ron(200))));

    LookupResult result;
    try {
      result = service.lookup(EAN);
    } finally {
      release.countDown();
    }

    assertThat(result.rows())
        .extracting(LookupRow::retailerId, LookupRow::status)
        .containsExactlyInAnyOrder(
            tuple("b.example", RowStatus.FOUND), tuple("a.example", RowStatus.UNAVAILABLE));
    assertThat(finished.await(10, TimeUnit.SECONDS)).isTrue();
    assertThat(interrupted).isFalse();
  }

  @Test
  @Tag("AC-2.8.2")
  void ac_2_8_2_defaultDeadlineIs4000() {
    assertThat(LookupService.DEFAULT_DEADLINE_MS).isEqualTo(4000);
  }

  // --- AC 2.8.3 -------------------------------------------------------------------------------

  @Test
  @Tag("AC-2.8.3")
  void ac_2_8_3_disabledComesFirstWithoutAdapterCall() {
    LookupResult result = serviceA(found(ron(100))).lookup(EAN);
    assertThat(result.rows())
        .extracting(LookupRow::retailerId, LookupRow::status)
        .contains(tuple("b.example", RowStatus.DISABLED));
    assertThat(calls).containsExactly("a.example");
  }

  @Test
  @Tag("AC-2.8.3")
  void ac_2_8_3_activeBlockIsUnavailableBeforeCacheAndWithoutAdapterCall() {
    LookupService service = serviceA(found(ron(100)));
    assertThat(rowA(service.lookup(EAN)).status()).isEqualTo(RowStatus.FOUND);

    blocks.record("a.example", BlockStore.Reason.HTTP_403);
    LookupRow row = rowA(service.lookup(EAN));

    assertThat(row.status()).isEqualTo(RowStatus.UNAVAILABLE);
    assertThat(row.priceMinor()).isNull();
    assertThat(calls).containsExactly("a.example");
  }

  @Test
  @Tag("AC-2.8.3")
  void ac_2_8_3_unknownEanIsNotFoundWithoutRequest() {
    LookupRow row = rowA(serviceA(found(ron(100))).lookup(Ean.parse("5901234123457")));
    assertThat(row.status()).isEqualTo(RowStatus.NOT_FOUND);
    assertThat(calls).isEmpty();
  }

  @Test
  @Tag("AC-2.8.3")
  void ac_2_8_3_curatedUrlIsPassedToTheAdapter() {
    serviceA(found(ron(100))).lookup(EAN);
    assertThat(urls).containsExactly(URI.create("https://a.example/p/1"));
  }

  // --- AC 2.8.4 -------------------------------------------------------------------------------

  @Test
  @Tag("AC-2.8.4")
  void ac_2_8_4_eanOnPageMismatchIsNotFound() {
    PageFacts other = new PageFacts(100L, "RON", Availability.IN_STOCK, null, "96385074");
    LookupRow row = rowA(serviceA(found(other)).lookup(EAN));
    assertThat(row.status()).isEqualTo(RowStatus.NOT_FOUND);
    assertThat(row.priceMinor()).isNull();
    assertThat(row.url()).isNull();
  }

  @Test
  @Tag("AC-2.8.4")
  void ac_2_8_4_gtin14WithLeadingZeroOrMissingEanOnPageStaysFound() {
    PageFacts padded = new PageFacts(100L, "RON", null, null, "0" + EAN.value());
    assertThat(rowA(serviceA(found(padded)).lookup(EAN)).status()).isEqualTo(RowStatus.FOUND);
    PageFacts none = new PageFacts(100L, "RON", null, null, null);
    assertThat(rowA(serviceA(found(none)).lookup(EAN)).status()).isEqualTo(RowStatus.FOUND);
  }

  @Test
  @Tag("AC-2.8.4")
  void ac_2_8_4_nonRonOrNullPriceIsFoundWithoutPrice() {
    PageFacts eur = new PageFacts(100L, "EUR", Availability.IN_STOCK, null, EAN.value());
    LookupRow eurRow = rowA(serviceA(found(eur)).lookup(EAN));
    assertThat(eurRow.status()).isEqualTo(RowStatus.FOUND);
    assertThat(eurRow.priceMinor()).isNull();
    assertThat(eurRow.availability()).isEqualTo(RowAvailability.IN_STOCK);

    PageFacts noPrice = new PageFacts(null, "RON", null, null, EAN.value());
    LookupRow nullRow = rowA(serviceA(found(noPrice)).lookup(EAN));
    assertThat(nullRow.status()).isEqualTo(RowStatus.FOUND);
    assertThat(nullRow.priceMinor()).isNull();

    PageFacts noCurrency = new PageFacts(100L, null, null, null, EAN.value());
    assertThat(rowA(serviceA(found(noCurrency)).lookup(EAN)).priceMinor()).isNull();
  }

  // --- AC 2.8.5 -------------------------------------------------------------------------------

  @Test
  @Tag("AC-2.8.5")
  void ac_2_8_5_foundWithinTtlIsServedFromCacheWithOriginalFetchedAt() {
    LookupService service = serviceA(found(ron(100)));
    assertThat(rowA(service.lookup(EAN)).fetchedAt()).isEqualTo(T0);

    clock.advance(Duration.ofMinutes(10));
    LookupRow cached = rowA(service.lookup(EAN));

    assertThat(cached.status()).isEqualTo(RowStatus.FOUND);
    assertThat(cached.priceMinor()).isEqualTo(100L);
    assertThat(cached.fetchedAt()).isEqualTo(T0);
    assertThat(calls).containsExactly("a.example");
  }

  @Test
  @Tag("AC-2.8.5")
  void ac_2_8_5_notFoundIsCachedToo() {
    LookupService service = serviceA(AdapterOutcome.NotFound::new);
    service.lookup(EAN);
    clock.advance(Duration.ofMinutes(1));
    assertThat(rowA(service.lookup(EAN)).status()).isEqualTo(RowStatus.NOT_FOUND);
    assertThat(calls).containsExactly("a.example");
  }

  @Test
  @Tag("AC-2.8.5")
  void ac_2_8_5_afterTtlTheAdapterIsCalledAgain() {
    LookupService service = serviceA(found(ron(100)));
    service.lookup(EAN);
    clock.advance(TTL.plusSeconds(1));
    LookupRow row = rowA(service.lookup(EAN));
    assertThat(calls).containsExactly("a.example", "a.example");
    assertThat(row.fetchedAt()).isEqualTo(T0.plus(TTL).plusSeconds(1));
  }

  @Test
  @Tag("AC-2.8.5")
  void ac_2_8_5_unavailableIsNotCached() {
    LookupService unavailable =
        serviceA(() -> new AdapterOutcome.Unavailable(UnavailableReason.HTTP_ERROR));
    assertThat(rowA(unavailable.lookup(EAN)).status()).isEqualTo(RowStatus.UNAVAILABLE);
    assertThat(rowA(unavailable.lookup(EAN)).status()).isEqualTo(RowStatus.UNAVAILABLE);
    assertThat(calls).hasSize(2);

    calls.clear();
    LookupService throwing =
        serviceA(() -> {
          throw new IllegalStateException("boom");
        });
    assertThat(rowA(throwing.lookup(EAN)).status()).isEqualTo(RowStatus.UNAVAILABLE);
    assertThat(rowA(throwing.lookup(EAN)).status()).isEqualTo(RowStatus.UNAVAILABLE);
    assertThat(calls).hasSize(2);
  }

  @Test
  @Tag("AC-2.8.5")
  void ac_2_8_5_concurrentLookupsShareOneLoad() throws Exception {
    CountDownLatch release = new CountDownLatch(1);
    Supplier<AdapterOutcome> slow =
        () -> {
          try {
            release.await(10, TimeUnit.SECONDS);
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
          }
          return new AdapterOutcome.Found(ron(100));
        };
    LookupService service = service(TestRegistries.twoEnabled(20_000), Map.of("a.example", slow));
    AtomicReference<LookupRow> second = new AtomicReference<>();
    Thread other = Thread.ofVirtual().start(() -> service.lookup(EAN));
    try {
      // The other Lookup's load is in flight once its adapter call is recorded.
      while (calls.isEmpty()) {
        Thread.onSpinWait();
      }
      Thread mine = Thread.ofVirtual().start(() -> second.set(rowA(service.lookup(EAN))));
      release.countDown();
      mine.join(10_000);
      other.join(10_000);
    } finally {
      release.countDown();
    }
    assertThat(second.get().status()).isEqualTo(RowStatus.FOUND);
    assertThat(calls.stream().filter("a.example"::equals)).hasSize(1);
  }

  // --- AC 2.8.6 -------------------------------------------------------------------------------

  @Test
  @Tag("AC-2.8.6")
  void ac_2_8_6_cacheEntryHoldsOnlyAllowedFacts() {
    assertThat(Arrays.stream(Verdict.class.getRecordComponents()).map(RecordComponent::getName))
        .containsExactly("status", "priceMinor", "availability", "url", "fetchedAt");
  }

  @Test
  @Tag("AC-2.8.6")
  @ExtendWith(OutputCaptureExtension.class)
  void ac_2_8_6_eachLookupLogsOneAllowlistedLinePerRetailer(CapturedOutput output) {
    serviceA(found(ron(100))).lookup(EAN);

    JsonMapper json = JsonMapper.builder().build();
    List<JsonNode> rows =
        output
            .getOut()
            .lines()
            .filter(l -> l.startsWith("{"))
            .map(json::readTree)
            .filter(n -> "lookup_row".equals(n.path("event").stringValue()))
            .toList();
    assertThat(rows)
        .extracting(n -> n.get("retailer").stringValue(), n -> n.get("outcome").stringValue())
        .containsExactly(tuple("a.example", "FOUND"), tuple("b.example", "DISABLED"));
    for (JsonNode row : rows) {
      assertThat(new ArrayList<>(row.propertyNames()))
          .containsExactly("ts", "level", "event", "ean", "retailer", "outcome", "durationMs");
      assertThat(LookupLogTest.ALLOWLIST).containsAll(row.propertyNames());
      assertThat(row.get("ean").stringValue()).isEqualTo(EAN.value());
    }
  }
}
