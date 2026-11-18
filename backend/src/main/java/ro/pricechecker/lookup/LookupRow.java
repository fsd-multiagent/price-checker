package ro.pricechecker.lookup;

import java.net.URI;
import java.time.Instant;

/** One retailer's result row. Non-FOUND rows carry only id, name and status (AD-5). */
public record LookupRow(
    String retailerId,
    String retailerName,
    RowStatus status,
    Long priceMinor,
    RowAvailability availability,
    URI url,
    Instant fetchedAt,
    boolean lowest) {

  static LookupRow withoutFacts(String id, String name, RowStatus status) {
    return new LookupRow(id, name, status, null, null, null, null, false);
  }

  LookupRow withLowest(boolean value) {
    return new LookupRow(
        retailerId, retailerName, status, priceMinor, availability, url, fetchedAt, value);
  }
}
