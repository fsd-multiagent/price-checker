package ro.pricechecker.lookup;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.springframework.stereotype.Service;
import ro.pricechecker.adapter.AdapterOutcome;
import ro.pricechecker.adapter.Availability;
import ro.pricechecker.adapter.PageFacts;
import ro.pricechecker.adapter.RetailerAdapter;
import ro.pricechecker.adapter.RetailerHttp;
import ro.pricechecker.cache.ResultCache;
import ro.pricechecker.ean.Ean;
import ro.pricechecker.registry.BlockStore;
import ro.pricechecker.registry.KnownEan;
import ro.pricechecker.registry.Registry;
import ro.pricechecker.registry.RetailerConfig;
import ro.pricechecker.registry.RuntimeSwitch;

/**
 * Builds one row per Approved retailer in the config (AD-8), then orders them and marks the Lowest
 * price (AD-4).
 *
 * <p>Per retailer, in AD-10 order: adapter disabled or fetching stopped ({@link RuntimeSwitch},
 * AD-9) → {@code DISABLED}; active Block ({@link BlockStore}) → {@code UNAVAILABLE}; EAN not in
 * {@code known-eans.yaml} → {@code NOT_FOUND} with no request; then the cache; then a load. Every
 * load runs on its own virtual thread through the {@link ResultCache}, so retailers are queried at
 * once (FR-2); the Lookup waits for each row only until its {@code deadlineMs} (NFR-1), and a row
 * past its deadline is {@code UNAVAILABLE} while the shared load goes on. A failure stays in its
 * row (AD-5). Each row is logged once (AC-3.6).
 */
@Service
public class LookupService {

  /** Used when a retailer has no {@code deadlineMs} (AC-2.3). */
  static final long DEFAULT_DEADLINE_MS = 4000;

  /** The only currency whose price is shown (AC-N2.1); anything else is FOUND without price. */
  private static final String SHOWN_CURRENCY = "RON";

  private final Registry registry;
  private final AdapterCatalog adapters;
  private final Optional<RetailerHttp> http;
  private final Clock clock;
  private final RuntimeSwitch runtime;
  private final BlockStore blocks;
  private final ResultCache<RetailerEan, Verdict> cache;
  private final LookupLog log;

  public LookupService(
      Registry registry,
      AdapterCatalog adapters,
      Optional<RetailerHttp> http,
      Clock clock,
      RuntimeSwitch runtime,
      BlockStore blocks,
      ResultCache<RetailerEan, Verdict> cache,
      LookupLog log) {
    this.registry = registry;
    this.adapters = adapters;
    this.http = http;
    this.clock = clock;
    this.runtime = runtime;
    this.blocks = blocks;
    this.cache = cache;
    this.log = log;
  }

  public LookupResult lookup(Ean ean) {
    runtime.refresh().ifPresent(log::runtimeConfigRejected);
    Instant start = clock.instant();
    List<Pending> pending = new ArrayList<>();
    for (RetailerConfig config : registry.retailers()) {
      if (registry.approvedDomains().contains(config.domain())) {
        pending.add(start(config, ean));
      }
    }
    List<LookupRow> rows = new ArrayList<>();
    for (Pending p : pending) {
      LookupRow row = finish(p, start);
      log.row(p.config().domain(), ean, row.status(), elapsedMs(start));
      rows.add(row);
    }
    return new LookupResult(ean.value(), RowOrder.arrange(rows));
  }

  /** A row that is either decided already or waits for a (shared) load. */
  private record Pending(
      RetailerConfig config, LookupRow decided, CompletableFuture<Verdict> load) {}

  /** Applies the AD-10 checks and starts the load, if any; never throws (AD-5). */
  private Pending start(RetailerConfig config, Ean ean) {
    String id = config.domain();
    try {
      if (!config.enabled() || !runtime.isAllowed(id)) {
        return decided(config, RowStatus.DISABLED);
      }
      if (blocks.isBlocked(id)) {
        return decided(config, RowStatus.UNAVAILABLE);
      }
      Optional<URI> url = knownUrl(id, ean);
      if (url.isEmpty()) {
        return decided(config, RowStatus.NOT_FOUND);
      }
      Optional<RetailerAdapter> adapter = adapters.find(id);
      if (http.isEmpty() || adapter.isEmpty()) {
        return decided(config, RowStatus.UNAVAILABLE);
      }
      CompletableFuture<Verdict> load =
          cache.get(
              new RetailerEan(id, ean.value()),
              key -> load(adapter.get(), http.get(), ean, url.get()));
      return new Pending(config, null, load);
    } catch (Exception e) {
      return decided(config, RowStatus.UNAVAILABLE);
    }
  }

  private static Pending decided(RetailerConfig config, RowStatus status) {
    String id = config.domain();
    return new Pending(config, LookupRow.withoutFacts(id, id, status), null);
  }

  /** Waits for the row's load until its deadline; never cancels the shared load. */
  private LookupRow finish(Pending p, Instant start) {
    if (p.decided() != null) {
      return p.decided();
    }
    String id = p.config().domain();
    long deadline = p.config().deadlineMs() != null ? p.config().deadlineMs() : DEFAULT_DEADLINE_MS;
    long remaining = Math.max(0, deadline - elapsedMs(start));
    try {
      Verdict verdict = p.load().get(remaining, TimeUnit.MILLISECONDS);
      if (verdict != null) {
        return verdict.row(id, id);
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    } catch (ExecutionException | TimeoutException | RuntimeException e) {
      // AD-5: the row is UNAVAILABLE; the Lookup goes on.
    }
    return LookupRow.withoutFacts(id, id, RowStatus.UNAVAILABLE);
  }

  /**
   * Runs one adapter call and normalizes it (AC-N2.3, AC-3.5, AC-N2.1). Returns {@code null} for
   * anything unavailable, so the cache keeps nothing for it.
   */
  private Verdict load(RetailerAdapter adapter, RetailerHttp http, Ean ean, URI productUrl) {
    AdapterOutcome outcome;
    try {
      outcome = adapter.fetchAndExtract(ean, productUrl, http);
    } catch (RuntimeException e) {
      return null;
    }
    Instant fetchedAt = clock.instant();
    return switch (outcome) {
      case AdapterOutcome.Found found -> found(ean, productUrl, found.facts(), fetchedAt);
      case AdapterOutcome.NotFound n -> Verdict.notFound(fetchedAt);
      case AdapterOutcome.Unavailable u -> null;
      case null -> null;
    };
  }

  private static Verdict found(Ean ean, URI productUrl, PageFacts facts, Instant fetchedAt) {
    if (facts == null) {
      return null;
    }
    if (facts.eanOnPage() != null && !sameEan(facts.eanOnPage(), ean.value())) {
      return Verdict.notFound(fetchedAt);
    }
    Long price = SHOWN_CURRENCY.equals(facts.currency()) ? facts.priceMinor() : null;
    URI url = facts.url() != null ? facts.url() : productUrl;
    return new Verdict(RowStatus.FOUND, price, availability(facts.availability()), url, fetchedAt);
  }

  /** A page may show the code as a GTIN-14 with leading zeros; only the digits after them count. */
  private static boolean sameEan(String onPage, String requested) {
    return stripLeadingZeros(onPage.strip()).equals(stripLeadingZeros(requested));
  }

  private static String stripLeadingZeros(String value) {
    int i = 0;
    while (i < value.length() - 1 && value.charAt(i) == '0') {
      i++;
    }
    return value.substring(i);
  }

  private long elapsedMs(Instant start) {
    return Duration.between(start, clock.instant()).toMillis();
  }

  private static RowAvailability availability(Availability value) {
    if (value == null) {
      return RowAvailability.UNKNOWN;
    }
    return switch (value) {
      case IN_STOCK -> RowAvailability.IN_STOCK;
      case OUT_OF_STOCK -> RowAvailability.OUT_OF_STOCK;
      case UNKNOWN -> RowAvailability.UNKNOWN;
    };
  }

  private Optional<URI> knownUrl(String domain, Ean ean) {
    for (KnownEan known : registry.knownEans()) {
      if (known.domain().equals(domain) && known.ean().equals(ean.value())) {
        return Optional.of(URI.create(known.url()));
      }
    }
    return Optional.empty();
  }
}
