package ro.pricechecker.registry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assumptions.assumeThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Story 2.6: the Block state, its file and the AD-9 generation rule (T-5: injected clock). */
class BlockStoreTest {

  private static final Instant START = Instant.parse("2026-11-18T10:00:00Z");

  @TempDir Path dir;

  private final MutableClock clock = new MutableClock(START);
  private final List<String> events = new ArrayList<>();
  private Path runtimeFile;
  private Path blocksFile;
  private RuntimeSwitch runtime;

  @BeforeEach
  void setUp() throws IOException {
    runtimeFile = dir.resolve("runtime.yaml");
    blocksFile = dir.resolve("blocks.json");
    writeRuntime(0);
    runtime = new RuntimeSwitch(runtimeFile, clock);
    assertThat(runtime.refresh()).isEmpty();
  }

  private void writeRuntime(long clearGeneration) throws IOException {
    Files.writeString(
        runtimeFile,
        "retailers:\n  a.example: {fetching: allowed, clearGeneration: "
            + clearGeneration
            + "}\n  b.example: {fetching: allowed}\n");
  }

  private void poll() {
    clock.advance(Duration.ofSeconds(15));
    assertThat(runtime.refresh()).isEmpty();
  }

  private BlockStore store() {
    return new BlockStore(
        blocksFile, runtime, clock, (retailer, reason) -> events.add(retailer + " " + reason));
  }

  @Test
  void missingFileMeansNoBlocks() {
    BlockStore store = store();
    assertThat(store.isBlocked("a.example")).isFalse();
    assertThat(store.entries()).isEmpty();
    assertThat(blocksFile).doesNotExist();
  }

  @Test
  @Tag("AC-2.6.1")
  void ac_2_6_1_recordCreatesAMissingStateDirectory() {
    Path nested = dir.resolve("state").resolve("blocks.json");
    BlockStore store = new BlockStore(nested, runtime, clock, (retailer, reason) -> {});
    store.record("a.example", BlockStore.Reason.HTTP_403);
    assertThat(nested).exists();
    assertThat(new BlockStore(nested, runtime, clock, (r, why) -> {}).isBlocked("a.example"))
        .isTrue();
  }

  @Test
  @Tag("AC-2.6.1")
  void ac_2_6_1_recordWritesTheWholeMapAndTellsTheListener() throws IOException {
    BlockStore store = store();
    store.record("a.example", BlockStore.Reason.HTTP_403);
    clock.advance(Duration.ofSeconds(1));
    store.record("b.example", BlockStore.Reason.ROBOTS);

    JsonNode root = JsonMapper.builder().build().readTree(Files.readString(blocksFile));
    JsonNode a = root.get("retailers").get("a.example");
    assertThat(a.get("blockGeneration").longValue()).isZero();
    assertThat(a.get("blockedAt").stringValue()).isEqualTo("2026-11-18T10:00:00Z");
    assertThat(a.get("reason").stringValue()).isEqualTo("HTTP_403");
    assertThat(root.get("retailers").get("b.example").get("reason").stringValue())
        .isEqualTo("ROBOTS");
    assertThat(events).containsExactly("a.example HTTP_403", "b.example ROBOTS");
    try (Stream<Path> files = Files.list(dir)) {
      assertThat(files.map(p -> p.getFileName().toString()))
          .containsExactlyInAnyOrder("runtime.yaml", "blocks.json");
    }
  }

  @Test
  @Tag("AC-2.6.1")
  void ac_2_6_1_secondRecordOfAnActiveBlockDoesNothing() {
    BlockStore store = store();
    store.record("a.example", BlockStore.Reason.HTTP_403);
    clock.advance(Duration.ofSeconds(5));
    store.record("a.example", BlockStore.Reason.CHALLENGE);
    assertThat(events).containsExactly("a.example HTTP_403");
    assertThat(store.entries().get("a.example").reason()).isEqualTo(BlockStore.Reason.HTTP_403);
    assertThat(store.entries().get("a.example").blockedAt()).isEqualTo(START);
  }

  @Test
  @Tag("AC-2.6.2")
  void ac_2_6_2_blockIsActiveAndSurvivesARestart() {
    store().record("a.example", BlockStore.Reason.CHALLENGE);
    assertThat(store().isBlocked("a.example")).isTrue();
    BlockStore restarted = store();
    assertThat(restarted.isBlocked("a.example")).isTrue();
    assertThat(restarted.isBlocked("b.example")).isFalse();
    assertThat(restarted.entries().get("a.example"))
        .isEqualTo(new BlockStore.Entry(0, START, BlockStore.Reason.CHALLENGE));
  }

  @Test
  @Tag("AC-2.6.2")
  void ac_2_6_2_runnerBlockIsActiveWithoutAnEntry() throws IOException {
    Files.writeString(
        runtimeFile,
        "retailers:\n  a.example:\n    fetching: allowed\n"
            + "    runnerBlock: {blockedAt: \"2026-11-18T04:00:00Z\", reason: CHALLENGE}\n");
    poll();
    assertThat(store().isBlocked("a.example")).isTrue();
  }

  @Test
  @Tag("AC-2.6.3")
  void ac_2_6_3_polledClearEndsTheBlock() throws IOException {
    BlockStore store = store();
    store.record("a.example", BlockStore.Reason.HTTP_403);
    writeRuntime(1);
    assertThat(store.isBlocked("a.example")).as("not polled yet").isTrue();
    poll();
    assertThat(store.isBlocked("a.example")).isFalse();
    assertThat(store().isBlocked("a.example")).as("after a restart too").isFalse();
  }

  @Test
  @Tag("AC-2.6.3")
  void ac_2_6_3_blockRecordedAfterAnUnpolledClearStaysActive() throws IOException {
    BlockStore store = store();
    writeRuntime(1);
    store.record("a.example", BlockStore.Reason.HTTP_403);
    assertThat(store.entries().get("a.example").blockGeneration()).isEqualTo(1);
    poll();
    assertThat(store.isBlocked("a.example")).isTrue();
  }

  @Test
  @Tag("AC-2.6.3")
  void ac_2_6_3_inFlightBlockAfterAnUnpolledClearOfAnOlderBlockStaysActive() throws IOException {
    BlockStore store = store();
    store.record("a.example", BlockStore.Reason.HTTP_403);
    writeRuntime(1);
    store.record("a.example", BlockStore.Reason.CHALLENGE);
    assertThat(store.entries().get("a.example").blockGeneration()).isEqualTo(1);
    poll();
    assertThat(store.isBlocked("a.example")).isTrue();
  }

  @Test
  @Tag("AC-2.6.3")
  void ac_2_6_3_newBlockAfterAClearIsRecordedAgain() throws IOException {
    BlockStore store = store();
    store.record("a.example", BlockStore.Reason.HTTP_403);
    writeRuntime(1);
    poll();
    store.record("a.example", BlockStore.Reason.ROBOTS);
    assertThat(store.isBlocked("a.example")).isTrue();
    assertThat(store.entries().get("a.example").blockGeneration()).isEqualTo(1);
    assertThat(events).containsExactly("a.example HTTP_403", "a.example ROBOTS");
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "",
        "not json",
        "[]",
        "{}",
        "{\"retailers\": []}",
        "{\"retailers\": {\"a.example\": 1}}",
        "{\"retailers\": {\"a.example\": {\"blockGeneration\": -1,"
            + " \"blockedAt\": \"2026-11-18T10:00:00Z\", \"reason\": \"HTTP_403\"}}}",
        "{\"retailers\": {\"a.example\": {\"blockGeneration\": 1.5,"
            + " \"blockedAt\": \"2026-11-18T10:00:00Z\", \"reason\": \"HTTP_403\"}}}",
        "{\"retailers\": {\"a.example\": {\"blockGeneration\": 1,"
            + " \"blockedAt\": \"yesterday\", \"reason\": \"HTTP_403\"}}}",
        "{\"retailers\": {\"a.example\": {\"blockGeneration\": 1,"
            + " \"blockedAt\": \"2026-11-18T10:00:00Z\", \"reason\": \"TEAPOT\"}}}",
        "{\"retailers\": {\"a.example\": {\"blockGeneration\": 1,"
            + " \"blockedAt\": \"2026-11-18T10:00:00Z\"}}}",
        "{\"retailers\": {}, \"retailers\": {}}"
      })
  void unparseableFileFailsTheConstructor(String json) throws IOException {
    Files.writeString(blocksFile, json);
    assertThatThrownBy(this::store).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void unreadableFileFailsTheConstructor() throws IOException {
    Files.createDirectory(blocksFile);
    assertThatThrownBy(this::store).isInstanceOf(UncheckedIOException.class);
  }

  @Test
  @Tag("AC-2.6.1")
  void failedWriteKeepsTheBlockAndStillTellsTheListener() throws IOException {
    assumeThat(dir.getFileSystem().supportedFileAttributeViews()).contains("posix");
    Path readOnly = Files.createDirectory(dir.resolve("ro"));
    BlockStore store =
        new BlockStore(
            readOnly.resolve("blocks.json"),
            runtime,
            clock,
            (retailer, reason) -> events.add(retailer + " " + reason));
    Files.setPosixFilePermissions(readOnly, PosixFilePermissions.fromString("r-x------"));
    try {
      assertThatThrownBy(() -> store.record("a.example", BlockStore.Reason.HTTP_403))
          .isInstanceOf(UncheckedIOException.class);
    } finally {
      Files.setPosixFilePermissions(readOnly, PosixFilePermissions.fromString("rwx------"));
    }
    assertThat(store.isBlocked("a.example")).isTrue();
    assertThat(events).containsExactly("a.example HTTP_403");
  }

  // ---- story 2.7: notBefore after a 429 ----

  private JsonNode entryInFile() throws IOException {
    return JsonMapper.builder()
        .build()
        .readTree(Files.readString(blocksFile))
        .get("retailers")
        .get("a.example");
  }

  @Test
  @Tag("AC-2.7.1")
  void ac_2_7_1_retryAfterIsPersistedAndIsNotABlock() throws IOException {
    BlockStore store = store();
    store.recordRetryAfter("a.example", START.plusSeconds(60));

    assertThat(store.notBefore("a.example")).contains(START.plusSeconds(60));
    assertThat(store.isBlocked("a.example")).isFalse();
    assertThat(events).isEmpty();
    JsonNode entry = entryInFile();
    assertThat(entry.get("notBefore").stringValue()).isEqualTo("2026-11-18T10:01:00Z");
    assertThat(entry.has("reason")).isFalse();
    assertThat(entry.has("blockGeneration")).isFalse();
    assertThat(store.notBefore("b.example")).isEmpty();
  }

  @Test
  @Tag("AC-2.7.1")
  void ac_2_7_1_notBeforeNeverMovesEarlier() throws IOException {
    BlockStore store = store();
    store.recordRetryAfter("a.example", START.plusSeconds(300));
    store.recordRetryAfter("a.example", START.plusSeconds(60));

    assertThat(store.notBefore("a.example")).contains(START.plusSeconds(300));
    assertThat(entryInFile().get("notBefore").stringValue()).isEqualTo("2026-11-18T10:05:00Z");

    store.recordRetryAfter("a.example", START.plusSeconds(600));
    assertThat(store.notBefore("a.example")).contains(START.plusSeconds(600));
  }

  @Test
  @Tag("AC-2.7.3")
  void ac_2_7_3_notBeforeSurvivesARestart() {
    store().recordRetryAfter("a.example", START.plusSeconds(120));
    BlockStore restarted = store();
    assertThat(restarted.notBefore("a.example")).contains(START.plusSeconds(120));
    assertThat(restarted.isBlocked("a.example")).isFalse();
  }

  @Test
  @Tag("AC-2.7.1")
  void ac_2_7_1_blockAndNotBeforeAreBothKeptOnEveryWrite() throws IOException {
    BlockStore store = store();
    store.recordRetryAfter("a.example", START.plusSeconds(120));
    store.record("a.example", BlockStore.Reason.HTTP_403);
    assertThat(entryInFile().get("notBefore").stringValue()).isEqualTo("2026-11-18T10:02:00Z");
    assertThat(entryInFile().get("reason").stringValue()).isEqualTo("HTTP_403");

    store.recordRetryAfter("a.example", START.plusSeconds(240));
    BlockStore restarted = store();
    assertThat(restarted.entries().get("a.example"))
        .isEqualTo(
            new BlockStore.Entry(0, START, BlockStore.Reason.HTTP_403, START.plusSeconds(240)));
    assertThat(restarted.isBlocked("a.example")).isTrue();
  }

  @Test
  @Tag("AC-2.7.3")
  void ac_2_7_3_laterOfFileAndRuntimeNotBeforeWins() throws IOException {
    BlockStore store = store();
    store.recordRetryAfter("a.example", START.plusSeconds(60));
    Files.writeString(
        runtimeFile,
        "retailers:\n  a.example: {fetching: allowed, notBefore: \"2026-11-18T11:00:00Z\"}\n"
            + "  b.example: {fetching: allowed, notBefore: \"2026-11-18T09:00:00Z\"}\n");
    poll();

    assertThat(store.notBefore("a.example")).contains(Instant.parse("2026-11-18T11:00:00Z"));
    assertThat(store.notBefore("b.example")).contains(Instant.parse("2026-11-18T09:00:00Z"));
    store.recordRetryAfter("b.example", START.plusSeconds(60));
    assertThat(store.notBefore("b.example")).contains(START.plusSeconds(60));
    assertThat(store.isBlocked("a.example")).isFalse();
  }

  @Test
  @Tag("AC-2.7.1")
  void ac_2_7_1_parseIgnoresUnknownFieldsAndReadsNotBefore() {
    var parsed =
        BlockStore.parse(
            """
            {"retailers": {
              "a.example": {"notBefore": "2026-11-18T10:01:00Z", "later": 1},
              "b.example": {"blockGeneration": 2, "blockedAt": "2026-11-18T10:00:00Z",
                            "reason": "ROBOTS", "extra": {"x": true}}
            }, "version": 1}
            """);
    assertThat(parsed.get("a.example"))
        .isEqualTo(new BlockStore.Entry(0, null, null, START.plusSeconds(60)));
    assertThat(parsed.get("b.example"))
        .isEqualTo(new BlockStore.Entry(2, START, BlockStore.Reason.ROBOTS));
  }

  @ParameterizedTest
  @Tag("AC-2.7.1")
  @ValueSource(
      strings = {
        "{\"retailers\": {\"a.example\": {}}}",
        "{\"retailers\": {\"a.example\": {\"other\": 1}}}",
        "{\"retailers\": {\"a.example\": {\"notBefore\": \"soon\"}}}",
        "{\"retailers\": {\"a.example\": {\"notBefore\": 60}}}",
        "{\"retailers\": {\"a.example\": {\"reason\": \"HTTP_403\","
            + " \"notBefore\": \"2026-11-18T10:01:00Z\"}}}",
        "{\"retailers\": {\"a.example\": {\"blockGeneration\": 0,"
            + " \"blockedAt\": \"2026-11-18T10:00:00Z\", \"reason\": \"HTTP_429\"}}}",
      })
  void ac_2_7_1_parseStaysStrictOnKnownFields(String json) {
    assertThatThrownBy(() -> BlockStore.parse(json)).isInstanceOf(IllegalArgumentException.class);
  }
}
