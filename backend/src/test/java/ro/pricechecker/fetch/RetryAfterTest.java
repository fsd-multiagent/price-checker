package ro.pricechecker.fetch;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Optional;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Story 2.7: {@code Retry-After} as delta-seconds, HTTP-date, or the 60 s default (AD-6). */
class RetryAfterTest {

  private static final Instant NOW = Instant.parse("2026-11-18T10:00:00Z");

  private static String httpDate(Instant instant) {
    return DateTimeFormatter.RFC_1123_DATE_TIME.format(instant.atOffset(ZoneOffset.UTC));
  }

  @Test
  @Tag("AC-2.7.1")
  void ac_2_7_1_deltaSeconds() {
    assertThat(RetryAfter.notBefore(Optional.of("1"), NOW)).isEqualTo(NOW.plusSeconds(1));
    assertThat(RetryAfter.notBefore(Optional.of(" 120 "), NOW)).isEqualTo(NOW.plusSeconds(120));
    assertThat(RetryAfter.notBefore(Optional.of("0"), NOW)).isEqualTo(NOW);
  }

  @Test
  @Tag("AC-2.7.1")
  void ac_2_7_1_httpDate() {
    String header = httpDate(NOW.plusSeconds(2));
    assertThat(header).isEqualTo("Wed, 18 Nov 2026 10:00:02 GMT");
    assertThat(RetryAfter.notBefore(Optional.of(header), NOW)).isEqualTo(NOW.plusSeconds(2));
  }

  @Test
  @Tag("AC-2.7.1")
  void ac_2_7_1_missingIsSixtySeconds() {
    assertThat(RetryAfter.notBefore(Optional.empty(), NOW)).isEqualTo(NOW.plusSeconds(60));
  }

  @ParameterizedTest
  @Tag("AC-2.7.1")
  @ValueSource(strings = {"soon", "", " ", "-1", "1.5", "+5", "Thu, 32 Nov 2026 10:00:00 GMT"})
  void ac_2_7_1_garbageIsSixtySeconds(String header) {
    assertThat(RetryAfter.notBefore(Optional.of(header), NOW)).isEqualTo(NOW.plusSeconds(60));
  }

  @Test
  @Tag("AC-2.7.1")
  void ac_2_7_1_hugeValuesAreCappedWithoutOverflow() {
    Instant cap = NOW.plus(Duration.ofDays(3650));
    assertThat(RetryAfter.notBefore(Optional.of("99999999999999999999"), NOW)).isEqualTo(cap);
    assertThat(RetryAfter.notBefore(Optional.of(String.valueOf(Long.MAX_VALUE)), NOW))
        .isEqualTo(cap);
    assertThat(
            RetryAfter.notBefore(Optional.of(httpDate(Instant.parse("9999-12-31T00:00:00Z"))), NOW))
        .isEqualTo(cap);
  }
}
