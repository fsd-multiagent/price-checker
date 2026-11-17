package ro.pricechecker.extract;

import java.util.regex.Pattern;

/**
 * The facts one extraction step found on a product page (S-7): no name, description, image or URL.
 *
 * @param priceMinor price in bani, always above 0, or {@code null} when missing or unparseable
 *     (C-7)
 * @param currency three upper-case letters, or {@code null}
 * @param availability never {@code null}; {@link Availability#UNKNOWN} when not stated
 * @param gtin 8 to 14 digits, or {@code null}
 */
public record ExtractedFacts(
    Long priceMinor, String currency, Availability availability, String gtin) {

  private static final Pattern CURRENCY = Pattern.compile("[A-Z]{3}");
  private static final Pattern GTIN = Pattern.compile("[0-9]{8,14}");

  /** Stock state as the page states it. */
  public enum Availability {
    IN_STOCK,
    OUT_OF_STOCK,
    UNKNOWN
  }

  /** Normalizes invalid values to {@code null} (or {@code UNKNOWN}) instead of keeping them. */
  public ExtractedFacts {
    if (priceMinor != null && priceMinor <= 0) {
      priceMinor = null;
    }
    currency = currency == null ? null : currency.strip();
    if (currency != null && !CURRENCY.matcher(currency).matches()) {
      currency = null;
    }
    gtin = gtin == null ? null : gtin.strip();
    if (gtin != null && !GTIN.matcher(gtin).matches()) {
      gtin = null;
    }
    if (availability == null) {
      availability = Availability.UNKNOWN;
    }
  }
}
