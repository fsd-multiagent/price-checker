package ro.pricechecker.registry;

import static ro.pricechecker.registry.SourceRegisterTest.register;
import static ro.pricechecker.registry.SourceRegisterTest.row;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;

/** Test-only access to the package-private {@link Registry} constructor and a test Kill switch. */
public final class TestRegistries {

  public static final String EAN_A = "4006381333931";
  public static final String EAN_B = "96385074";

  private TestRegistries() {}

  public static Registry of(String registerMarkdown, String retailersYaml, String knownEansYaml) {
    return new Registry(registerMarkdown, retailersYaml, knownEansYaml);
  }

  /**
   * a.example (Approved, enabled), b.example (Approved, disabled), c.example (Proposed, disabled).
   */
  public static Registry mixed() {
    String limits = "1 request / 2 s, concurrency 1";
    String reg =
        register(
            row("a.example", "Approved 2026-11-20", limits),
            row("b.example", "Approved 2026-11-20", limits),
            row("c.example", "Proposed", limits));
    String retailers =
        """
        retailers:
          a.example: {adapter: enabled, resolution: curated, minIntervalMs: 2000, concurrency: 1, deadlineMs: 4000}
          b.example: {adapter: disabled, resolution: curated, minIntervalMs: 2000, concurrency: 1, deadlineMs: 4000}
          c.example: {adapter: disabled, resolution: curated, minIntervalMs: 2000, concurrency: 1, deadlineMs: 4000}
        """;
    String eans =
        """
        knownEans:
          a.example:
            - {ean: "4006381333931", url: "https://a.example/p/1"}
            - {ean: "96385074", url: "https://a.example/p/2"}
          b.example:
            - {ean: "4006381333931", url: "https://b.example/p/1"}
            - {ean: "96385074", url: "https://b.example/p/2"}
        """;
    return of(reg, retailers, eans);
  }

  /**
   * Like {@link #mixed()} but with a loose register limit (10 ms, concurrency 4) so fetch tests
   * choose the retailer limits: a.example (Approved, enabled) gets the given values; b.example
   * (Approved, disabled) and c.example (Proposed, disabled) get the same.
   */
  public static Registry fetchable(int minIntervalMs, int concurrency, int deadlineMs) {
    String limits = "100 requests / 1 s, concurrency 4";
    String reg =
        register(
            row("a.example", "Approved 2026-11-20", limits),
            row("b.example", "Approved 2026-11-20", limits),
            row("c.example", "Proposed", limits));
    String values =
        "resolution: curated, minIntervalMs: %d, concurrency: %d, deadlineMs: %d}"
            .formatted(minIntervalMs, concurrency, deadlineMs);
    String retailers =
        "retailers:\n"
            + "  a.example: {adapter: enabled, "
            + values
            + "\n  b.example: {adapter: disabled, "
            + values
            + "\n  c.example: {adapter: disabled, "
            + values
            + "\n";
    String eans =
        """
        knownEans:
          a.example:
            - {ean: "4006381333931", url: "https://a.example/p/1"}
            - {ean: "96385074", url: "https://a.example/p/2"}
          b.example:
            - {ean: "4006381333931", url: "https://b.example/p/1"}
            - {ean: "96385074", url: "https://b.example/p/2"}
        """;
    return of(reg, retailers, eans);
  }

  /**
   * a.example and b.example (both Approved, enabled, the given {@code deadlineMs}) and c.example
   * (Proposed, disabled); known EANs as in {@link #mixed()}.
   */
  public static Registry twoEnabled(int deadlineMs) {
    String limits = "100 requests / 1 s, concurrency 4";
    String reg =
        register(
            row("a.example", "Approved 2026-11-20", limits),
            row("b.example", "Approved 2026-11-20", limits),
            row("c.example", "Proposed", limits));
    String values =
        "resolution: curated, minIntervalMs: 10, concurrency: 1, deadlineMs: %d}"
            .formatted(deadlineMs);
    String retailers =
        "retailers:\n"
            + "  a.example: {adapter: enabled, "
            + values
            + "\n  b.example: {adapter: enabled, "
            + values
            + "\n  c.example: {adapter: disabled, "
            + values
            + "\n";
    String eans =
        """
        knownEans:
          a.example:
            - {ean: "4006381333931", url: "https://a.example/p/1"}
            - {ean: "96385074", url: "https://a.example/p/2"}
          b.example:
            - {ean: "4006381333931", url: "https://b.example/p/1"}
            - {ean: "96385074", url: "https://b.example/p/2"}
        """;
    return of(reg, retailers, eans);
  }

  /**
   * A {@link RuntimeSwitch} over a temporary {@code runtime.yaml} with {@code fetching: allowed}
   * for each given domain (and nothing else).
   */
  public static RuntimeSwitch runtimeAllowing(Clock clock, String... domains) {
    StringBuilder yaml = new StringBuilder("retailers:\n");
    for (String domain : domains) {
      yaml.append("  ").append(domain).append(": {fetching: allowed}\n");
    }
    try {
      Path file = Files.createTempFile("runtime", ".yaml");
      file.toFile().deleteOnExit();
      Files.writeString(file, yaml.toString());
      return new RuntimeSwitch(file, clock);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /** A {@link BlockStore} over a fresh temporary directory (no {@code blocks.json} yet). */
  public static BlockStore blockStore(RuntimeSwitch runtime, Clock clock) {
    try {
      Path dir = Files.createTempDirectory("blocks");
      dir.toFile().deleteOnExit();
      return new BlockStore(dir.resolve("blocks.json"), runtime, clock, (retailer, reason) -> {});
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
