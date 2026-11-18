package ro.pricechecker.ean;

/**
 * A valid EAN-8 or EAN-13 (AD-13). Instances exist only for valid input: the compact constructor
 * rejects anything that is not already a trimmed, valid EAN.
 */
public record Ean(String value) {

  public Ean {
    EanError error = check(value);
    if (error != null) {
      throw new InvalidEanException(error);
    }
  }

  /**
   * Trims the input with the ECMAScript {@code String.prototype.trim()} whitespace set, then
   * applies the AD-13 checks in order: empty, non-digit (ASCII {@code 0-9} only), length, check
   * digit.
   *
   * @throws InvalidEanException with the first failing rule's code
   */
  public static Ean parse(String raw) {
    return new Ean(raw == null ? "" : trim(raw));
  }

  private static EanError check(String s) {
    if (s == null || s.isEmpty()) {
      return EanError.EAN_EMPTY;
    }
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      if (c < '0' || c > '9') {
        return EanError.EAN_NON_DIGIT;
      }
    }
    if (s.length() != 8 && s.length() != 13) {
      return EanError.EAN_LENGTH;
    }
    return checkDigitOk(s) ? null : EanError.EAN_CHECK_DIGIT;
  }

  /** GS1 check digit: weights 3 and 1 alternating from the right, excluding the check digit. */
  private static boolean checkDigitOk(String digits) {
    int n = digits.length();
    int sum = 0;
    for (int i = 0; i < n - 1; i++) {
      int d = digits.charAt(n - 2 - i) - '0';
      sum += (i % 2 == 0) ? 3 * d : d;
    }
    return (10 - sum % 10) % 10 == digits.charAt(n - 1) - '0';
  }

  private static String trim(String s) {
    int start = 0;
    int end = s.length();
    while (start < end && isJsWhitespace(s.charAt(start))) {
      start++;
    }
    while (end > start && isJsWhitespace(s.charAt(end - 1))) {
      end--;
    }
    return s.substring(start, end);
  }

  /** ECMAScript WhiteSpace and LineTerminator: TAB, LF, VT, FF, CR, Zs, U+2028, U+2029, U+FEFF. */
  static boolean isJsWhitespace(char c) {
    return switch (c) {
      case '\t', '\n', (char) 0x0B, '\f', '\r', (char) 0x2028, (char) 0x2029, (char) 0xFEFF -> true;
      default -> Character.getType(c) == Character.SPACE_SEPARATOR;
    };
  }
}
