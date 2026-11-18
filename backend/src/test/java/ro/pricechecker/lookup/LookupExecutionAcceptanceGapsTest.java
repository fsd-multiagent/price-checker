package ro.pricechecker.lookup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.groups.Tuple.tuple;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import ro.pricechecker.adapter.AdapterOutcome;
import ro.pricechecker.adapter.Availability;
import ro.pricechecker.adapter.PageFacts;
import ro.pricechecker.adapter.RetailerAdapter;
import ro.pricechecker.adapter.RetailerHttp;
import ro.pricechecker.cache.ResultCache;
import ro.pricechecker.ean.Ean;
import ro.pricechecker.registry.BlockStore;
import ro.pricechecker.registry.MutableClock;
import ro.pricechecker.registry.Registry;
import ro.pricechecker.registry.RuntimeSwitch;
import ro.pricechecker.registry.TestRegistries;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Story 2.8 gaps found by Eufrosina (QA) (T-1, T-5): AD-10 precedence when several checks hold at
 * once, the deadline measured from the Lookup start on the injected clock, a load past its deadline
 * that still fills the cache, the cache key per EAN, a cache hit that a later stop or Block must
 * not bypass, and one log line per retailer on a cached Lookup too.
 *
 * <p>The few time bounds below are ceilings far above the expected (near zero) duration, never
 * sleeps; they fail only when the code waits for a deadline it should not wait for.
 */
class LookupExecutionAcceptanceGapsTest {

  private static final Instant T0 = Instant.parse("2026-11-18T10:15:30Z");
  private static final Duration TTL = Duration.ofMinutes(15);

  /** Longer than the Kill switch re-read interval (15 s), so the next Lookup reads the file. */
  private static final Duration PAST_REFRESH = Duration.ofSeconds(16);

  private static final Ean EAN = Ean.parse(TestRegistries.EAN_A);
  private static final Ean OTHER_EAN = Ean.parse(TestRegistries.EAN_B);
  private static final String BOTH_ALLOWED =
      "retailers:\n  a.example: {fetching: allowed}\n  b.example: {fetching: allowed}\n";
  private static final String A_STOPPED =
      "retailers:\n  a.example: {fetching: stopped}\n  b.example: {fetching: allowed}\n";

  @TempDir Path dir;

  private final MutableClock clock = new MutableClock(T0);
  private final List<String> calls = Collections.synchronizedList(new ArrayList<>());
  private final List<URI> requests = Collections.synchronizedList(new ArrayList<>());
  private RuntimeSwitch runtime;
  private BlockStore blocks;

  private void writeRuntime(String yaml) throws IOException {
    Files.writeString(dir.resolve("runtime.yaml"), yaml);
  }

  private RetailerAdapter adapter(String id, Supplier<AdapterOutcome> outcome) {
    return new RetailerAdapter() {
      @Override
      public String retailerId() {
        return id;
      }

      @Override
      public AdapterOutcome fetchAndExtract(Ean ean, URI productUrl, RetailerHttp http) {
        calls.add(id);
        try {
          http.get(productUrl);
        } catch (IOException e) {
          throw new java.io.UncheckedIOException(e);
        }
        return outcome.get();
      }
    };
  }

  private LookupService service(Registry registry, Map<String, Supplier<AdapterOutcome>> outcomes)
      throws IOException {
    if (!Files.exists(dir.resolve("runtime.yaml"))) {
      writeRuntime(BOTH_ALLOWED);
    }
    runtime = new RuntimeSwitch(dir.resolve("runtime.yaml"), clock);
    blocks = TestRegistries.blockStore(runtime, clock);
    List<RetailerAdapter> list = new ArrayList<>();
    for (String id : List.of("a.example", "b.example", "c.example")) {
      list.add(adapter(id, outcomes.getOrDefault(id, AdapterOutcome.NotFound::new)));
    }
    RetailerHttp http =
        url -> {
          requests.add(url);
          return "";
        };
    return new LookupService(
        registry,
        new AdapterCatalog(list, registry),
        Optional.of(http),
        clock,
        runtime,
        blocks,
        new ResultCache<>(TTL, clock),
        new LookupLog(clock));
  }

  private static Supplier<AdapterOutcome> foundRon(long price) {
    return () ->
        new AdapterOutcome.Found(
            new PageFacts(price, "RON", Availability.IN_STOCK, null, TestRegistries.EAN_A));
  }

  private static LookupRow row(LookupResult result, String id) {
    return result.rows().stream().filter(r -> r.retailerId().equals(id)).findFirst().orElseThrow();
  }

  // --- AC 2.8.2 -------------------------------------------------------------------------------

  @Test
  @Tag("AC-2.8.2")
  void ac_2_8_2_deadlineCountsFromLookupStartSoALateRowDoesNotAddItsOwnFullWait() throws Exception {
    // a.example answers at once but its call takes 3 s on the injected clock; b.example hangs.
    // Both have deadlineMs 4000, so when the Lookup waits for b.example only 1 s of its deadline
    // is left: it must be UNAVAILABLE after that 1 s, not after another full 4 s (ceiling 3 s).
    // The advance stays below the deadline so a.example's own wait never reaches 0 between the
    // clock advance and the load completing (that race made the r1 tests gate flaky).
    CountDownLatch release = new CountDownLatch(1);
    Supplier<AdapterOutcome> slowOnTheClock =
        () -> {
          clock.advance(Duration.ofMillis(3000));
          return foundRon(100).get();
        };
    Supplier<AdapterOutcome> hanging =
        () -> {
          try {
            release.await(30, TimeUnit.SECONDS);
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
          }
          return foundRon(200).get();
        };
    LookupService service =
        service(
            TestRegistries.twoEnabled(4000),
            Map.of("a.example", slowOnTheClock, "b.example", hanging));

    try {
      LookupResult result =
          assertTimeoutPreemptively(Duration.ofSeconds(3), () -> service.lookup(EAN));
      assertThat(row(result, "a.example").status()).isEqualTo(RowStatus.FOUND);
      assertThat(row(result, "b.example").status()).isEqualTo(RowStatus.UNAVAILABLE);
    } finally {
      release.countDown();
    }
  }

  @Test
  @Tag("AC-2.8.2")
  @Tag("AC-2.8.5")
  void ac_2_8_2_loadPastItsDeadlineGoesOnAndItsVerdictServesTheNextLookup() throws Exception {
    CountDownLatch release = new CountDownLatch(1);
    CountDownLatch returned = new CountDownLatch(1);
    Supplier<AdapterOutcome> late =
        () -> {
          try {
            release.await(30, TimeUnit.SECONDS);
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
          }
          returned.countDown();
          return foundRon(100).get();
        };
    LookupService service = service(TestRegistries.twoEnabled(200), Map.of("a.example", late));

    LookupRow first;
    try {
      first = row(service.lookup(EAN), "a.example");
    } finally {
      release.countDown();
    }
    assertThat(first.status()).isEqualTo(RowStatus.UNAVAILABLE);
    assertThat(returned.await(10, TimeUnit.SECONDS)).isTrue();

    LookupRow second = row(service.lookup(EAN), "a.example");

    assertThat(second.status()).isEqualTo(RowStatus.FOUND);
    assertThat(second.priceMinor()).isEqualTo(100L);
    assertThat(calls.stream().filter("a.example"::equals)).hasSize(1);
  }

  // --- AC 2.8.3 -------------------------------------------------------------------------------

  @Test
  @Tag("AC-2.8.3")
  void ac_2_8_3_stoppedRetailerIsDisabledEvenWhenBlockedAndTheEanIsUnknown() throws IOException {
    writeRuntime(A_STOPPED);
    LookupService service = service(TestRegistries.twoEnabled(4000), Map.of());
    blocks.record("a.example", BlockStore.Reason.HTTP_403);

    LookupResult known = service.lookup(EAN);
    LookupResult unknown = service.lookup(Ean.parse("5901234123457"));

    assertThat(row(known, "a.example").status()).isEqualTo(RowStatus.DISABLED);
    assertThat(row(unknown, "a.example").status()).isEqualTo(RowStatus.DISABLED);
    assertThat(calls).doesNotContain("a.example");
  }

  @Test
  @Tag("AC-2.8.3")
  void ac_2_8_3_activeBlockComesBeforeTheUnknownEanCheck() throws IOException {
    LookupService service = service(TestRegistries.twoEnabled(4000), Map.of());
    blocks.record("a.example", BlockStore.Reason.CHALLENGE);

    LookupResult result = service.lookup(Ean.parse("5901234123457"));

    assertThat(result.rows())
        .extracting(LookupRow::retailerId, LookupRow::status)
        .containsExactlyInAnyOrder(
            tuple("a.example", RowStatus.UNAVAILABLE), tuple("b.example", RowStatus.NOT_FOUND));
    assertThat(calls).isEmpty();
    assertThat(requests).isEmpty();
  }

  @Test
  @Tag("AC-2.8.3")
  @Tag("AC-2.8.5")
  void ac_2_8_3_stopAfterACachedVerdictIsDisabledNotTheCachedRow() throws IOException {
    LookupService service =
        service(TestRegistries.twoEnabled(4000), Map.of("a.example", foundRon(100)));
    assertThat(row(service.lookup(EAN), "a.example").status()).isEqualTo(RowStatus.FOUND);

    writeRuntime(A_STOPPED);
    clock.advance(PAST_REFRESH);
    LookupRow row = row(service.lookup(EAN), "a.example");

    assertThat(row.status()).isEqualTo(RowStatus.DISABLED);
    assertThat(row.priceMinor()).isNull();
  }

  @Test
  @Tag("AC-2.8.3")
  void ac_2_8_3_eachRetailerGetsItsOwnCuratedUrlForTheRequestedEan() throws IOException {
    LookupService service = service(TestRegistries.twoEnabled(4000), Map.of());

    service.lookup(OTHER_EAN);

    assertThat(requests)
        .containsExactlyInAnyOrder(
            URI.create("https://a.example/p/2"), URI.create("https://b.example/p/2"));
  }

  // --- AC 2.8.4 -------------------------------------------------------------------------------

  @Test
  @Tag("AC-2.8.4")
  void ac_2_8_4_mismatchedEanOnPageIsCachedAsNotFound() throws IOException {
    Supplier<AdapterOutcome> otherProduct =
        () ->
            new AdapterOutcome.Found(
                new PageFacts(100L, "RON", Availability.IN_STOCK, null, TestRegistries.EAN_B));
    LookupService service =
        service(TestRegistries.twoEnabled(4000), Map.of("a.example", otherProduct));

    service.lookup(EAN);
    clock.advance(Duration.ofMinutes(1));
    LookupRow row = row(service.lookup(EAN), "a.example");

    assertThat(row.status()).isEqualTo(RowStatus.NOT_FOUND);
    assertThat(row.priceMinor()).isNull();
    assertThat(calls.stream().filter("a.example"::equals)).hasSize(1);
  }

  // --- AC 2.8.5 -------------------------------------------------------------------------------

  @Test
  @Tag("AC-2.8.5")
  void ac_2_8_5_cacheIsPerEanSoAnotherEanFetchesAgain() throws IOException {
    LookupService service =
        service(TestRegistries.twoEnabled(4000), Map.of("a.example", foundRon(100)));

    service.lookup(EAN);
    service.lookup(OTHER_EAN);

    assertThat(calls.stream().filter("a.example"::equals)).hasSize(2);
    assertThat(requests)
        .filteredOn(u -> u.getHost().equals("a.example"))
        .containsExactly(URI.create("https://a.example/p/1"), URI.create("https://a.example/p/2"));
  }

  @Test
  @Tag("AC-2.8.5")
  void ac_2_8_5_cachedLookupDoesNotWaitForAnAdapterAndRendersAtOnce() throws IOException {
    // After the first Lookup every adapter would hang for 30 s; the cached Lookup must not call
    // them at all, so it returns within the 1 s of AC-N1.2.
    CountDownLatch never = new CountDownLatch(1);
    List<Boolean> hangNow = Collections.synchronizedList(new ArrayList<>());
    Supplier<AdapterOutcome> firstFastThenHang =
        () -> {
          if (!hangNow.isEmpty()) {
            try {
              never.await(30, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
              Thread.currentThread().interrupt();
            }
          }
          return foundRon(100).get();
        };
    LookupService service =
        service(
            TestRegistries.twoEnabled(4000),
            Map.of("a.example", firstFastThenHang, "b.example", firstFastThenHang));
    service.lookup(EAN);
    hangNow.add(true);
    clock.advance(TTL.minusSeconds(1));

    try {
      LookupResult cached =
          assertTimeoutPreemptively(Duration.ofSeconds(1), () -> service.lookup(EAN));
      assertThat(cached.rows())
          .extracting(LookupRow::retailerId, LookupRow::status, LookupRow::fetchedAt)
          .containsExactlyInAnyOrder(
              tuple("a.example", RowStatus.FOUND, T0), tuple("b.example", RowStatus.FOUND, T0));
    } finally {
      never.countDown();
    }
    assertThat(calls).hasSize(2);
  }

  @Test
  @Tag("AC-2.8.5")
  void ac_2_8_5_entryIsStillServedJustBeforeTheTtlEnds() throws IOException {
    LookupService service =
        service(TestRegistries.twoEnabled(4000), Map.of("a.example", foundRon(100)));
    service.lookup(EAN);

    clock.advance(TTL.minusMillis(1));
    service.lookup(EAN);

    assertThat(calls.stream().filter("a.example"::equals)).hasSize(1);
  }

  // --- AC 2.8.6 -------------------------------------------------------------------------------

  @Test
  @Tag("AC-2.8.6")
  @ExtendWith(OutputCaptureExtension.class)
  void ac_2_8_6_cachedLookupStillLogsOneAllowlistedLinePerRetailer(CapturedOutput output)
      throws IOException {
    LookupService service =
        service(TestRegistries.twoEnabled(4000), Map.of("a.example", foundRon(100)));

    service.lookup(EAN);
    service.lookup(EAN);

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
        .containsExactlyInAnyOrder(
            tuple("a.example", "FOUND"),
            tuple("b.example", "NOT_FOUND"),
            tuple("a.example", "FOUND"),
            tuple("b.example", "NOT_FOUND"));
    for (JsonNode row : rows) {
      assertThat(LookupLogTest.ALLOWLIST).containsAll(row.propertyNames());
      assertThat(row.toString()).doesNotContain("a.example/p/", "RON", "IN_STOCK");
    }
  }
}
