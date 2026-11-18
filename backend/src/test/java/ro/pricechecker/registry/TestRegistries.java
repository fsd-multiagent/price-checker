package ro.pricechecker.registry;

import static ro.pricechecker.registry.SourceRegisterTest.register;
import static ro.pricechecker.registry.SourceRegisterTest.row;

/** Test-only access to the package-private {@link Registry} constructor. */
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
}
