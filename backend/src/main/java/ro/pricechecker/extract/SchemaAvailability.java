package ro.pricechecker.extract;

import java.util.Locale;
import java.util.Set;
import ro.pricechecker.extract.ExtractedFacts.Availability;

/**
 * Maps availability text to {@link Availability}: a schema.org URL or term (matched on the text
 * after the last {@code /} or {@code :}) or an Open Graph word such as {@code in stock}.
 */
final class SchemaAvailability {

  private static final Set<String> IN_STOCK =
      Set.of("instock", "limitedavailability", "onlineonly");
  private static final Set<String> OUT_OF_STOCK =
      Set.of("outofstock", "soldout", "discontinued", "oos");

  private SchemaAvailability() {}

  static Availability of(String text) {
    if (text == null) {
      return Availability.UNKNOWN;
    }
    String term = text.strip();
    term = term.substring(Math.max(term.lastIndexOf('/'), term.lastIndexOf(':')) + 1);
    term = term.toLowerCase(Locale.ROOT).replaceAll("[^a-z]", "");
    if (IN_STOCK.contains(term)) {
      return Availability.IN_STOCK;
    }
    if (OUT_OF_STOCK.contains(term)) {
      return Availability.OUT_OF_STOCK;
    }
    return Availability.UNKNOWN;
  }
}
