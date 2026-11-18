package ro.pricechecker.fetch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import ro.pricechecker.registry.MutableClock;

/** Story 2.5: the per-host robots.txt cache on its own, with an injected clock only (T-5). */
class RobotsCacheTest {

  private static final Instant START = Instant.parse("2026-11-18T10:00:00Z");
  private static final Instant DEADLINE = START.plusSeconds(4);

  private final MutableClock clock = new MutableClock(START);
  private final RobotsCache cache = new RobotsCache(clock);
  private final AtomicInteger loads = new AtomicInteger();

  private RobotsCache.Loader answering(Optional<RobotsRules> rules) {
    return () -> {
      loads.incrementAndGet();
      return rules;
    };
  }

  @Test
  @Tag("AC-2.5.2")
  void ac_2_5_2_keptAnswerIsNotLoadedAgainWithin24h() throws InterruptedException {
    RobotsCache.Loader loader = answering(Optional.of(RobotsRules.DISALLOW_ALL));
    assertThat(cache.rules("a.example", DEADLINE, loader)).contains(RobotsRules.DISALLOW_ALL);
    clock.advance(RobotsCache.TTL.minusMillis(1));
    assertThat(cache.rules("a.example", DEADLINE, loader)).contains(RobotsRules.DISALLOW_ALL);
    assertThat(loads).hasValue(1);

    clock.advance(Duration.ofMillis(1));
    cache.rules("a.example", DEADLINE, loader);
    assertThat(loads).hasValue(2);
  }

  @Test
  @Tag("AC-2.5.2")
  void ac_2_5_2_hostKeyIgnoresCase() throws InterruptedException {
    cache.put("A.Example", RobotsRules.DISALLOW_ALL);
    assertThat(cache.rules("a.example", DEADLINE, answering(Optional.of(RobotsRules.ALLOW_ALL))))
        .contains(RobotsRules.DISALLOW_ALL);
    assertThat(loads).hasValue(0);
  }

  @Test
  @Tag("AC-2.5.2")
  void ac_2_5_2_hostsAreCachedSeparately() throws InterruptedException {
    cache.put("a.example", RobotsRules.DISALLOW_ALL);
    assertThat(
            cache.rules("www.a.example", DEADLINE, answering(Optional.of(RobotsRules.ALLOW_ALL))))
        .contains(RobotsRules.ALLOW_ALL);
    assertThat(loads).hasValue(1);
  }

  @Test
  @Tag("AC-2.5.3")
  void ac_2_5_3_unreachableAnswerIsNotKept() throws InterruptedException {
    RobotsCache.Loader loader = answering(Optional.empty());
    assertThat(cache.rules("a.example", DEADLINE, loader)).isEmpty();
    assertThat(cache.rules("a.example", DEADLINE, loader)).isEmpty();
    assertThat(loads).hasValue(2);
  }

  @Test
  @Tag("AC-2.5.3")
  void ac_2_5_3_loaderFailureIsNotKeptAndDoesNotWedgeTheHost() throws InterruptedException {
    RobotsCache.Loader failing =
        () -> {
          loads.incrementAndGet();
          throw new IllegalStateException("boom");
        };
    assertThatThrownBy(() -> cache.rules("a.example", DEADLINE, failing))
        .isInstanceOf(IllegalStateException.class);

    // The next caller loads again instead of waiting on a load that never completes.
    assertThat(cache.rules("a.example", DEADLINE, answering(Optional.of(RobotsRules.ALLOW_ALL))))
        .contains(RobotsRules.ALLOW_ALL);
    assertThat(loads).hasValue(2);
  }
}
