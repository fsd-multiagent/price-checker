package ro.pricechecker.lookup;

import java.net.URI;
import java.time.Clock;
import java.util.Optional;
import org.springframework.stereotype.Component;
import ro.pricechecker.adapter.AdapterOutcome;
import ro.pricechecker.adapter.RetailerAdapter;
import ro.pricechecker.ean.Ean;

/**
 * One load of a Known EAN for the Health check (FR-8, story 3.5): the same adapter call and AD-10
 * normalization as a Lookup, but never through the cache, so every call sends. It decides nothing
 * about Kill switch, Blocks or {@code notBefore}; the caller and the fetch client do.
 */
@Component
public class KnownEanLoad {

  private final AdapterCatalog adapters;
  private final Clock clock;
  private final LookupLog log;

  public KnownEanLoad(AdapterCatalog adapters, Clock clock, LookupLog log) {
    this.adapters = adapters;
    this.clock = clock;
    this.log = log;
  }

  /**
   * Logs one Health check result through {@link LookupLog} (ADR 0017); an {@code ean} that is not a
   * valid EAN is left out of the line, so no free text reaches it.
   */
  public void log(String retailerId, String ean, Enum<?> outcome) {
    Ean valid;
    try {
      valid = Ean.parse(ean);
    } catch (RuntimeException e) {
      valid = null;
    }
    log.healthCheck(retailerId, valid, outcome);
  }

  /**
   * Loads {@code ean} from {@code url} of {@code retailerId}. Returns an {@code UNAVAILABLE} row
   * when there is no verdict (no adapter, invalid EAN, an unavailable outcome or any exception);
   * never throws (AD-5).
   */
  public LookupRow load(String retailerId, String ean, URI url, PageSource pages) {
    try {
      Optional<RetailerAdapter> adapter = adapters.find(retailerId);
      if (adapter.isEmpty()) {
        return LookupRow.withoutFacts(retailerId, retailerId, RowStatus.UNAVAILABLE);
      }
      Ean parsed = Ean.parse(ean);
      AdapterOutcome outcome = adapter.get().fetchAndExtract(parsed, url, pages::get);
      Verdict verdict = LookupService.normalize(outcome, parsed, url, clock.instant());
      if (verdict != null) {
        return verdict.row(retailerId, retailerId);
      }
    } catch (Exception e) {
      // AD-5: no verdict; the row is UNAVAILABLE.
    }
    return LookupRow.withoutFacts(retailerId, retailerId, RowStatus.UNAVAILABLE);
  }
}
