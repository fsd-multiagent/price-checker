package ro.pricechecker.registry;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Story 2.6 gaps found by Eufrosina (QA): the whole map survives rewrites and restarts, a clear
 * ends only its own retailer's Block, and a Block recorded after a clear stays active over a
 * restart (AD-9). Injected clock, no sleeps (T-5).
 */
class BlockStoreAcceptanceGapsTest {

  private static final Instant START = Instant.parse("2026-11-18T10:00:00Z");

  @TempDir Path dir;

  private final MutableClock clock = new MutableClock(START);
  private Path runtimeFile;
  private Path blocksFile;
  private RuntimeSwitch runtime;

  @BeforeEach
  void setUp() throws IOException {
    runtimeFile = dir.resolve("runtime.yaml");
    blocksFile = dir.resolve("blocks.json");
    writeRuntime(0, 0);
    runtime = new RuntimeSwitch(runtimeFile, clock);
    assertThat(runtime.refresh()).isEmpty();
  }

  private void writeRuntime(long clearA, long clearB) throws IOException {
    Files.writeString(
        runtimeFile,
        "retailers:\n"
            + "  a.example: {fetching: allowed, clearGeneration: "
            + clearA
            + "}\n"
            + "  b.example: {fetching: allowed, clearGeneration: "
            + clearB
            + "}\n");
  }

  private void poll() {
    clock.advance(Duration.ofSeconds(15));
    assertThat(runtime.refresh()).isEmpty();
  }

  private BlockStore store() {
    return new BlockStore(blocksFile, runtime, clock, (retailer, reason) -> {});
  }

  @Test
  @Tag("AC-2.6.1")
  void ac_2_6_1_recordAfterARestartRewritesTheWholeMapAtomically() throws IOException {
    store().record("a.example", BlockStore.Reason.HTTP_403);
    clock.advance(Duration.ofMinutes(1));
    BlockStore restarted = store();
    restarted.record("b.example", BlockStore.Reason.CHALLENGE);

    BlockStore again = store();
    assertThat(again.entries())
        .containsEntry("a.example", new BlockStore.Entry(0, START, BlockStore.Reason.HTTP_403))
        .containsEntry(
            "b.example",
            new BlockStore.Entry(0, START.plusSeconds(60), BlockStore.Reason.CHALLENGE));
    try (Stream<Path> files = Files.list(dir)) {
      assertThat(files.map(p -> p.getFileName().toString()))
          .as("no temp file left behind")
          .containsExactlyInAnyOrder("runtime.yaml", "blocks.json");
    }
  }

  @Test
  @Tag("AC-2.6.2")
  void ac_2_6_2_runnerBlockIsActiveAfterARestart() throws IOException {
    Files.writeString(
        runtimeFile,
        "retailers:\n  a.example:\n    fetching: allowed\n"
            + "    runnerBlock: {blockedAt: \"2026-11-18T04:00:00Z\", reason: CHALLENGE}\n");
    poll();
    assertThat(store().isBlocked("a.example")).isTrue();
    RuntimeSwitch restartedRuntime = new RuntimeSwitch(runtimeFile, clock);
    assertThat(restartedRuntime.refresh()).isEmpty();
    BlockStore restarted = new BlockStore(blocksFile, restartedRuntime, clock, (r, why) -> {});
    assertThat(restarted.isBlocked("a.example")).isTrue();
    assertThat(restarted.entries()).as("a runnerBlock is not written to blocks.json").isEmpty();
  }

  @Test
  @Tag("AC-2.6.3")
  void ac_2_6_3_clearOfOneRetailerLeavesAnotherBlocked() throws IOException {
    BlockStore store = store();
    store.record("a.example", BlockStore.Reason.HTTP_403);
    store.record("b.example", BlockStore.Reason.ROBOTS);
    writeRuntime(1, 0);
    poll();
    assertThat(store.isBlocked("a.example")).isFalse();
    assertThat(store.isBlocked("b.example")).isTrue();
  }

  @Test
  @Tag("AC-2.6.3")
  void ac_2_6_3_blockRecordedAfterAPolledClearSurvivesARestart() throws IOException {
    store().record("a.example", BlockStore.Reason.HTTP_403);
    writeRuntime(1, 0);
    poll();
    BlockStore store = store();
    assertThat(store.isBlocked("a.example")).isFalse();
    store.record("a.example", BlockStore.Reason.CHALLENGE);

    BlockStore restarted = store();
    assertThat(restarted.isBlocked("a.example")).isTrue();
    assertThat(restarted.entries().get("a.example").blockGeneration()).isEqualTo(1);
  }
}
