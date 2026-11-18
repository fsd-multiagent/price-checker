package ro.pricechecker.adapter;

/** Why an adapter could not read a page (AD-7). */
public enum UnavailableReason {
  TIMEOUT,
  HTTP_ERROR,
  BLOCKED,
  UNPARSEABLE
}
