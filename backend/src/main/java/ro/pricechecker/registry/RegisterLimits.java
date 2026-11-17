package ro.pricechecker.registry;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Rate limit from the register's {@code Limits} column (AC-N3.3): at most {@code concurrency}
 * requests in flight, and at least {@code intervalMs} between two requests.
 */
public record RegisterLimits(long intervalMs, int concurrency) {

  private static final Pattern FORMAT =
      Pattern.compile("(\\d+) requests? / (\\d+) s, concurrency (\\d+)");

  /** Parses {@code N request(s) / M s, concurrency C}; anything else is unparseable (empty). */
  public static Optional<RegisterLimits> parse(String text) {
    if (text == null) {
      return Optional.empty();
    }
    Matcher m = FORMAT.matcher(text.strip());
    if (!m.matches()) {
      return Optional.empty();
    }
    try {
      long requests = Long.parseLong(m.group(1));
      long seconds = Long.parseLong(m.group(2));
      int concurrency = Integer.parseInt(m.group(3));
      if (requests <= 0 || seconds <= 0 || concurrency <= 0) {
        return Optional.empty();
      }
      // Round up so a config never ends up faster than the register allows.
      long intervalMs = Math.ceilDiv(Math.multiplyExact(seconds, 1000L), requests);
      return Optional.of(new RegisterLimits(intervalMs, concurrency));
    } catch (NumberFormatException | ArithmeticException e) {
      return Optional.empty();
    }
  }
}
