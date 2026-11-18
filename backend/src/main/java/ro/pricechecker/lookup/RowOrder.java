package ro.pricechecker.lookup;

import java.util.Comparator;
import java.util.List;

/** Row order and Lowest-price marking, decided once on the server (AD-4). */
final class RowOrder {

  private static final Comparator<LookupRow> ORDER =
      Comparator.comparingInt(RowOrder::rank)
          .thenComparing(r -> r.priceMinor() == null ? 0L : r.priceMinor())
          .thenComparing(LookupRow::retailerName);

  private RowOrder() {}

  private static boolean hasPrice(LookupRow row) {
    return row.status() == RowStatus.FOUND && row.priceMinor() != null;
  }

  private static boolean eligible(LookupRow row) {
    return hasPrice(row) && row.availability() != RowAvailability.OUT_OF_STOCK;
  }

  private static int rank(LookupRow row) {
    return switch (row.status()) {
      case FOUND -> hasPrice(row) ? 0 : 1;
      case NOT_FOUND -> 2;
      case UNAVAILABLE -> 3;
      case DISABLED -> 4;
    };
  }

  /**
   * Returns the rows in UX order with {@code lowest} set on every row tied for the lowest price.
   */
  static List<LookupRow> arrange(List<LookupRow> rows) {
    long min =
        rows.stream()
            .filter(RowOrder::eligible)
            .mapToLong(LookupRow::priceMinor)
            .min()
            .orElse(Long.MAX_VALUE);
    return rows.stream()
        .map(r -> r.withLowest(eligible(r) && r.priceMinor() == min))
        .sorted(ORDER)
        .toList();
  }
}
