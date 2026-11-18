package ro.pricechecker.registry;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * The Kill switch (FR-7, S-8, AD-9): reads {@code runtime.yaml} at most every 15 s, lazily, when a
 * caller asks for a {@link #refresh()} (no scheduler thread; time from the injected {@link Clock},
 * T-5). Fails closed: until a good read, every retailer is stopped. A rejected read keeps the last
 * good settings and returns its reason; the caller logs it, so {@code registry} needs no logger.
 * The bean and its file path are wired by {@code ro.pricechecker.RuntimeSwitchConfig}, so {@code
 * registry} reads no property itself (AC 1.5.4).
 */
public final class RuntimeSwitch {

  /** Why a due read was rejected; travels in the log line's {@code outcome} (AD-14). */
  public enum Rejection {
    MISSING,
    UNREADABLE,
    UNPARSEABLE
  }

  static final Duration POLL_INTERVAL = Duration.ofSeconds(15);

  private final Path file;
  private final Clock clock;

  private volatile RuntimeSettings settings = RuntimeSettings.NONE;
  private Instant lastAttempt;

  public RuntimeSwitch(Path file, Clock clock) {
    this.file = file;
    this.clock = clock;
  }

  /**
   * Re-reads the file when no read happened yet or at least 15 s passed since the last attempt.
   * Returns the {@link Rejection} reason when this call read the file and rejected it; empty
   * otherwise.
   */
  public synchronized Optional<Rejection> refresh() {
    Instant now = Instant.now(clock);
    // A clock that stepped back re-reads at once instead of freezing the switch.
    if (lastAttempt != null
        && !now.isBefore(lastAttempt)
        && now.isBefore(lastAttempt.plus(POLL_INTERVAL))) {
      return Optional.empty();
    }
    lastAttempt = now;
    String text;
    try {
      text = readText();
    } catch (NoSuchFileException e) {
      return Optional.of(Rejection.MISSING);
    } catch (IOException | SecurityException e) {
      return Optional.of(Rejection.UNREADABLE);
    }
    try {
      settings = RuntimeSettings.parse(text);
    } catch (IllegalArgumentException e) {
      return Optional.of(Rejection.UNPARSEABLE);
    }
    return Optional.empty();
  }

  /**
   * Reads and parses the file now, outside the poll and without changing the current settings; for
   * {@link BlockStore#record}, so a {@code clear-block} written but not yet polled cannot cancel a
   * new Block (AD-9). Empty when the file is missing, unreadable or rejected.
   */
  Optional<RuntimeSettings> readFile() {
    try {
      return Optional.of(RuntimeSettings.parse(readText()));
    } catch (IOException | SecurityException | IllegalArgumentException e) {
      return Optional.empty();
    }
  }

  private String readText() throws IOException {
    return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
  }

  /** Whether fetching is allowed for {@code domain} in the current settings (fail closed). */
  public boolean isAllowed(String domain) {
    return settings.isAllowed(domain);
  }

  /** The polled {@code clearGeneration} for {@code domain}; 0 when absent or before a good read. */
  public long clearGeneration(String domain) {
    return settings.clearGeneration(domain);
  }

  /** Whether the polled settings hold a {@code runnerBlock} for {@code domain} (AD-16). */
  public boolean hasRunnerBlock(String domain) {
    return settings.hasRunnerBlock(domain);
  }
}
