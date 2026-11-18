package ro.pricechecker.fetch;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import ro.pricechecker.registry.MutableClock;

@Tag("AC-2.4.3")
class RetailerLimiterTest {

  private final MutableClock clock = new MutableClock(Instant.parse("2026-11-18T10:00:00Z"));
  private final RecordingSleeper sleeper = new RecordingSleeper(clock);
  private final RetailerLimiter limiter =
      new RetailerLimiter(Duration.ofMillis(2000), 1, clock, sleeper);

  private Instant in(long ms) {
    return clock.instant().plusMillis(ms);
  }

  @Test
  void ac_2_4_3_firstPermitIsImmediate() throws InterruptedException {
    Optional<RetailerLimiter.Permit> permit = limiter.acquire(in(4000));
    assertThat(permit).isPresent();
    assertThat(sleeper.sleeps).isEmpty();
  }

  @Test
  void ac_2_4_3_nextSlotIsWaitedForWhenBeforeTheDeadline() throws InterruptedException {
    limiter.acquire(in(4000)).orElseThrow().close();
    clock.advance(Duration.ofMillis(500));
    assertThat(limiter.acquire(in(4000))).isPresent();
    assertThat(sleeper.sleeps).containsExactly(Duration.ofMillis(1500));
  }

  @Test
  void ac_2_4_3_slotAtOrAfterTheDeadlineIsRefusedWithoutWaiting() throws InterruptedException {
    limiter.acquire(in(4000)).orElseThrow().close();
    assertThat(limiter.acquire(in(2000))).isEmpty();
    assertThat(sleeper.sleeps).isEmpty();
  }

  @Test
  void ac_2_4_3_refusalGivesTheConcurrencyPermitBack() throws InterruptedException {
    limiter.acquire(in(4000)).orElseThrow().close();
    assertThat(limiter.acquire(in(1000))).isEmpty();
    clock.advance(Duration.ofMillis(2000));
    assertThat(limiter.acquire(in(1000))).isPresent();
  }

  @Test
  void ac_2_4_3_heldPermitBlocksASecondCallerUntilItsDeadline() throws InterruptedException {
    RetailerLimiter.Permit held = limiter.acquire(in(4000)).orElseThrow();
    clock.advance(Duration.ofMillis(3000));
    assertThat(limiter.acquire(in(20))).isEmpty();
    held.close();
    assertThat(limiter.acquire(in(20))).isPresent();
  }

  @Test
  void ac_2_4_3_pastDeadlineIsRefused() throws InterruptedException {
    assertThat(limiter.acquire(in(0))).isEmpty();
    assertThat(limiter.acquire(in(4000))).isPresent();
  }
}
