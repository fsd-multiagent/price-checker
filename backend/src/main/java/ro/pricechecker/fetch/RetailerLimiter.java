package ro.pricechecker.fetch;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * Rate and concurrency limit for one retailer (S-3, S-5, AC 2.4.3). A caller first takes a
 * concurrency permit (fair {@link Semaphore}, tried for the time left before the deadline), then
 * the next rate slot {@code max(now, next)}; when that slot is not before the deadline it gives the
 * permit back and is refused, so nothing is sent. Waits go through the injected {@link Sleeper} and
 * time comes from the injected {@link Clock} (T-5). The slot is taken at the moment of sending, so
 * two sends are never closer than the interval, even after a late wake-up.
 */
final class RetailerLimiter {

  /** A held concurrency permit; close it when the response has been read. */
  interface Permit extends AutoCloseable {
    @Override
    void close();
  }

  private final Duration interval;
  private final Semaphore permits;
  private final Clock clock;
  private final Sleeper sleeper;

  private Instant next = Instant.MIN;

  RetailerLimiter(Duration interval, int concurrency, Clock clock, Sleeper sleeper) {
    this.interval = interval;
    this.permits = new Semaphore(concurrency, true);
    this.clock = clock;
    this.sleeper = sleeper;
  }

  /**
   * Returns a permit once the caller may send, or empty when that cannot happen before {@code
   * deadline}.
   */
  Optional<Permit> acquire(Instant deadline) throws InterruptedException {
    Duration left = Duration.between(clock.instant(), deadline);
    if (!left.isPositive() || !permits.tryAcquire(left.toNanos(), TimeUnit.NANOSECONDS)) {
      return Optional.empty();
    }
    boolean granted = false;
    try {
      while (true) {
        Instant now = clock.instant();
        Instant slot;
        synchronized (this) {
          slot = next.isAfter(now) ? next : now;
          if (!slot.isBefore(deadline)) {
            return Optional.empty();
          }
          if (!slot.isAfter(now)) {
            next = now.plus(interval);
            granted = true;
            return Optional.of(permits::release);
          }
        }
        sleeper.sleep(Duration.between(now, slot));
      }
    } finally {
      if (!granted) {
        permits.release();
      }
    }
  }
}
