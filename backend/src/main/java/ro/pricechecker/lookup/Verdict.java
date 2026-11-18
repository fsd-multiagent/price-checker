package ro.pricechecker.lookup;

import java.net.URI;
import java.time.Instant;

/**
 * A normalized adapter result, and the Lookup cache value (FR-6, S-7, AC-3.6): only status, price,
 * availability, URL and the time it was fetched; never page text, name or image. Only {@code FOUND}
 * and {@code NOT_FOUND} exist as verdicts; an unavailable load has none, so it is never cached.
 */
public record Verdict(
    RowStatus status, Long priceMinor, RowAvailability availability, URI url, Instant fetchedAt) {

  public Verdict {
    if (status != RowStatus.FOUND && status != RowStatus.NOT_FOUND) {
      throw new IllegalArgumentException("only FOUND or NOT_FOUND is a verdict");
    }
  }

  static Verdict notFound(Instant fetchedAt) {
    return new Verdict(RowStatus.NOT_FOUND, null, null, null, fetchedAt);
  }

  LookupRow row(String id, String name) {
    if (status == RowStatus.NOT_FOUND) {
      return LookupRow.withoutFacts(id, name, RowStatus.NOT_FOUND);
    }
    return new LookupRow(id, name, status, priceMinor, availability, url, fetchedAt, false);
  }
}
