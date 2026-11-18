package ro.pricechecker.fetch;

import java.math.BigInteger;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Optional;

/**
 * The time a {@code 429} asks us to wait until (AD-6, AC-11.4, RFC 9110 §10.2.3): {@code
 * Retry-After} as delta-seconds or an HTTP-date; missing or unparseable means {@value
 * #DEFAULT_SECONDS} s. A value is capped at {@link #MAX} from now, so a huge one cannot overflow.
 */
final class RetryAfter {

  static final long DEFAULT_SECONDS = 60;
  static final Duration MAX = Duration.ofDays(3650);

  private RetryAfter() {}

  /** When the retailer may be contacted again, computed from {@code now} (T-5). */
  static Instant notBefore(Optional<String> header, Instant now) {
    Instant cap = now.plus(MAX);
    if (header.isEmpty()) {
      return now.plusSeconds(DEFAULT_SECONDS);
    }
    String value = header.get().strip();
    if (!value.isEmpty() && value.chars().allMatch(c -> c >= '0' && c <= '9')) {
      BigInteger seconds = new BigInteger(value);
      if (seconds.compareTo(BigInteger.valueOf(MAX.toSeconds())) >= 0) {
        return cap;
      }
      return now.plusSeconds(seconds.longValueExact());
    }
    try {
      Instant date = ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant();
      return date.isAfter(cap) ? cap : date;
    } catch (DateTimeParseException e) {
      return now.plusSeconds(DEFAULT_SECONDS);
    }
  }
}
