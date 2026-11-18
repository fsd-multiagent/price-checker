package ro.pricechecker.health;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import ro.pricechecker.fetch.FetchClient;
import ro.pricechecker.fetch.Transport;
import ro.pricechecker.fetch.UserAgent;
import ro.pricechecker.lookup.AdapterCatalog;
import ro.pricechecker.lookup.KnownEanLoad;
import ro.pricechecker.lookup.LookupLog;
import ro.pricechecker.registry.BlockStore;
import ro.pricechecker.registry.MutableClock;
import ro.pricechecker.registry.Registry;
import ro.pricechecker.registry.RuntimeSwitch;
import ro.pricechecker.registry.TestRegistries;
import ro.pricechecker.retailer.celro.CelroAdapter;
import ro.pricechecker.retailer.evomagro.EvomagAdapter;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * AC 4.2.2: the Health check, with the real cel.ro and evomag.ro Adapters, over a stub transport
 * that serves the saved fixture pages. The register here is an in-memory test copy with both
 * retailers Approved and enabled; nothing contacts a retailer (T-3), and the clock and sleeper are
 * injected (T-5). Only the altered-page test reads {@code layout-changed.html} (AC 4.2.3).
 */
class LayoutChangeHealthCheckTest {

  private static final Instant NOW = Instant.parse("2026-11-18T10:00:00Z");
  private static final String ALLOW_ALL = "User-agent: *\nAllow: /\n";
  private static final String SECOND_EAN = TestRegistries.EAN_A;

  /**
   * Retailer id to the GTIN its {@code product-jsonld.html} and {@code layout-changed.html} hold.
   */
  private static final Map<String, String> FIXTURE_EAN =
      Map.of("cel.ro", "5941234567899", "evomag.ro", "5901234123457");

  @TempDir Path dir;

  private final MutableClock clock = new MutableClock(NOW);

  private static String fixture(String retailerId, String name) {
    try {
      return Files.readString(Path.of("..", "fixtures", retailerId, name));
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static String registerRow(String domain) {
    return "| Shop | `"
        + domain
        + "` | Approved 2026-11-01 | 100 requests / 1 s, concurrency 4 | n |";
  }

  /** cel.ro and evomag.ro, both Approved and enabled; two Known EANs each (fixture GTIN first). */
  private static Registry registry() {
    String register =
        "# Source register\n\n## Retailers\n\n"
            + "| Retailer | Domain | Status | Limits | Notes |\n|---|---|---|---|---|\n"
            + registerRow("cel.ro")
            + "\n"
            + registerRow("evomag.ro")
            + "\n";
    String values = "resolution: curated, minIntervalMs: 10, concurrency: 1, deadlineMs: 4000}";
    String retailers =
        "retailers:\n"
            + "  cel.ro: {adapter: enabled, "
            + values
            + "\n  evomag.ro: {adapter: enabled, "
            + values
            + "\n";
    StringBuilder eans = new StringBuilder("knownEans:\n");
    for (String id : List.of("cel.ro", "evomag.ro")) {
      eans.append("  ").append(id).append(":\n");
      eans.append("    - {ean: \"")
          .append(FIXTURE_EAN.get(id))
          .append("\", url: \"https://www.")
          .append(id)
          .append("/p/1\"}\n");
      eans.append("    - {ean: \"")
          .append(SECOND_EAN)
          .append("\", url: \"https://www.")
          .append(id)
          .append("/p/2\"}\n");
    }
    return TestRegistries.of(register, retailers, eans.toString());
  }

  /**
   * Builds the Health check. {@code /p/1} of {@code alteredRetailer} gets {@code
   * layout-changed.html}; every other page is {@code product-jsonld.html}, with the fixture GTIN
   * replaced by the requested EAN on {@code /p/2} (a differing {@code eanOnPage} is NOT_FOUND).
   */
  private HealthCheck healthCheck(String alteredRetailer) throws IOException {
    Path runtimeFile = dir.resolve("runtime.yaml");
    Files.writeString(
        runtimeFile,
        "retailers:\n  cel.ro: {fetching: allowed}\n  evomag.ro: {fetching: allowed}\n");
    Registry registry = registry();
    RuntimeSwitch runtime = new RuntimeSwitch(runtimeFile, clock);
    LookupLog log = new LookupLog(clock);
    BlockStore blocks = new BlockStore(dir.resolve("blocks.json"), runtime, clock, log::blocked);
    Transport transport =
        (url, userAgent, timeout) -> {
          if (url.getPath().equals("/robots.txt")) {
            return new Transport.Response(200, Optional.empty(), ALLOW_ALL);
          }
          String id = url.getHost().replaceFirst("^www\\.", "");
          String body;
          if (url.getPath().equals("/p/1")) {
            body =
                fixture(
                    id, id.equals(alteredRetailer) ? "layout-changed.html" : "product-jsonld.html");
          } else {
            body = fixture(id, "product-jsonld.html").replace(FIXTURE_EAN.get(id), SECOND_EAN);
          }
          return new Transport.Response(200, Optional.empty(), body);
        };
    FetchClient fetch =
        new FetchClient(
            registry,
            runtime,
            blocks,
            transport,
            clock,
            clock::advance,
            UserAgent.build("1.0", "https://contact.example/about"),
            new FetchClient.Pace(2, 1));
    AdapterCatalog catalog =
        new AdapterCatalog(List.of(new CelroAdapter(), new EvomagAdapter()), registry);
    return new HealthCheck(
        registry,
        runtime,
        blocks,
        fetch,
        new KnownEanLoad(catalog, clock, new LookupLog(clock)),
        log,
        clock,
        dir.resolve("report"));
  }

  private JsonNode report() throws IOException {
    return JsonMapper.builder()
        .build()
        .readTree(Files.readString(dir.resolve("report").resolve("report.json")));
  }

  @Test
  @Tag("AC-4.2.2")
  void ac_4_2_2_normalPagesPassAndExitZero() throws IOException {
    HealthCheck check = healthCheck(null);

    check.run();

    assertThat(check.getExitCode()).isZero();
    assertThat(check.report().checks())
        .hasSize(4)
        .allSatisfy(c -> {
          assertThat(c.outcome()).isEqualTo(CheckResult.Outcome.PASSED);
          assertThat(c.missing()).isEmpty();
        });
    assertThat(report().get("passed").asBoolean()).isTrue();
  }

  /** One case per retailer, so each gets its own {@code @TempDir} and clock (T-5). */
  @ParameterizedTest
  @ValueSource(strings = {"cel.ro", "evomag.ro"})
  @Tag("AC-4.2.2")
  @Tag("AC-4.2.3")
  void ac_4_2_2_layoutChangedPageFailsNamingRetailerEanAndPrice(String retailer)
      throws IOException {
    HealthCheck check = healthCheck(retailer);
    String ean = FIXTURE_EAN.get(retailer);

    check.run();

    assertThat(check.getExitCode()).isEqualTo(1);
    JsonNode report = report();
    assertThat(report.get("passed").asBoolean()).isFalse();
    JsonNode checks = report.get("checks");
    assertThat(checks).hasSize(4);
    for (JsonNode c : checks) {
      boolean altered =
          c.get("retailer").asString().equals(retailer) && c.get("ean").asString().equals(ean);
      if (altered) {
        assertThat(c.get("outcome").asString()).isEqualTo("FAILED");
        assertThat(c.get("status").asString()).isEqualTo("FOUND");
        assertThat(c.get("missing")).hasSize(1);
        assertThat(c.get("missing").get(0).asString()).isEqualTo("price");
        assertThat(c.get("blocked").asBoolean()).isFalse();
      } else {
        assertThat(c.get("outcome").asString()).as(c.toString()).isEqualTo("PASSED");
      }
    }
    assertThat(checks)
        .filteredOn(c -> c.get("outcome").asString().equals("FAILED"))
        .singleElement()
        .satisfies(c -> {
          assertThat(c.get("retailer").asString()).isEqualTo(retailer);
          assertThat(c.get("ean").asString()).isEqualTo(ean);
        });
  }
}
