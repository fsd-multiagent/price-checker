package ro.pricechecker.adapter;

import java.net.URI;
import ro.pricechecker.ean.Ean;

/**
 * One Spring bean per retailer (AD-7). Adapters handle no deadlines, cache or logging and read no
 * config.
 */
public interface RetailerAdapter {

  /** The register domain this adapter serves; must match a {@code retailers.yaml} id. */
  String retailerId();

  AdapterOutcome fetchAndExtract(Ean ean, URI productUrl, RetailerHttp http);
}
