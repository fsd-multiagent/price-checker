package ro.pricechecker.lookup;

/** The Lookup cache key (FR-6): one retailer and one validated EAN. */
public record RetailerEan(String retailerId, String ean) {}
