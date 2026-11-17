package ro.pricechecker.registry;

/** One curated EAN-to-product-URL entry of {@code config/known-eans.yaml} (AD-12). */
public record KnownEan(String domain, String ean, String url) {}
