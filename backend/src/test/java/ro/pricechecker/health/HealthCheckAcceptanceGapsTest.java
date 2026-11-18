package ro.pricechecker.health;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ro.pricechecker.adapter.AdapterOutcome;
import ro.pricechecker.adapter.Availability;
import ro.pricechecker.adapter.PageFacts;
import ro.pricechecker.adapter.RetailerAdapter;
import ro.pricechecker.adapter.RetailerHttp;
import ro.pricechecker.adapter.UnavailableReason;
import ro.pricechecker.ean.Ean;
import ro.pricechecker.fetch.FetchClient;
import ro.pricechecker.fetch.FetchFailure;
import ro.pricechecker.fetch.PageStripper;
import ro.pricechecker.fetch.Transport;
import ro.pricechecker.fetch.UserAgent;
import ro.pricechecker.lookup.AdapterCatalog;
import ro.pricechecker.lookup.KnownEanLoad;
import ro.pricechecker.lookup.LookupLog;
import ro.pricechecker.lookup.LookupRow;
import ro.pricechecker.lookup.RowAvailability;
import ro.pricechecker.lookup.RowStatus;
import ro.pricechecker.registry.BlockStore;
import ro.pricechecker.registry.MutableClock;
import ro.pricechecker.registry.Registry;
import ro.pricechecker.registry.RuntimeSwitch;
import ro.pricechecker.registry.TestRegistries;

/**
 * Story 3.5 gaps found by Eufrosina (QA) after the dev step: only Approved, adapter-enabled
 * retailers are contacted even when {@code runtime.yaml} allows more (AC 3.5.1); unknown
 * availability and a missing URL are missing facts (AC 3.5.2) and fail the run with the stripped
 * page saved (AC 3.5.3). Round 2: a report with no PASSED check or a rejected runtime.yaml does not
 * pass, and a runner-blocked retailer fails with {@code blocked} set and nothing sent. Stub
 * transport and injected clock only (T-3, T-5).
 */
class HealthCheckAcceptanceGapsTest {

  private static final Instant NOW = Instant.parse("2026-11-18T10:00:00Z");
  private static final URI URL = URI.create("https://a.example/p/1");
  private static final String UNKNOWN_STOCK = "<html><p>" + "y".repeat(300) + "</p>unknown</html>";

  @TempDir Path dir;

  private final MutableClock clock = new MutableClock(NOW);
  private final List<URI> sent = new ArrayList<>();
  private Path reportDir;

  private static Registry registry() {
    return TestRegistries.approvedEnabledDisabledProposed();
  }

  /**
   * "unknown" pages are FOUND with unknown availability and no page URL (the row falls back to the
   * Known EAN URL, AD-10); others have every fact.
   */
  private static RetailerAdapter adapter(String id) {
    return new RetailerAdapter() {
      @Override
      public String retailerId() {
        return id;
      }

      @Override
      public AdapterOutcome fetchAndExtract(Ean ean, URI productUrl, RetailerHttp http) {
        String body;
        try {
          body = http.get(productUrl);
        } catch (FetchFailure f) {
          return f.outcome();
        } catch (IOException e) {
          return new AdapterOutcome.Unavailable(UnavailableReason.HTTP_ERROR);
        }
        if (body.contains("unknown")) {
          return new AdapterOutcome.Found(
              new PageFacts(1999L, "RON", Availability.UNKNOWN, null, ean.value()));
        }
        return new AdapterOutcome.Found(
            new PageFacts(1999L, "RON", Availability.IN_STOCK, productUrl, ean.value()));
      }
    };
  }

  private HealthCheck healthCheck(String firstPageBody) throws IOException {
    return healthCheck(
        firstPageBody,
        """
        retailers:
          a.example: {fetching: allowed}
          b.example: {fetching: allowed}
          c.example: {fetching: allowed}
        """);
  }

  private HealthCheck healthCheck(String firstPageBody, String runtimeYaml) throws IOException {
    Path runtimeFile = Files.writeString(dir.resolve("runtime.yaml"), runtimeYaml);
    reportDir = dir.resolve("report");
    Registry registry = registry();
    RuntimeSwitch runtime = new RuntimeSwitch(runtimeFile, clock);
    LookupLog log = new LookupLog(clock);
    BlockStore blocks = new BlockStore(dir.resolve("blocks.json"), runtime, clock, log::blocked);
    Transport transport =
        (url, userAgent, timeout) -> {
          sent.add(url);
          if (url.getPath().equals("/robots.txt")) {
            return new Transport.Response(200, Optional.empty(), "User-agent: *\nAllow: /\n");
          }
          String body = url.equals(URL) ? firstPageBody : "good";
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
        new AdapterCatalog(
            List.of(adapter("a.example"), adapter("b.example"), adapter("c.example")), registry);
    return new HealthCheck(
        registry,
        runtime,
        blocks,
        fetch,
        new KnownEanLoad(catalog, clock, new LookupLog(clock)),
        log,
        clock,
        reportDir);
  }

  private static LookupRow found(Long price, RowAvailability availability, URI url) {
    return new LookupRow(
        "a.example", "a.example", RowStatus.FOUND, price, availability, url, NOW, false);
  }

  @Test
  @Tag("AC-3.5.1")
  void ac_3_5_1_onlyApprovedEnabledRetailersAreContactedEvenWhenRuntimeAllowsMore()
      throws IOException {
    HealthCheck check = healthCheck("good");

    check.run();

    assertThat(sent).allMatch(u -> u.getHost().equals("a.example"));
    assertThat(check.report().checks())
        .extracting(CheckResult::retailer)
        .containsExactly("a.example", "a.example", "b.example", "b.example");
    assertThat(check.report().checks().subList(2, 4))
        .allSatisfy(c -> {
          assertThat(c.outcome()).isEqualTo(CheckResult.Outcome.SKIPPED);
          assertThat(c.reason()).isEqualTo("disabled");
          assertThat(c.status()).isEqualTo("DISABLED");
        });
    assertThat(check.getExitCode()).isZero();
  }

  @Test
  @Tag("AC-3.5.2")
  void ac_3_5_2_foundWithEveryFactMissesNothing() {
    assertThat(HealthCheck.missing(found(1999L, RowAvailability.IN_STOCK, URL))).isEmpty();
    assertThat(HealthCheck.missing(found(1999L, RowAvailability.OUT_OF_STOCK, URL))).isEmpty();
  }

  @Test
  @Tag("AC-3.5.2")
  void ac_3_5_2_unknownOrNullAvailabilityAndNullUrlAreMissingFacts() {
    assertThat(HealthCheck.missing(found(1999L, RowAvailability.UNKNOWN, URL)))
        .containsExactly("availability");
    assertThat(HealthCheck.missing(found(1999L, null, URL))).containsExactly("availability");
    assertThat(HealthCheck.missing(found(1999L, RowAvailability.IN_STOCK, null)))
        .containsExactly("url");
    assertThat(HealthCheck.missing(found(null, RowAvailability.UNKNOWN, null)))
        .containsExactly("price", "availability", "url");
  }

  @Test
  @Tag("AC-3.5.2")
  void ac_3_5_2_unavailableRowMissesEveryFact() {
    LookupRow row =
        new LookupRow(
            "a.example", "a.example", RowStatus.UNAVAILABLE, null, null, null, null, false);

    assertThat(HealthCheck.missing(row)).containsExactly("price", "availability", "url");
  }

  @Test
  @Tag("AC-3.5.3")
  void ac_3_5_3_unknownAvailabilityFailsWithTheStrippedPageSaved() throws IOException {
    HealthCheck check = healthCheck(UNKNOWN_STOCK);

    check.run();

    assertThat(check.getExitCode()).isEqualTo(1);
    CheckResult failed = check.report().checks().get(0);
    assertThat(failed.outcome()).isEqualTo(CheckResult.Outcome.FAILED);
    assertThat(failed.status()).isEqualTo("FOUND");
    assertThat(failed.missing()).containsExactly("availability");
    assertThat(failed.blocked()).isFalse();
    assertThat(Files.readString(reportDir.resolve(failed.page())))
        .isEqualTo(PageStripper.strip(UNKNOWN_STOCK))
        .doesNotContain("y".repeat(300));
    assertThat(check.report().checks().get(1).outcome()).isEqualTo(CheckResult.Outcome.PASSED);
  }

  private static CheckResult result(CheckResult.Outcome outcome) {
    return new CheckResult(
        "a.example", "5940000000001", outcome, null, "FOUND", List.of(), false, null);
  }

  @Test
  @Tag("AC-3.5.2")
  void ac_3_5_2_reportWithNoChecksDoesNotPass() {
    assertThat(new HealthReport(null, List.of()).passed()).isFalse();
  }

  @Test
  @Tag("AC-3.5.2")
  void ac_3_5_2_reportWithOnePassAndSkipsPasses() {
    HealthReport report =
        new HealthReport(
            null, List.of(result(CheckResult.Outcome.SKIPPED), result(CheckResult.Outcome.PASSED)));

    assertThat(report.passed()).isTrue();
  }

  @Test
  @Tag("AC-3.5.3")
  void ac_3_5_3_rejectedRuntimeConfigFailsEvenWithPassingChecks() {
    HealthReport report = new HealthReport("MISSING", List.of(result(CheckResult.Outcome.PASSED)));

    assertThat(report.passed()).isFalse();
  }

  @Test
  @Tag("AC-3.5.3")
  void ac_3_5_3_runnerBlockedRetailerFailsWithBlockedFlagAndSendsNothing() throws IOException {
    HealthCheck check =
        healthCheck(
            "good",
            """
            retailers:
              a.example: {fetching: allowed, runnerBlock: true}
            """);

    check.run();

    assertThat(sent).isEmpty();
    assertThat(check.getExitCode()).isEqualTo(1);
    assertThat(check.report().checks().subList(0, 2))
        .allSatisfy(c -> {
          assertThat(c.retailer()).isEqualTo("a.example");
          assertThat(c.outcome()).isEqualTo(CheckResult.Outcome.FAILED);
          assertThat(c.reason()).isEqualTo("blocked");
          assertThat(c.blocked()).isTrue();
          assertThat(c.missing()).containsExactly("price", "availability", "url");
          assertThat(c.page()).isNull();
        });
  }
}
