package ro.pricechecker.cache;

import com.github.benmanes.caffeine.cache.AsyncCache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.function.Function;

/**
 * A generic async cache (AD-1: no project types). Each miss runs its loader on its own virtual
 * thread; concurrent misses for one key share that load. An entry lives {@code ttl} after it was
 * written, measured on the injected {@link Clock} (T-5) from the instant its loader returned. A
 * loader that returns {@code null} or throws leaves no entry, so the next {@link #get} loads again.
 *
 * <p>Each call returns its own future derived from the shared load; callers that stop waiting
 * should still use {@code get(timeout)} rather than {@code orTimeout} or {@code cancel}.
 */
public final class ResultCache<K, V> {

  private final AsyncCache<K, Timed<V>> cache;
  private final Duration ttl;
  private final Clock clock;

  public ResultCache(Duration ttl, Clock clock) {
    if (ttl == null || ttl.isNegative() || ttl.isZero()) {
      throw new IllegalArgumentException("ttl must be positive");
    }
    this.ttl = ttl;
    this.clock = clock;
    this.cache =
        Caffeine.newBuilder()
            .expireAfterWrite(ttl)
            .ticker(() -> nanos(clock.instant()))
            .executor(Executors.newVirtualThreadPerTaskExecutor())
            .buildAsync();
  }

  /** The cached value for {@code key}, or a load of it on a virtual thread. */
  public CompletableFuture<V> get(K key, Function<K, V> loader) {
    CompletableFuture<Timed<V>> earlier = cache.getIfPresent(key);
    if (earlier != null && (isEmptyResult(earlier) || isExpired(earlier))) {
      // Caffeine drops a failed or null result after it completes, not atomically with it; a
      // caller arriving in between would otherwise reuse it instead of loading again. Caffeine also
      // stamps the write time when the load completes, on a callback that can run after a waiter
      // resumed, so expiry is checked here against the time the loader itself recorded.
      cache.asMap().remove(key, earlier);
    }
    return cache
        .get(
            key,
            k -> {
              V value = loader.apply(k);
              return value == null ? null : new Timed<>(value, clock.instant());
            })
        .thenApply(timed -> timed == null ? null : timed.value());
  }

  private boolean isExpired(CompletableFuture<Timed<V>> future) {
    return future.isDone() && !clock.instant().isBefore(future.join().loadedAt().plus(ttl));
  }

  private static boolean isEmptyResult(CompletableFuture<?> future) {
    return future.isDone() && (future.isCompletedExceptionally() || future.join() == null);
  }

  private record Timed<V>(V value, Instant loadedAt) {}

  private static long nanos(Instant instant) {
    return Math.addExact(
        Math.multiplyExact(instant.getEpochSecond(), 1_000_000_000L), instant.getNano());
  }
}
