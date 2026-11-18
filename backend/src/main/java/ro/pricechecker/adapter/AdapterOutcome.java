package ro.pricechecker.adapter;

/** What an adapter returns for one product page (AD-7). */
public sealed interface AdapterOutcome {

  /** The page was read. */
  record Found(PageFacts facts) implements AdapterOutcome {}

  /** The retailer does not list the product. */
  record NotFound() implements AdapterOutcome {}

  /** The page could not be read. */
  record Unavailable(UnavailableReason reason) implements AdapterOutcome {}
}
