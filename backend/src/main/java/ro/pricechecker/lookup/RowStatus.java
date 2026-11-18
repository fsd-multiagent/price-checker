package ro.pricechecker.lookup;

/** Row status as the API sees it (AD-5); kept here because api may not import adapter. */
public enum RowStatus {
  FOUND,
  NOT_FOUND,
  UNAVAILABLE,
  DISABLED
}
