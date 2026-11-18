package ro.pricechecker.fetch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static ro.pricechecker.fetch.StubTransport.ok;
import static ro.pricechecker.fetch.StubTransport.status;
import static ro.pricechecker.fetch.StubTransport.tooMany;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ro.pricechecker.adapter.AdapterOutcome;
import ro.pricechecker.adapter.PageFacts;
import ro.pricechecker.adapter.RetailerAdapter;
import ro.pricechecker.adapter.RetailerHttp;
import ro.pricechecker.adapter.UnavailableReason;
import ro.pricechecker.cache.ResultCache;
import ro.pricechecker.ean.Ean;
import ro.pricechecker.lookup.AdapterCatalog;
import ro.pricechecker.lookup.LookupLog;
import ro.pricechecker.lookup.LookupRow;
import ro.pricechecker.lookup.LookupService;
import ro.pricechecker.lookup.RowStatus;
import ro.pricechecker.registry.BlockStore;
import ro.pricechecker.registry.MutableClock;
import ro.pricechecker.registry.Registry;
import ro.pricechecker.registry.RuntimeSwitch;
import ro.pricechecker.registry.TestRegistries;

/**
 * Story 2.7 gaps found by Eufrosina (QA): the deadline boundary, the 429 retry kept apart from the
 * 5xx retries, a past HTTP-date, a failed {@code blocks.json} write, an expired {@code
 * runtime.yaml} {@code notBefore}, and the Lookup row while a pause lasts (AC-11.4, AD-6 check 5).
 * Stub transport, injected clock and sleeper only (T-3, T-5).
 */
class RetryAfterAcceptanceGapsTest {

  private static final Instant START = Instant.parse("2026-11-18T10:00:00Z");
  private static final URI PAGE = URI.create("https://a.example/p/1");
  private static final UserAgent UA = UserAgent.build("1.0", "https://contact.example/about");
  private static final AdapterOutcome REFUSED =
      new AdapterOutcome.Unavailable(UnavailableReason.REFUSED);
  private static final int DEADLINE_MS = 4000;

  @TempDir Path dir;

  private final MutableClock clock = new MutableClock(START);
  private final RecordingSleeper sleeper = new RecordingSleeper(clock);
  private final StubTransport transport = new StubTransport();
  private final Registry registry = TestRegistries.fetchable(10, 1, DEADLINE_MS);
  private Path runtimeFile;
  private RuntimeSwitch runtime;

  @BeforeEach
  void setUp() throws IOException {
    runtimeFile = dir.resolve("runtime.yaml");
    Files.writeString(runtimeFile, "retailers:\n  a.example: {fetching: allowed}\n");
    runtime = new RuntimeSwitch(runtimeFile, clock);
    assertThat(runtime.refresh()).isEmpty();
  }

  private BlockStore store(Path blocksFile) {
    return new BlockStore(blocksFile, runtime, clock, new LookupLog(clock)::blocked);
  }

  private BlockStore store() {
    return store(dir.resolve("blocks.json"));
  }

  private FetchClient.BoundHttp boundA(BlockStore store) {
    return new FetchClient(
            registry, runtime, store, transport, clock, sleeper, UA, RobotsTest.allowAll(clock))
        .bind("a.example");
  }

  private static AdapterOutcome outcomeOf(FetchClient.BoundHttp http, URI url) {
    FetchFailure f = catchThrowableOfType(FetchFailure.class, () -> http.fetch(url));
    assertThat(f).as("expected a FetchFailure").isNotNull();
    return f.outcome();
  }

  private static String httpDate(Instant instant) {
    return DateTimeFormatter.RFC_1123_DATE_TIME.format(instant.atOffset(ZoneOffset.UTC));
  }

  // ---- AC-2.7.1 ----

  @Test
  @Tag("AC-2.7.1")
  void ac_2_7_1_pastHttpDateIsRetriedOnceWithoutWaiting() throws IOException {
    BlockStore store = store();
    transport.then(tooMany(httpDate(START.minusSeconds(30)))).then(ok("<html>page</html>"));

    assertThat(boundA(store).fetch(PAGE).body()).isEqualTo("<html>page</html>");

    assertThat(transport.requests).hasSize(2);
    // Only the retailer's 10 ms minimum interval, no Retry-After wait.
    assertThat(sleeper.sleeps).containsExactly(Duration.ofMillis(10));
    assertThat(store.notBefore("a.example")).contains(START.minusSeconds(30));
    assertThat(store.isBlocked("a.example")).isFalse();
  }

  @Test
  @Tag("AC-2.7.1")
  void ac_2_7_1_failedBlocksWriteStillKeepsNotBeforeInMemory() throws IOException {
    Path stateDir = dir.resolve("state");
    BlockStore store = store(stateDir.resolve("blocks.json"));
    // The state directory becomes a regular file, so every blocks.json write fails.
    Files.writeString(stateDir, "not a directory");
    assertThat(
            catchThrowableOfType(
                UncheckedIOException.class,
                () -> store.recordRetryAfter("other.example", START.plusSeconds(1))))
        .isNotNull();
    transport.then(tooMany("3600"));

    assertThat(outcomeOf(boundA(store), PAGE)).isEqualTo(REFUSED);
    assertThat(store.notBefore("a.example")).contains(START.plusSeconds(3600));
    assertThat(store.isBlocked("a.example")).isFalse();

    clock.advance(Duration.ofMinutes(10));
    assertThat(outcomeOf(boundA(store), PAGE)).isEqualTo(REFUSED);
    assertThat(transport.requests).hasSize(1);
  }

  // ---- AC-2.7.2 ----

  @Test
  @Tag("AC-2.7.2")
  void ac_2_7_2_notBeforeExactlyAtTheDeadlineIsUnavailable() {
    BlockStore store = store();
    transport.then(tooMany(String.valueOf(DEADLINE_MS / 1000))).always(ok("<html>page</html>"));

    assertThat(outcomeOf(boundA(store), PAGE)).isEqualTo(REFUSED);

    assertThat(transport.requests).hasSize(1);
    assertThat(sleeper.sleeps).isEmpty();
  }

  @Test
  @Tag("AC-2.7.2")
  void ac_2_7_2_notBeforeJustInsideTheDeadlineWaitsAndRetries() throws IOException {
    BlockStore store = store();
    transport.then(tooMany(String.valueOf(DEADLINE_MS / 1000 - 1))).then(ok("<html>page</html>"));

    assertThat(boundA(store).fetch(PAGE).body()).isEqualTo("<html>page</html>");

    assertThat(transport.requests).hasSize(2);
    assertThat(sleeper.sleeps).containsExactly(Duration.ofSeconds(DEADLINE_MS / 1000 - 1));
  }

  @Test
  @Tag("AC-2.7.2")
  void ac_2_7_2_theTooManyRetryDoesNotUseUpThe5xxRetries() throws IOException {
    BlockStore store = store();
    transport.then(tooMany("1")).then(status(503)).then(status(503)).then(ok("<html>page</html>"));

    assertThat(boundA(store).fetch(PAGE).body()).isEqualTo("<html>page</html>");

    assertThat(transport.requests).hasSize(4);
    assertThat(sleeper.sleeps)
        .containsExactly(Duration.ofSeconds(1), Duration.ofMillis(250), Duration.ofMillis(500));
  }

  @Test
  @Tag("AC-2.7.2")
  void ac_2_7_2_aTooManyAfterA5xxRetryIsStillRetriedOnlyOnce() {
    BlockStore store = store();
    transport
        .then(status(503))
        .then(tooMany("1"))
        .then(tooMany("1"))
        .always(ok("<html>page</html>"));

    assertThat(outcomeOf(boundA(store), PAGE)).isEqualTo(REFUSED);

    assertThat(transport.requests).hasSize(3);
    assertThat(sleeper.sleeps).containsExactly(Duration.ofMillis(250), Duration.ofSeconds(1));
  }

  // ---- AC-2.7.3 ----

  @Test
  @Tag("AC-2.7.3")
  void ac_2_7_3_expiredRuntimeYamlNotBeforeLetsTheRequestThrough() throws IOException {
    BlockStore store = store();
    Files.writeString(
        runtimeFile,
        "retailers:\n  a.example: {fetching: allowed, notBefore: 2026-11-18T09:00:00Z}\n");
    clock.advance(Duration.ofSeconds(15));
    assertThat(runtime.refresh()).isEmpty();
    assertThat(store.notBefore("a.example")).contains(Instant.parse("2026-11-18T09:00:00Z"));

    assertThat(boundA(store).fetch(PAGE).body()).isEqualTo("<html>page</html>");
    assertThat(transport.requests).hasSize(1);
  }

  @Test
  @Tag("AC-2.7.3")
  void ac_2_7_3_lookupRowIsUnavailableDuringThePauseAndNotReusedAfterIt() {
    BlockStore store = store();
    List<String> adapterCalls = new ArrayList<>();
    FetchClient.BoundHttp boundA = boundA(store);
    LookupService service =
        new LookupService(
            registry,
            new AdapterCatalog(
                List.of(
                    adapter("a.example", adapterCalls),
                    adapter("b.example", adapterCalls),
                    adapter("c.example", adapterCalls)),
                registry),
            Optional.of(id -> boundA),
            clock,
            runtime,
            store,
            new ResultCache<>(Duration.ofMinutes(15), clock),
            new LookupLog(clock));
    Ean ean = Ean.parse(TestRegistries.EAN_A);
    transport.then(tooMany("3600"));

    assertThat(rowA(service, ean).status()).isEqualTo(RowStatus.UNAVAILABLE);
    assertThat(transport.requests).hasSize(1);

    // A second Lookup inside the pause sends nothing and gives no facts.
    clock.advance(Duration.ofMinutes(30));
    LookupRow during = rowA(service, ean);
    assertThat(during.status()).isEqualTo(RowStatus.UNAVAILABLE);
    assertThat(during.priceMinor()).isNull();
    assertThat(during.fetchedAt()).isNull();
    assertThat(transport.requests).hasSize(1);

    // Once the pause has passed the retailer is asked again: the UNAVAILABLE row was not kept.
    clock.advance(Duration.ofMinutes(31));
    transport.always(ok("<html>page</html>"));
    assertThat(rowA(service, ean).status()).isEqualTo(RowStatus.FOUND);
    assertThat(transport.requests).extracting(StubTransport.Request::url).endsWith(PAGE);
    assertThat(transport.requests).hasSize(2);
    assertThat(adapterCalls).containsOnly("a.example").hasSize(3);
  }

  private static LookupRow rowA(LookupService service, Ean ean) {
    return service.lookup(ean).rows().stream()
        .filter(r -> r.retailerId().equals("a.example"))
        .findFirst()
        .orElseThrow();
  }

  /** An adapter that fetches the product URL and maps a fetch failure to its outcome. */
  private static RetailerAdapter adapter(String id, List<String> calls) {
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
        } catch (FetchFailure f) {
          return f.outcome();
        } catch (IOException e) {
          throw new UncheckedIOException(e);
        }
        return new AdapterOutcome.Found(new PageFacts(100L, "RON", null, null, null));
      }
    };
  }
}
