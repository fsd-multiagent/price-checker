package ro.pricechecker.ean;

/** Why an input is not a valid EAN, in the AD-13 check order. */
public enum EanError {
  EAN_EMPTY,
  EAN_NON_DIGIT,
  EAN_LENGTH,
  EAN_CHECK_DIGIT
}
