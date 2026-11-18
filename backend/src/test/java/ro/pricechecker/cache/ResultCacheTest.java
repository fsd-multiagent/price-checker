package ro.pricechecker.cache;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import ro.pricechecker.registry.MutableClock;

class ResultCacheTest {

  private static final Duration TTL = Duration.ofMinutes(15);

  private final MutableClock clock = new MutableClock(Instant.parse("2026-11-18T10:15:30Z"));
  private final ResultCache<String, String> cache = new ResultCache<>(TTL, clock);
  private final AtomicInteger loads = new AtomicInteger();

  private String load(String key) {
    loads.incrementAndGet();
    return key + "-value";
  }

  @Test
  @Tag("AC-2.8.5")
  void ac_2_8_5_hitWithinTtlDoesNotLoadAgain() {
    assertThat(cache.get("k", this::load).join()).isEqualTo("k-value");
    clock.advance(TTL.minusMinutes(1));
    assertThat(cache.get("k", this::load).join()).isEqualTo("k-value");
    assertThat(loads).hasValue(1);
  }

  @Test
  @Tag("AC-2.8.5")
  void ac_2_8_5_afterTtlLoadsAgain() {
    cache.get("k", this::load).join();
    clock.advance(TTL.plusSeconds(1));
    cache.get("k", this::load).join();
    assertThat(loads).hasValue(2);
  }

  @Test
  @Tag("AC-2.8.5")
  void ac_2_8_5_nullOrFailedLoadIsNotKept() {
    assertThat(
            cache
                .get(
                    "k",
                    k -> {
                      loads.incrementAndGet();
                      return null;
                    })
                .join())
        .isNull();
    CompletableFuture<String> failed =
        cache.get(
            "k",
            k -> {
              loads.incrementAndGet();
              throw new IllegalStateException("boom");
            });
    assertThatThrownBy(failed::join).hasCauseInstanceOf(IllegalStateException.class);
    assertThat(cache.get("k", this::load).join()).isEqualTo("k-value");
    assertThat(loads).hasValue(3);
  }

  @Test
  @Tag("AC-2.8.5")
  void ac_2_8_5_concurrentMissesShareOneLoadThatOutlivesAWaiterTimeout() throws Exception {
    CountDownLatch release = new CountDownLatch(1);
    AtomicBoolean virtual = new AtomicBoolean();
    AtomicBoolean interrupted = new AtomicBoolean();
    java.util.function.Function<String, String> slow =
        k -> {
          loads.incrementAndGet();
          virtual.set(Thread.currentThread().isVirtual());
          try {
            release.await(10, TimeUnit.SECONDS);
          } catch (InterruptedException e) {
            interrupted.set(true);
            Thread.currentThread().interrupt();
          }
          return "slow";
        };

    CompletableFuture<String> first = cache.get("k", slow);
    CompletableFuture<String> second = cache.get("k", slow);
    assertThat(second).isSameAs(first);

    assertThatThrownBy(() -> first.get(10, TimeUnit.MILLISECONDS))
        .isInstanceOf(TimeoutException.class);
    assertThat(first).isNotCancelled().isNotDone();

    release.countDown();
    assertThat(second.get(10, TimeUnit.SECONDS)).isEqualTo("slow");
    assertThat(loads).hasValue(1);
    assertThat(virtual).isTrue();
    assertThat(interrupted).isFalse();
  }

  @Test
  void rejectsNonPositiveTtl() {
    assertThatThrownBy(() -> new ResultCache<String, String>(Duration.ZERO, clock))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
