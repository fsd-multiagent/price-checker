package ro.pricechecker.fetch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static ro.pricechecker.fetch.StubTransport.ok;
import static ro.pricechecker.fetch.StubTransport.tooMany;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import ro.pricechecker.adapter.AdapterOutcome;
import ro.pricechecker.adapter.UnavailableReason;
import ro.pricechecker.lookup.LookupLog;
import ro.pricechecker.registry.BlockStore;
import ro.pricechecker.registry.MutableClock;
import ro.pricechecker.registry.RuntimeSwitch;
import ro.pricechecker.registry.TestRegistries;
import tools.jackson.databind.json.JsonMapper;

/**
 * Story 2.7: a 429 records {@code notBefore} (never a Block), is retried once only when {@code
 * notBefore} is inside the deadline, and while it is in the future nothing is sent (AD-6, AC-11.4,
 * S-5, S-6). Stub transport, injected clock and sleeper only (T-3, T-5).
 */
@ExtendWith(OutputCaptureExtension.class)
class RetryAfterHandlingTest {

  private static final Instant START = Instant.parse("2026-11-18T10:00:00Z");
  private static final URI PAGE = URI.create("https://a.example/p/1");
  private static final URI ROBOTS = URI.create("https://a.example/robots.txt");
  private static final UserAgent UA = UserAgent.build("1.0", "https://contact.example/about");
  private static final AdapterOutcome REFUSED =
      new AdapterOutcome.Unavailable(UnavailableReason.REFUSED);

  @TempDir Path dir;

  private final MutableClock clock = new MutableClock(START);
  private final RecordingSleeper sleeper = new RecordingSleeper(clock);
  private final StubTransport transport = new StubTransport();
  private Path runtimeFile;
  private Path blocksFile;
  private RuntimeSwitch runtime;

  @BeforeEach
  void setUp() throws IOException {
    runtimeFile = dir.resolve("runtime.yaml");
    blocksFile = dir.resolve("blocks.json");
    Files.writeString(runtimeFile, "retailers:\n  a.example: {fetching: allowed}\n");
    runtime = new RuntimeSwitch(runtimeFile, clock);
    assertThat(runtime.refresh()).isEmpty();
  }

  private BlockStore store() {
    return new BlockStore(blocksFile, runtime, clock, new LookupLog(clock)::blocked);
  }

  /** A fresh client (seeded allow-all robots.txt) over the given state; deadline 4 s. */
  private FetchClient.BoundHttp boundA(BlockStore store) {
    return boundA(store, RobotsTest.allowAll(clock));
  }

  private FetchClient.BoundHttp boundA(BlockStore store, RobotsCache robots) {
    return new FetchClient(
            TestRegistries.fetchable(10, 1, 4000),
            runtime,
            store,
            transport,
            clock,
            sleeper,
            UA,
            robots)
        .bind("a.example");
  }

  private static AdapterOutcome outcomeOf(FetchClient.BoundHttp http, URI url) {
    FetchFailure f = catchThrowableOfType(FetchFailure.class, () -> http.fetch(url));
    assertThat(f).as("expected a FetchFailure").isNotNull();
    return f.outcome();
  }

  private String notBeforeInFile() throws IOException {
    return JsonMapper.builder()
        .build()
        .readTree(Files.readString(blocksFile))
        .get("retailers")
        .get("a.example")
        .get("notBefore")
        .stringValue();
  }

  private static boolean hasWarnLine(CapturedOutput output) {
    return output.getOut().lines().anyMatch(l -> l.startsWith("{") && l.contains("\"WARN\""));
  }

  // ---- AC-2.7.1 / AC-2.7.2 ----

  @Test
  @Tag("AC-2.7.2")
  void ac_2_7_2_deltaInsideDeadlineWaitsAndRetriesOnce(CapturedOutput output) throws IOException {
    BlockStore store = store();
    transport.then(tooMany("1")).then(ok("<html>page</html>"));

    FetchedPage page = boundA(store).fetch(PAGE);

    assertThat(page.body()).isEqualTo("<html>page</html>");
    assertThat(transport.requests).hasSize(2);
    assertThat(transport.requests.get(1))
        .extracting(r -> r.url(), r -> r.userAgent())
        .containsExactly(PAGE, UA.value());
    assertThat(transport.requests.get(0).userAgent()).isEqualTo(UA.value());
    assertThat(sleeper.sleeps).containsExactly(Duration.ofSeconds(1));
    assertThat(notBeforeInFile()).isEqualTo("2026-11-18T10:00:01Z");
    assertThat(store.isBlocked("a.example")).isFalse();
    assertThat(hasWarnLine(output)).isFalse();
  }

  @Test
  @Tag("AC-2.7.1")
  void ac_2_7_1_httpDateIsTheNotBefore() throws IOException {
    BlockStore store = store();
    String date =
        DateTimeFormatter.RFC_1123_DATE_TIME.format(START.plusSeconds(2).atOffset(ZoneOffset.UTC));
    transport.then(tooMany(date)).then(ok("<html>page</html>"));

    boundA(store).fetch(PAGE);

    assertThat(notBeforeInFile()).isEqualTo("2026-11-18T10:00:02Z");
    assertThat(sleeper.sleeps).containsExactly(Duration.ofSeconds(2));
    assertThat(transport.requests).hasSize(2);
  }

  @Test
  @Tag("AC-2.7.2")
  void ac_2_7_2_missingRetryAfterIsOutsideTheDeadline(CapturedOutput output) throws IOException {
    BlockStore store = store();
    transport.always(tooMany(null));

    assertThat(outcomeOf(boundA(store), PAGE)).isEqualTo(REFUSED);

    assertThat(transport.requests).hasSize(1);
    assertThat(sleeper.sleeps).isEmpty();
    assertThat(notBeforeInFile()).isEqualTo("2026-11-18T10:01:00Z");
    assertThat(store.isBlocked("a.example")).isFalse();
    assertThat(hasWarnLine(output)).isFalse();
  }

  @Test
  @Tag("AC-2.7.2")
  void ac_2_7_2_garbageRetryAfterIsSixtySeconds() throws IOException {
    BlockStore store = store();
    transport.always(tooMany("soon"));

    assertThat(outcomeOf(boundA(store), PAGE)).isEqualTo(REFUSED);

    assertThat(transport.requests).hasSize(1);
    assertThat(notBeforeInFile()).isEqualTo("2026-11-18T10:01:00Z");
  }

  @Test
  @Tag("AC-2.7.2")
  void ac_2_7_2_secondTooManyIsNotRetried() throws IOException {
    BlockStore store = store();
    transport.then(tooMany("1")).then(tooMany("2")).always(ok("<html>page</html>"));

    assertThat(outcomeOf(boundA(store), PAGE)).isEqualTo(REFUSED);

    assertThat(transport.requests).hasSize(2);
    assertThat(sleeper.sleeps).containsExactly(Duration.ofSeconds(1));
    assertThat(notBeforeInFile()).isEqualTo("2026-11-18T10:00:03Z");
  }

  @Test
  @Tag("AC-2.7.1")
  void ac_2_7_1_aLaterTooManyNeverMovesNotBeforeEarlier() throws IOException {
    BlockStore store = store();
    // A concurrent 429 stores a later notBefore while this request is in flight.
    transport.then(url -> {
      store.recordRetryAfter("a.example", START.plusSeconds(600));
      return tooMany("1");
    });

    // Its own notBefore (now + 1 s) is inside the deadline, but the stored later one is kept, so
    // the retry is refused without sending.
    assertThat(outcomeOf(boundA(store), PAGE)).isEqualTo(REFUSED);
    assertThat(store.notBefore("a.example")).contains(START.plusSeconds(600));
    assertThat(notBeforeInFile()).isEqualTo("2026-11-18T10:10:00Z");
    assertThat(transport.requests).hasSize(1);
  }

  // ---- AC-2.7.3 ----

  @Test
  @Tag("AC-2.7.3")
  void ac_2_7_3_laterFetchInsidePauseSendsNothing() throws IOException {
    BlockStore store = store();
    transport.then(tooMany("3600"));
    assertThat(outcomeOf(boundA(store), PAGE)).isEqualTo(REFUSED);
    assertThat(transport.requests).hasSize(1);

    clock.advance(Duration.ofMinutes(30));
    // A fresh robots.txt cache too: robots.txt is not fetched either.
    assertThat(outcomeOf(boundA(store, new RobotsCache(clock)), PAGE)).isEqualTo(REFUSED);
    assertThat(transport.requests).hasSize(1);
    assertThat(store.isBlocked("a.example")).isFalse();

    clock.advance(Duration.ofMinutes(30));
    transport.always(ok("<html>page</html>"));
    assertThat(boundA(store).fetch(PAGE).body()).isEqualTo("<html>page</html>");
    assertThat(transport.requests).hasSize(2);
  }

  @Test
  @Tag("AC-2.7.3")
  void ac_2_7_3_restartStillHonorsNotBefore() {
    transport.then(tooMany("3600"));
    assertThat(outcomeOf(boundA(store()), PAGE)).isEqualTo(REFUSED);

    BlockStore restarted = store();
    assertThat(outcomeOf(boundA(restarted, new RobotsCache(clock)), PAGE)).isEqualTo(REFUSED);
    assertThat(transport.requests).hasSize(1);
  }

  @Test
  @Tag("AC-2.7.3")
  void ac_2_7_3_runnerNotBeforeInRuntimeYamlSendsNothing() throws IOException {
    BlockStore store = store();
    Files.writeString(
        runtimeFile,
        "retailers:\n  a.example: {fetching: allowed, notBefore: 2026-11-18T11:00:00Z}\n");
    clock.advance(Duration.ofSeconds(15));
    assertThat(runtime.refresh()).isEmpty();

    assertThat(outcomeOf(boundA(store, new RobotsCache(clock)), PAGE)).isEqualTo(REFUSED);
    assertThat(transport.requests).isEmpty();
    assertThat(store.isBlocked("a.example")).isFalse();

    // A bad value rejects the whole file; the last good settings (with notBefore) stay.
    Files.writeString(runtimeFile, "retailers:\n  a.example: {fetching: allowed, notBefore: x}\n");
    clock.advance(Duration.ofSeconds(15));
    assertThat(runtime.refresh()).isPresent();
    assertThat(outcomeOf(boundA(store, new RobotsCache(clock)), PAGE)).isEqualTo(REFUSED);
    assertThat(transport.requests).isEmpty();
  }

  @Test
  @Tag("AC-2.7.1")
  void ac_2_7_1_robotsTooManyRecordsNotBeforeAndIsNotRetried() throws IOException {
    BlockStore store = store();
    transport.then(tooMany("1"));

    assertThat(outcomeOf(boundA(store, new RobotsCache(clock)), PAGE)).isEqualTo(REFUSED);

    assertThat(transport.requests).extracting(r -> r.url()).containsExactly(ROBOTS);
    assertThat(sleeper.sleeps).isEmpty();
    assertThat(notBeforeInFile()).isEqualTo("2026-11-18T10:00:01Z");
    assertThat(store.isBlocked("a.example")).isFalse();
  }

  @Test
  @Tag("AC-2.7.1")
  void ac_2_7_1_hugeDeltaIsCapped() throws IOException {
    BlockStore store = store();
    transport.always(tooMany("99999999999999999999"));

    assertThat(outcomeOf(boundA(store), PAGE)).isEqualTo(REFUSED);

    assertThat(store.notBefore("a.example")).contains(START.plus(Duration.ofDays(3650)));
    assertThat(transport.requests).hasSize(1);
  }
}
