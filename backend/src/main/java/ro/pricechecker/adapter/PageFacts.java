package ro.pricechecker.adapter;

import java.net.URI;

/**
 * The only facts an adapter keeps from a page (AD-7, S-7): never name, description or image. An
 * unparseable price is {@code null}, never 0 (C-7).
 */
public record PageFacts(
    Long priceMinor, String currency, Availability availability, URI url, String eanOnPage) {}
