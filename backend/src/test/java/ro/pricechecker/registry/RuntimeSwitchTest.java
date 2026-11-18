package ro.pricechecker.registry;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The 15 s lazy poll, fail closed, last good kept (AD-9, T-5). */
class RuntimeSwitchTest {

  private static final String ALLOWED = "retailers:\n  a.example: {fetching: allowed}\n";
  private static final String STOPPED = "retailers:\n  a.example: {fetching: stopped}\n";

  @TempDir Path dir;

  private final MutableClock clock = new MutableClock(Instant.parse("2026-11-18T10:00:00Z"));

  private Path file() {
    return dir.resolve("runtime.yaml");
  }

  private RuntimeSwitch runtimeSwitch() {
    return new RuntimeSwitch(file(), clock);
  }

  private void write(String yaml) throws IOException {
    Files.writeString(file(), yaml);
  }

  @Test
  @Tag("AC-2.3.2")
  void ac_2_3_2_stoppedBeforeTheFirstRead() throws IOException {
    write(ALLOWED);
    assertThat(runtimeSwitch().isAllowed("a.example")).isFalse();
  }

  @Test
  @Tag("AC-2.3.1")
  void ac_2_3_1_firstRefreshReadsTheFile() throws IOException {
    write(ALLOWED);
    RuntimeSwitch runtime = runtimeSwitch();
    assertThat(runtime.refresh()).isEmpty();
    assertThat(runtime.isAllowed("a.example")).isTrue();
    assertThat(runtime.isAllowed("b.example")).isFalse();
  }

  @Test
  @Tag("AC-2.3.1")
  void ac_2_3_1_changeSeenAfter15sNotBefore() throws IOException {
    write(ALLOWED);
    RuntimeSwitch runtime = runtimeSwitch();
    runtime.refresh();
    write(STOPPED);

    clock.advance(Duration.ofSeconds(14));
    assertThat(runtime.refresh()).isEmpty();
    assertThat(runtime.isAllowed("a.example")).isTrue();

    clock.advance(Duration.ofSeconds(1));
    assertThat(runtime.refresh()).isEmpty();
    assertThat(runtime.isAllowed("a.example")).isFalse();

    write(ALLOWED);
    clock.advance(Duration.ofSeconds(15));
    runtime.refresh();
    assertThat(runtime.isAllowed("a.example")).isTrue();
  }

  @Test
  @Tag("AC-2.3.2")
  void ac_2_3_2_missingFileIsRejectedAndStopsEverything() {
    RuntimeSwitch runtime = runtimeSwitch();
    assertThat(runtime.refresh()).contains(RuntimeSwitch.Rejection.MISSING);
    assertThat(runtime.isAllowed("a.example")).isFalse();
  }

  @Test
  @Tag("AC-2.3.2")
  void ac_2_3_2_garbageWithNoGoodReadStopsEverything() throws IOException {
    write("::: not yaml [");
    RuntimeSwitch runtime = runtimeSwitch();
    assertThat(runtime.refresh()).contains(RuntimeSwitch.Rejection.UNPARSEABLE);
    assertThat(runtime.isAllowed("a.example")).isFalse();
  }

  @Test
  @Tag("AC-2.3.2")
  void ac_2_3_2_directoryIsUnreadable() throws IOException {
    Files.createDirectory(file());
    RuntimeSwitch runtime = runtimeSwitch();
    assertThat(runtime.refresh()).contains(RuntimeSwitch.Rejection.UNREADABLE);
    assertThat(runtime.isAllowed("a.example")).isFalse();
  }

  @Test
  @Tag("AC-2.3.3")
  void ac_2_3_3_badAfterGoodKeepsLastGood() throws IOException {
    write(ALLOWED);
    RuntimeSwitch runtime = runtimeSwitch();
    runtime.refresh();

    write("retailers:\n  a.example: {fetching: yes}\n");
    clock.advance(Duration.ofSeconds(15));
    assertThat(runtime.refresh()).contains(RuntimeSwitch.Rejection.UNPARSEABLE);
    assertThat(runtime.isAllowed("a.example")).isTrue();

    Files.delete(file());
    clock.advance(Duration.ofSeconds(15));
    assertThat(runtime.refresh()).contains(RuntimeSwitch.Rejection.MISSING);
    assertThat(runtime.isAllowed("a.example")).isTrue();
  }

  @Test
  @Tag("AC-2.3.3")
  void ac_2_3_3_rejectedReadIsReportedOncePerAttempt() throws IOException {
    write("garbage: [");
    RuntimeSwitch runtime = runtimeSwitch();
    assertThat(runtime.refresh()).contains(RuntimeSwitch.Rejection.UNPARSEABLE);
    clock.advance(Duration.ofSeconds(5));
    assertThat(runtime.refresh()).isEqualTo(Optional.empty());
    clock.advance(Duration.ofSeconds(10));
    assertThat(runtime.refresh()).contains(RuntimeSwitch.Rejection.UNPARSEABLE);
  }

  @Test
  @Tag("AC-2.3.1")
  void ac_2_3_1_clockSteppedBackReReadsAtOnce() throws IOException {
    write(ALLOWED);
    RuntimeSwitch runtime = runtimeSwitch();
    runtime.refresh();
    write(STOPPED);
    clock.advance(Duration.ofSeconds(-60));
    runtime.refresh();
    assertThat(runtime.isAllowed("a.example")).isFalse();
  }
}
