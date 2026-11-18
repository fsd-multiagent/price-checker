package ro.pricechecker.lookup;

import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;
import ro.pricechecker.adapter.AdapterOutcome;
import ro.pricechecker.adapter.Availability;
import ro.pricechecker.adapter.PageFacts;
import ro.pricechecker.adapter.RetailerAdapter;
import ro.pricechecker.adapter.RetailerHttp;
import ro.pricechecker.ean.Ean;
import ro.pricechecker.registry.KnownEan;
import ro.pricechecker.registry.Registry;
import ro.pricechecker.registry.RetailerConfig;
import ro.pricechecker.registry.RuntimeSwitch;

/**
 * Builds one row per Approved retailer in the config (AD-8), then orders them and marks the Lowest
 * price (AD-4). A retailer stopped by the Kill switch ({@link RuntimeSwitch}, AD-9) gets a {@code
 * DISABLED} row. Skeleton: sequential, no cache, Block or deadlines (stories 2.x).
 */
@Service
public class LookupService {

  private final Registry registry;
  private final AdapterCatalog adapters;
  private final Optional<RetailerHttp> http;
  private final Clock clock;
  private final RuntimeSwitch runtime;
  private final LookupLog log;

  public LookupService(
      Registry registry,
      AdapterCatalog adapters,
      Optional<RetailerHttp> http,
      Clock clock,
      RuntimeSwitch runtime,
      LookupLog log) {
    this.registry = registry;
    this.adapters = adapters;
    this.http = http;
    this.clock = clock;
    this.runtime = runtime;
    this.log = log;
  }

  public LookupResult lookup(Ean ean) {
    runtime.refresh().ifPresent(log::runtimeConfigRejected);
    List<LookupRow> rows = new ArrayList<>();
    for (RetailerConfig config : registry.retailers()) {
      if (registry.approvedDomains().contains(config.domain())) {
        rows.add(safeRow(config, ean));
      }
    }
    return new LookupResult(ean.value(), RowOrder.arrange(rows));
  }

  /** A failure while building one row stays in that row (AC-5.4): it never fails the Lookup. */
  private LookupRow safeRow(RetailerConfig config, Ean ean) {
    try {
      return row(config, ean);
    } catch (Exception e) {
      return LookupRow.withoutFacts(config.domain(), config.domain(), RowStatus.UNAVAILABLE);
    }
  }

  private LookupRow row(RetailerConfig config, Ean ean) {
    String id = config.domain();
    // AD-10: adapter disabled or fetching stopped comes first, before any other check or call.
    if (!config.enabled() || !runtime.isAllowed(id)) {
      return LookupRow.withoutFacts(id, id, RowStatus.DISABLED);
    }
    Optional<URI> url = knownUrl(id, ean);
    if (url.isEmpty()) {
      return LookupRow.withoutFacts(id, id, RowStatus.NOT_FOUND);
    }
    Optional<RetailerAdapter> adapter = adapters.find(id);
    if (http.isEmpty() || adapter.isEmpty()) {
      return LookupRow.withoutFacts(id, id, RowStatus.UNAVAILABLE);
    }
    AdapterOutcome outcome;
    try {
      outcome = adapter.get().fetchAndExtract(ean, url.get(), http.get());
    } catch (RuntimeException e) {
      return LookupRow.withoutFacts(id, id, RowStatus.UNAVAILABLE);
    }
    return switch (outcome) {
      case AdapterOutcome.Found found -> found(id, url.get(), found.facts());
      case AdapterOutcome.NotFound n -> LookupRow.withoutFacts(id, id, RowStatus.NOT_FOUND);
      case AdapterOutcome.Unavailable u -> LookupRow.withoutFacts(id, id, RowStatus.UNAVAILABLE);
      case null -> LookupRow.withoutFacts(id, id, RowStatus.UNAVAILABLE);
    };
  }

  private LookupRow found(String id, URI productUrl, PageFacts facts) {
    URI url = facts.url() != null ? facts.url() : productUrl;
    return new LookupRow(
        id,
        id,
        RowStatus.FOUND,
        facts.priceMinor(),
        availability(facts.availability()),
        url,
        Instant.now(clock),
        false);
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
