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

  /** One retailer's entry in {@code blocks.json}. */
  record Entry(long blockGeneration, Instant blockedAt, Reason reason) {}

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
    return entry != null && entry.blockGeneration() >= runtime.clearGeneration(retailer);
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
    if (current != null && current.blockGeneration() >= generation) {
      return;
    }
    entries.put(retailer, new Entry(generation, clock.instant(), reason));
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
      fields.put("blockGeneration", entry.blockGeneration());
      fields.put("blockedAt", entry.blockedAt().toString());
      fields.put("reason", entry.reason().name());
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

  /** {@code {"retailers": {"<domain>": {blockGeneration, blockedAt, reason}}}}; strict. */
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
      JsonNode generation = node.path("blockGeneration");
      if (!generation.isIntegralNumber()
          || !generation.canConvertToLong()
          || generation.longValue() < 0) {
        throw new IllegalArgumentException("blockGeneration is not a non-negative integer");
      }
      JsonNode blockedAt = node.path("blockedAt");
      JsonNode reason = node.path("reason");
      if (!blockedAt.isString() || !reason.isString()) {
        throw new IllegalArgumentException("blockedAt or reason is not a string");
      }
      try {
        parsed.put(
            e.getKey(),
            new Entry(
                generation.longValue(),
                Instant.parse(blockedAt.stringValue()),
                Reason.valueOf(reason.stringValue())));
      } catch (DateTimeParseException | IllegalArgumentException ex) {
        throw new IllegalArgumentException("bad blockedAt or reason", ex);
      }
    }
    return parsed;
  }
}
