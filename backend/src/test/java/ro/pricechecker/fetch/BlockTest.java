package ro.pricechecker.fetch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static ro.pricechecker.fetch.StubTransport.ok;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import ro.pricechecker.adapter.AdapterOutcome;
import ro.pricechecker.adapter.UnavailableReason;
import ro.pricechecker.lookup.LookupLog;
import ro.pricechecker.registry.BlockStore;
import ro.pricechecker.registry.MutableClock;
import ro.pricechecker.registry.RuntimeSwitch;
import ro.pricechecker.registry.TestRegistries;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Story 2.6: a 403, a challenge page or a robots.txt disallow is a Block: recorded in {@code
 * blocks.json}, logged at WARN, never retried; while it is active nothing is sent (FR-11, S-6,
 * AD-6, AD-9). Stub transport, injected clock and sleeper only (T-3, T-5).
 */
@ExtendWith(OutputCaptureExtension.class)
class BlockTest {

  private static final Instant START = Instant.parse("2026-11-18T10:00:00Z");
  private static final URI ROBOTS = URI.create("https://a.example/robots.txt");
  private static final URI PAGE = URI.create("https://a.example/p/1");
  private static final URI SEARCH = URI.create("https://a.example/cauta?q=1");
  private static final UserAgent UA = UserAgent.build("1.0", "https://contact.example/about");

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
    writeClearGeneration(0);
    runtime = new RuntimeSwitch(runtimeFile, clock);
    assertThat(runtime.refresh()).isEmpty();
  }

  private void writeClearGeneration(long generation) throws IOException {
    Files.writeString(
        runtimeFile,
        "retailers:\n  a.example: {fetching: allowed, clearGeneration: " + generation + "}\n");
  }

  private void poll() {
    clock.advance(Duration.ofSeconds(15));
    assertThat(runtime.refresh()).isEmpty();
  }

  private BlockStore store() {
    return new BlockStore(blocksFile, runtime, clock, new LookupLog(clock)::blocked);
  }

  /** A fresh client (empty robots.txt cache) over the same Block state. */
  private FetchClient.BoundHttp boundA(BlockStore store) {
    return new FetchClient(
            TestRegistries.fetchable(10, 1, 4000),
            runtime,
            store,
            transport,
            clock,
            sleeper,
            UA,
            RobotsTest.allowAll(clock))
        .bind("a.example");
  }

  private static AdapterOutcome outcomeOf(FetchClient.BoundHttp http, URI url) {
    FetchFailure f = catchThrowableOfType(FetchFailure.class, () -> http.fetch(url));
    assertThat(f).as("expected a FetchFailure").isNotNull();
    return f.outcome();
  }

  private static final AdapterOutcome BLOCKED =
      new AdapterOutcome.Unavailable(UnavailableReason.BLOCKED);

  private String reasonInFile() throws IOException {
    JsonNode root = JsonMapper.builder().build().readTree(Files.readString(blocksFile));
    return root.get("retailers").get("a.example").get("reason").stringValue();
  }

  private static List<JsonNode> warnLines(CapturedOutput output) {
    JsonMapper json = JsonMapper.builder().build();
    return output
        .getOut()
        .lines()
        .filter(l -> l.startsWith("{"))
        .map(json::readTree)
        .filter(n -> "WARN".equals(n.path("level").stringValue()))
        .toList();
  }

  private static void assertOneWarnLine(CapturedOutput output, String reason) {
    List<JsonNode> warns = warnLines(output);
    assertThat(warns).hasSize(1);
    assertThat(warns.get(0).get("event").stringValue()).isEqualTo("retailer_blocked");
    assertThat(warns.get(0).get("retailer").stringValue()).isEqualTo("a.example");
    assertThat(warns.get(0).get("outcome").stringValue()).isEqualTo(reason);
  }

  // ---- AC-2.6.1: each Block cause is recorded, logged, sent once ----

  @Test
  @Tag("AC-2.6.1")
  void ac_2_6_1_http403IsABlockSentOnce(CapturedOutput output) throws IOException {
    BlockStore store = store();
    transport.always(StubTransport.status(403));

    assertThat(outcomeOf(boundA(store), PAGE)).isEqualTo(BLOCKED);

    assertThat(transport.requests).hasSize(1);
    assertThat(sleeper.sleeps).isEmpty();
    assertThat(reasonInFile()).isEqualTo("HTTP_403");
    assertThat(store.isBlocked("a.example")).isTrue();
    assertOneWarnLine(output, "HTTP_403");
  }

  @ParameterizedTest
  @Tag("AC-2.6.1")
  @ValueSource(ints = {200, 503})
  void ac_2_6_1_challengePageIsABlockSentOnce(int code, CapturedOutput output) throws IOException {
    BlockStore store = store();
    transport.always(
        new Transport.Response(
            code, java.util.Optional.empty(), "<html><script>window._cf_chl_opt={}</script>"));

    assertThat(outcomeOf(boundA(store), PAGE)).isEqualTo(BLOCKED);

    assertThat(transport.requests).hasSize(1);
    assertThat(sleeper.sleeps).isEmpty();
    assertThat(reasonInFile()).isEqualTo("CHALLENGE");
    assertOneWarnLine(output, "CHALLENGE");
  }

  @Test
  @Tag("AC-2.6.1")
  void ac_2_6_1_robotsDisallowIsABlockWithNoPageRequest(CapturedOutput output) throws IOException {
    BlockStore store = store();
    transport.always(url ->
        url.getPath().equals("/robots.txt") ? ok("User-agent: *\nDisallow: /cauta\n") : ok("page"));
    FetchClient.BoundHttp http =
        new FetchClient(
                TestRegistries.fetchable(10, 1, 4000),
                runtime,
                store,
                transport,
                clock,
                sleeper,
                UA,
                new RobotsCache(clock))
            .bind("a.example");

    assertThat(outcomeOf(http, SEARCH)).isEqualTo(BLOCKED);

    assertThat(transport.requests).extracting(StubTransport.Request::url).containsExactly(ROBOTS);
    assertThat(reasonInFile()).isEqualTo("ROBOTS");
    assertOneWarnLine(output, "ROBOTS");
  }

  @Test
  @Tag("AC-2.6.1")
  void ac_2_6_1_plainServerErrorIsStillRetriedAndNoBlock() {
    BlockStore store = store();
    transport.always(StubTransport.status(503));
    assertThat(outcomeOf(boundA(store), PAGE))
        .isEqualTo(new AdapterOutcome.Unavailable(UnavailableReason.HTTP_ERROR));
    assertThat(transport.requests).hasSize(3);
    assertThat(store.isBlocked("a.example")).isFalse();
    assertThat(blocksFile).doesNotExist();
  }

  @Test
  @Tag("AC-2.6.1")
  void ac_2_6_1_failedWriteStillGivesBlocked() throws IOException {
    Files.createDirectory(dir.resolve("gone"));
    BlockStore store =
        new BlockStore(
            dir.resolve("gone").resolve("missing-dir").resolve("blocks.json"),
            runtime,
            clock,
            (retailer, reason) -> {});
    transport.always(StubTransport.status(403));
    FetchClient.BoundHttp http = boundA(store);

    assertThat(outcomeOf(http, PAGE)).isEqualTo(BLOCKED);
    assertThat(outcomeOf(http, PAGE)).isEqualTo(BLOCKED);
    assertThat(transport.requests).hasSize(1);
  }

  // ---- AC-2.6.2: an active Block sends nothing, also after a restart ----

  @Test
  @Tag("AC-2.6.2")
  void ac_2_6_2_activeBlockSendsNothingAndSurvivesARestart(CapturedOutput output) {
    transport.always(StubTransport.status(403));
    outcomeOf(boundA(store()), PAGE);
    transport.requests.clear();

    BlockStore restarted = store();
    assertThat(restarted.isBlocked("a.example")).isTrue();
    transport.always(ok("page"));
    FetchClient.BoundHttp http =
        new FetchClient(
                TestRegistries.fetchable(10, 1, 4000),
                runtime,
                restarted,
                transport,
                clock,
                sleeper,
                UA,
                new RobotsCache(clock))
            .bind("a.example");
    assertThat(outcomeOf(http, PAGE)).isEqualTo(BLOCKED);
    assertThat(outcomeOf(http, URI.create("https://www.a.example/p/2"))).isEqualTo(BLOCKED);
    assertThat(transport.requests).as("not even robots.txt").isEmpty();
    assertThat(warnLines(output)).hasSize(1);
  }

  @Test
  @Tag("AC-2.6.2")
  void ac_2_6_2_runnerBlockSendsNothing() throws IOException {
    Files.writeString(
        runtimeFile,
        "retailers:\n  a.example:\n    fetching: allowed\n"
            + "    runnerBlock: {blockedAt: \"2026-11-18T04:00:00Z\", reason: CHALLENGE}\n");
    poll();
    assertThat(outcomeOf(boundA(store()), PAGE)).isEqualTo(BLOCKED);
    assertThat(transport.requests).isEmpty();
  }

  // ---- AC-2.6.3: only a polled clear ends a Block ----

  @Test
  @Tag("AC-2.6.3")
  void ac_2_6_3_polledClearEndsTheBlock() throws IOException {
    BlockStore store = store();
    transport.then(StubTransport.status(403));
    FetchClient.BoundHttp http = boundA(store);
    assertThat(outcomeOf(http, PAGE)).isEqualTo(BLOCKED);

    writeClearGeneration(1);
    assertThat(outcomeOf(http, PAGE)).as("clear not polled yet").isEqualTo(BLOCKED);
    poll();

    assertThat(http.get(PAGE)).isEqualTo("<html>page</html>");
    assertThat(transport.requests).hasSize(2);
  }

  @Test
  @Tag("AC-2.6.3")
  void ac_2_6_3_blockRecordedBetweenClearAndPollStaysActive() throws IOException {
    BlockStore store = store();
    writeClearGeneration(1);
    transport.then(StubTransport.status(403));
    FetchClient.BoundHttp http = boundA(store);
    assertThat(outcomeOf(http, PAGE)).isEqualTo(BLOCKED);

    poll();

    assertThat(outcomeOf(http, PAGE)).isEqualTo(BLOCKED);
    assertThat(transport.requests).hasSize(1);
  }
}
