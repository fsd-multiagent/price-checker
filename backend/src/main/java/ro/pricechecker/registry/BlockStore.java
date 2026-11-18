package ro.pricechecker.registry;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Clock;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The Block state (FR-11, S-6, AD-9), and the only reader and writer of {@code blocks.json}. Loaded
 * once at start, then kept in memory; every {@link #record} writes the whole map atomically (a temp
 * file in the same directory, then {@code ATOMIC_MOVE}).
 *
 * <p>A recorded Block carries {@code blockGeneration}, the larger of the polled and the file's
 * {@code clearGeneration} at the moment of the Block, so a {@code clear-block} written but not yet
 * polled cannot cancel it. A Block is active while {@code blockGeneration >= clearGeneration} or
 * {@code runtime.yaml} holds a {@code runnerBlock} for the retailer (AD-16); only an operator
 * {@code clear-block} ends either kind. A missing file means no Blocks; an unreadable or
 * unparseable file fails the constructor, so the application refuses to start (fail closed).
 *
 * <p>A 429 is never a Block (AC-11.4): {@link #recordRetryAfter} keeps the time the retailer asked
 * us to wait until as the entry's {@code notBefore}, persisted with the same whole-map write and
 * never moved earlier. {@link #notBefore} is the later of that and the polled {@code runtime.yaml}
 * {@code notBefore} (a runner 429, AD-16). An entry holds the Block fields ({@code
 * blockGeneration}, {@code blockedAt}, {@code reason}) all or none, an optional {@code notBefore},
 * and at least one of the two; unknown fields are ignored.
 *
 * <p>{@code registry} has no logger: the {@link Listener} carries each new Block out, and the
 * wiring in {@code ro.pricechecker.BlockStoreConfig} names the file, so this class reads no
 * property. Time comes from the injected {@link Clock} (T-5).
 */
public final class BlockStore {

  /** What caused a Block (AD-6 outcome table); travels in the log line's {@code outcome}. */
  public enum Reason {
    HTTP_403,
    CHALLENGE,
    ROBOTS
  }

  /** Told once per new Block, before the file is written, so a failed write is still logged. */
  @FunctionalInterface
  public interface Listener {
    void blocked(String retailer, Reason reason);
  }

  /**
   * One retailer's entry in {@code blocks.json}: a Block ({@code reason} non-null, with {@code
   * blockedAt}), a {@code notBefore}, or both. Absent values are {@code null}.
   */
  record Entry(long blockGeneration, Instant blockedAt, Reason reason, Instant notBefore) {

    /** A Block without a {@code notBefore}. */
    Entry(long blockGeneration, Instant blockedAt, Reason reason) {
      this(blockGeneration, blockedAt, reason, null);
    }

    boolean hasBlock() {
      return reason != null;
    }
  }

  private static final JsonMapper JSON =
      JsonMapper.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build();

  private final Path file;
  private final RuntimeSwitch runtime;
  private final Clock clock;
  private final Listener listener;
  private final Map<String, Entry> entries = new ConcurrentHashMap<>();

  /**
   * Loads {@code file}.
   *
   * @throws UncheckedIOException when the file exists but cannot be read
   * @throws IllegalArgumentException when the file cannot be parsed
   */
  public BlockStore(Path file, RuntimeSwitch runtime, Clock clock, Listener listener) {
    this.file = file;
    this.runtime = runtime;
    this.clock = clock;
    this.listener = listener;
    String text;
    try {
      text = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
    } catch (NoSuchFileException e) {
      return;
    } catch (IOException e) {
      throw new UncheckedIOException("blocks file unreadable", e);
    }
    entries.putAll(parse(text));
  }

  /** Whether {@code retailer} has an active Block: its own, or a {@code runnerBlock}. */
  public boolean isBlocked(String retailer) {
    if (runtime.hasRunnerBlock(retailer)) {
      return true;
    }
    Entry entry = entries.get(retailer);
    return entry != null
        && entry.hasBlock()
        && entry.blockGeneration() >= runtime.clearGeneration(retailer);
  }

  /**
   * The time before which nothing may be sent to {@code retailer}: the later of the persisted
   * {@code notBefore} and the polled {@code runtime.yaml} one; empty when neither exists.
   */
  public Optional<Instant> notBefore(String retailer) {
    Entry entry = entries.get(retailer);
    Optional<Instant> own = Optional.ofNullable(entry == null ? null : entry.notBefore());
    Optional<Instant> runner = runtime.notBefore(retailer);
    if (own.isEmpty()) {
      return runner;
    }
    if (runner.isEmpty()) {
      return own;
    }
    return own.get().isAfter(runner.get()) ? own : runner;
  }

  /**
   * Keeps {@code notBefore} for {@code retailer} after a 429 and writes {@code blocks.json}. Never
   * moves a stored value earlier (an earlier or equal one changes nothing and writes nothing); a
   * Block in the same entry is kept.
   *
   * @throws UncheckedIOException when the file cannot be written; the value stays in memory
   */
  public synchronized void recordRetryAfter(String retailer, Instant notBefore) {
    Entry current = entries.get(retailer);
    if (current != null && current.notBefore() != null && !notBefore.isAfter(current.notBefore())) {
      return;
    }
    entries.put(
        retailer,
        current == null
            ? new Entry(0, null, null, notBefore)
            : new Entry(
                current.blockGeneration(), current.blockedAt(), current.reason(), notBefore));
    write();
  }

  /**
   * Records a Block for {@code retailer}, tells the listener and writes {@code blocks.json}. Does
   * nothing when the retailer already has an active Block of its own, so concurrent hits log once.
   *
   * @throws UncheckedIOException when the file cannot be written; the Block stays active in memory
   *     and the listener was already told
   */
  public synchronized void record(String retailer, Reason reason) {
    // The file may hold a clear not polled yet; a new Block must outlive it (AD-9).
    long polled = runtime.clearGeneration(retailer);
    long inFile = runtime.readFile().map(s -> s.clearGeneration(retailer)).orElse(0L);
    long generation = Math.max(polled, inFile);
    Entry current = entries.get(retailer);
    if (current != null && current.hasBlock() && current.blockGeneration() >= generation) {
      return;
    }
    Instant notBefore = current == null ? null : current.notBefore();
    entries.put(retailer, new Entry(generation, clock.instant(), reason, notBefore));
    listener.blocked(retailer, reason);
    write();
  }

  /** The current entries, for tests. */
  Map<String, Entry> entries() {
    return Map.copyOf(entries);
  }

  private void write() {
    Map<String, Object> retailers = new TreeMap<>();
    entries.forEach((retailer, entry) -> {
      Map<String, Object> fields = new LinkedHashMap<>();
      if (entry.hasBlock()) {
        fields.put("blockGeneration", entry.blockGeneration());
        fields.put("blockedAt", entry.blockedAt().toString());
        fields.put("reason", entry.reason().name());
      }
      if (entry.notBefore() != null) {
        fields.put("notBefore", entry.notBefore().toString());
      }
      retailers.put(retailer, fields);
    });
    byte[] bytes =
        JSON.writeValueAsString(Map.of("retailers", retailers)).getBytes(StandardCharsets.UTF_8);
    Path dir = file.toAbsolutePath().getParent();
    Path temp = null;
    try {
      Files.createDirectories(dir);
      temp = Files.createTempFile(dir, "blocks", null);
      Files.write(temp, bytes);
      Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
      temp = null;
    } catch (IOException e) {
      throw new UncheckedIOException("blocks file not written", e);
    } finally {
      if (temp != null) {
        try {
          Files.deleteIfExists(temp);
        } catch (IOException ignored) {
          // The temp file is harmless; the failure that matters is already thrown.
        }
      }
    }
  }

  /**
   * {@code {"retailers": {"<domain>": {blockGeneration, blockedAt, reason, notBefore}}}}: strict on
   * these fields (the Block ones all or none, at least a Block or a {@code notBefore}), unknown
   * fields ignored.
   */
  static Map<String, Entry> parse(String json) {
    JsonNode root;
    try {
      root = JSON.readTree(json);
    } catch (JacksonException e) {
      throw new IllegalArgumentException("blocks file is not JSON", e);
    }
    if (root == null || !root.isObject() || !root.path("retailers").isObject()) {
      throw new IllegalArgumentException("no 'retailers' object");
    }
    Map<String, Entry> parsed = new LinkedHashMap<>();
    for (Map.Entry<String, JsonNode> e : root.get("retailers").properties()) {
      JsonNode node = e.getValue();
      if (!node.isObject()) {
        throw new IllegalArgumentException("retailer entry is not an object");
      }
      boolean hasGeneration = node.has("blockGeneration");
      boolean hasBlockedAt = node.has("blockedAt");
      boolean hasReason = node.has("reason");
      boolean hasBlock = hasGeneration || hasBlockedAt || hasReason;
      if (hasBlock && !(hasGeneration && hasBlockedAt && hasReason)) {
        throw new IllegalArgumentException("Block fields are not all present");
      }
      if (!hasBlock && !node.has("notBefore")) {
        throw new IllegalArgumentException("entry has neither a Block nor notBefore");
      }
      long generation = 0;
      Instant blockedAt = null;
      Reason reason = null;
      if (hasBlock) {
        JsonNode gen = node.get("blockGeneration");
        if (!gen.isIntegralNumber() || !gen.canConvertToLong() || gen.longValue() < 0) {
          throw new IllegalArgumentException("blockGeneration is not a non-negative integer");
        }
        generation = gen.longValue();
        JsonNode at = node.get("blockedAt");
        JsonNode why = node.get("reason");
        if (!at.isString() || !why.isString()) {
          throw new IllegalArgumentException("blockedAt or reason is not a string");
        }
        try {
          blockedAt = Instant.parse(at.stringValue());
          reason = Reason.valueOf(why.stringValue());
        } catch (DateTimeParseException | IllegalArgumentException ex) {
          throw new IllegalArgumentException("bad blockedAt or reason", ex);
        }
      }
      Instant notBefore = null;
      if (node.has("notBefore")) {
        JsonNode value = node.get("notBefore");
        if (!value.isString()) {
          throw new IllegalArgumentException("notBefore is not a string");
        }
        try {
          notBefore = Instant.parse(value.stringValue());
        } catch (DateTimeParseException ex) {
          throw new IllegalArgumentException("bad notBefore", ex);
        }
      }
      parsed.put(e.getKey(), new Entry(generation, blockedAt, reason, notBefore));
    }
    return parsed;
  }
}
