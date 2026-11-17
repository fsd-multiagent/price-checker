package ro.pricechecker.extract;

import java.math.BigDecimal;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Turns a price into integer bani. Anything unparseable, zero, negative or more precise than bani
 * is {@code null}, never 0 (C-7). Never throws.
 */
public final class Prices {

  private static final Pattern NUMBER_TEXT = Pattern.compile("[0-9.,]+");
  private static final Pattern LEADING_GROUP = Pattern.compile("[0-9]{1,3}");
  private static final Pattern GROUP = Pattern.compile("[0-9]{3}");
  private static final Pattern DIGITS = Pattern.compile("[0-9]+");
  private static final Pattern WHITESPACE = Pattern.compile("[\\s\\u00A0\\u2007\\u2009\\u202F]+");
  private static final Pattern SPACE_LEADING = Pattern.compile("[1-9][0-9]{0,2}");
  private static final Pattern SPACE_LAST = Pattern.compile("[0-9]{3}(?:[.,][0-9]{1,2})?");
  private static final int MAX_LENGTH = 40;

  private Prices() {}

  /**
   * Parses price text such as {@code 1.299,99 lei}, {@code 1299.99} or {@code 1 299,99 RON}.
   *
   * <p>When both {@code .} and {@code ,} occur, the last one is the decimal mark. When only one
   * kind occurs once with 1 or 2 digits after it, it is the decimal mark; otherwise it groups
   * thousands, and every group after the first must have exactly 3 digits.
   */
  public static Long toMinor(String text) {
    if (text == null) {
      return null;
    }
    String s = stripCurrencyWord(WHITESPACE.matcher(text).replaceAll(" ").strip()).strip();
    if (!spaceGroupsValid(s)) {
      return null;
    }
    s = WHITESPACE.matcher(s).replaceAll("");
    if (s.isEmpty() || s.length() > MAX_LENGTH || !NUMBER_TEXT.matcher(s).matches()) {
      return null;
    }
    String plain = toPlainDecimal(s);
    return plain == null ? null : toMinor(new BigDecimal(plain));
  }

  /** Converts an amount in lei to bani; {@code null} when not above 0 or finer than 0.01. */
  public static Long toMinor(BigDecimal amount) {
    if (amount == null || amount.signum() <= 0) {
      return null;
    }
    BigDecimal exact = amount.stripTrailingZeros();
    if (exact.scale() > 2) {
      return null;
    }
    try {
      return exact.movePointRight(2).longValueExact();
    } catch (ArithmeticException e) {
      return null;
    }
  }

  private static String stripCurrencyWord(String s) {
    String lower = s.toLowerCase(Locale.ROOT);
    for (String word : new String[] {"lei", "ron"}) {
      if (lower.endsWith(word)) {
        s = s.substring(0, s.length() - word.length());
        lower = lower.substring(0, lower.length() - word.length());
        break;
      }
    }
    for (String word : new String[] {"lei", "ron"}) {
      if (lower.startsWith(word)) {
        return s.substring(word.length());
      }
    }
    return s;
  }

  /** Returns digits with an optional {@code .} decimal mark, or {@code null} when ambiguous. */
  private static String toPlainDecimal(String s) {
    int lastDot = s.lastIndexOf('.');
    int lastComma = s.lastIndexOf(',');
    if (lastDot < 0 && lastComma < 0) {
      return s;
    }
    if (lastDot >= 0 && lastComma >= 0) {
      int decimalAt = Math.max(lastDot, lastComma);
      char decimal = s.charAt(decimalAt);
      if (s.indexOf(decimal) != decimalAt) {
        return null;
      }
      return join(
          ungroup(s.substring(0, decimalAt), decimal == '.' ? ',' : '.'),
          s.substring(decimalAt + 1));
    }
    char mark = lastDot >= 0 ? '.' : ',';
    int at = s.indexOf(mark);
    int after = s.length() - at - 1;
    if (at == s.lastIndexOf(mark) && after >= 1 && after <= 2) {
      return join(s.substring(0, at), s.substring(at + 1));
    }
    return ungroup(s, mark);
  }

  /** Space-separated digits group thousands: 1-3 digits, then exactly 3 each (C-7). */
  private static boolean spaceGroupsValid(String s) {
    String[] tokens = s.split(" ");
    if (tokens.length == 1) {
      return true;
    }
    if (!SPACE_LEADING.matcher(tokens[0]).matches()) {
      return false;
    }
    for (int i = 1; i < tokens.length; i++) {
      Pattern p = i == tokens.length - 1 ? SPACE_LAST : GROUP;
      if (!p.matcher(tokens[i]).matches()) {
        return false;
      }
    }
    return true;
  }

  private static String join(String integer, String fraction) {
    if (integer == null
        || !DIGITS.matcher(integer).matches()
        || !DIGITS.matcher(fraction).matches()) {
      return null;
    }
    return integer + "." + fraction;
  }

  /** Removes the grouping mark after checking every group: 1-3 digits, then exactly 3 each. */
  private static String ungroup(String integer, char grouping) {
    if (integer.indexOf(grouping) < 0) {
      return integer;
    }
    String[] groups = integer.split(Pattern.quote(String.valueOf(grouping)), -1);
    if (!LEADING_GROUP.matcher(groups[0]).matches() || groups[0].startsWith("0")) {
      return null;
    }
    StringBuilder digits = new StringBuilder(groups[0]);
    for (int i = 1; i < groups.length; i++) {
      if (!GROUP.matcher(groups[i]).matches()) {
        return null;
      }
      digits.append(groups[i]);
    }
    return digits.toString();
  }
}
