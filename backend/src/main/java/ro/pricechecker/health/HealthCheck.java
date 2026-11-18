package ro.pricechecker.health;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.ExitCodeGenerator;
import ro.pricechecker.fetch.FetchClient;
import ro.pricechecker.fetch.PageStripper;
import ro.pricechecker.lookup.KnownEanLoad;
import ro.pricechecker.lookup.LookupLog;
import ro.pricechecker.lookup.LookupRow;
import ro.pricechecker.lookup.RowAvailability;
import ro.pricechecker.lookup.RowStatus;
import ro.pricechecker.registry.BlockStore;
import ro.pricechecker.registry.KnownEan;
import ro.pricechecker.registry.Registry;
import ro.pricechecker.registry.RetailerConfig;
import ro.pricechecker.registry.RuntimeSwitch;

/**
 * The Health check (FR-8, UJ-4, story 3.5): loads every Known EAN of every Approved retailer once,
 * one after another in {@code retailers.yaml} × {@code known-eans.yaml} order, through {@link
 * KnownEanLoad} (no cache) and the shared {@link FetchClient} (C-12), so robots.txt, the Kill
 * switch, Blocks and {@code notBefore} apply unchanged. A retailer that is disabled, stopped or
 * paused by a future {@code notBefore} is {@code SKIPPED} with nothing sent; a check fails when the
 * row is not {@code FOUND} with price, known availability and URL, or the retailer is Blocked. Each
 * failing check with a page saves it {@link PageStripper#strip stripped} (S-7). The exit code is 0
 * only when the report passed.
 */
public final class HealthCheck implements CommandLineRunner, ExitCodeGenerator {

  static final String PAGES = "pages";

  private final Registry registry;
  private final RuntimeSwitch runtime;
  private final BlockStore blocks;
  private final FetchClient fetch;
  private final KnownEanLoad load;
  private final LookupLog log;
  private final Clock clock;
  private final Path reportDir;

  private volatile HealthReport report;

  public HealthCheck(
      Registry registry,
      RuntimeSwitch runtime,
      BlockStore blocks,
      FetchClient fetch,
      KnownEanLoad load,
      LookupLog log,
      Clock clock,
      Path reportDir) {
    this.registry = registry;
    this.runtime = runtime;
    this.blocks = blocks;
    this.fetch = fetch;
    this.load = load;
    this.log = log;
    this.clock = clock;
    this.reportDir = reportDir;
  }

  @Override
  public void run(String... args) throws IOException {
    report = check();
    report.write(reportDir);
  }

  /** Runs every check and saves the stripped page of each failure; writes no report. */
  HealthReport check() {
    Optional<RuntimeSwitch.Rejection> rejection = runtime.refresh();
    if (rejection.isPresent()) {
      log.runtimeConfigRejected(rejection.get());
      return new HealthReport(rejection.get().name(), List.of());
    }
    List<CheckResult> checks = new ArrayList<>();
    for (RetailerConfig config : registry.retailers()) {
      if (!registry.approvedDomains().contains(config.domain())) {
        continue;
      }
      for (KnownEan known : registry.knownEans()) {
        if (known.domain().equals(config.domain())) {
          CheckResult result = checkOne(config, known);
          load.log(result.retailer(), result.ean(), result.outcome());
          checks.add(result);
        }
      }
    }
    return new HealthReport(null, checks);
  }

  private CheckResult checkOne(RetailerConfig config, KnownEan known) {
    String id = config.domain();
    String ean = known.ean();
    if (!config.enabled() || !runtime.isAllowed(id)) {
      return skipped(id, ean, "disabled", RowStatus.DISABLED);
    }
    if (blocks.isBlocked(id)) {
      return blockedFailure(id, ean);
    }
    if (pausedNow(id)) {
      return skipped(id, ean, "notBefore", RowStatus.UNAVAILABLE);
    }
    AtomicReference<String> lastPage = new AtomicReference<>();
    FetchClient.BoundHttp http = fetch.bind(id);
    LookupRow row =
        load.load(
            id,
            ean,
            URI.create(known.url()),
            url -> {
              String body = http.get(url);
              lastPage.set(body);
              return body;
            });
    List<String> missing = missing(row);
    boolean blocked = blocks.isBlocked(id);
    if (missing.isEmpty() && !blocked) {
      return new CheckResult(
          id, ean, CheckResult.Outcome.PASSED, null, row.status().name(), missing, false, null);
    }
    CheckResult failed =
        new CheckResult(
            id, ean, CheckResult.Outcome.FAILED, null, row.status().name(), missing, blocked, null);
    String page = lastPage.get();
    return page == null ? failed : failed.withPage(savePage(id, ean, page));
  }

  private boolean pausedNow(String id) {
    Instant now = clock.instant();
    return blocks.notBefore(id).filter(now::isBefore).isPresent();
  }

  private static CheckResult skipped(String id, String ean, String reason, RowStatus status) {
    return new CheckResult(
        id, ean, CheckResult.Outcome.SKIPPED, reason, status.name(), List.of(), false, null);
  }

  private static CheckResult blockedFailure(String id, String ean) {
    return new CheckResult(
        id,
        ean,
        CheckResult.Outcome.FAILED,
        "blocked",
        RowStatus.UNAVAILABLE.name(),
        List.of("price", "availability", "url"),
        true,
        null);
  }

  /** The facts a passing check needs; a non-{@code FOUND} row misses all of them. */
  static List<String> missing(LookupRow row) {
    if (row.status() != RowStatus.FOUND) {
      return List.of("price", "availability", "url");
    }
    List<String> missing = new ArrayList<>();
    if (row.priceMinor() == null) {
      missing.add("price");
    }
    if (row.availability() == null || row.availability() == RowAvailability.UNKNOWN) {
      missing.add("availability");
    }
    if (row.url() == null) {
      missing.add("url");
    }
    return missing;
  }

  /** Saves the stripped page (S-7, T-2) and returns its path relative to the report directory. */
  private String savePage(String id, String ean, String body) {
    String relative = PAGES + "/" + id + "/" + ean + '.' + "html";
    Path file = reportDir.resolve(relative);
    try {
      Files.createDirectories(file.getParent());
      Files.writeString(file, PageStripper.strip(body), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException("cannot save health page", e);
    }
    return relative;
  }

  /** The finished report, or {@code null} before {@link #run} completed. */
  public HealthReport report() {
    return report;
  }

  /** 0 when the report passed; 1 otherwise, also when the run did not finish. */
  @Override
  public int getExitCode() {
    HealthReport done = report;
    return done != null && done.passed() ? 0 : 1;
  }
}
