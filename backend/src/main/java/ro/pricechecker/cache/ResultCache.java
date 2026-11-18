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
 * written, measured on the injected {@link Clock} (T-5). A loader that returns {@code null} or
 * throws leaves no entry, so the next {@link #get} loads again.
 *
 * <p>The returned future is shared: callers that stop waiting must use {@code get(timeout)}, never
 * {@code orTimeout} or {@code cancel}, so the load is not cut short for the others.
 */
public final class ResultCache<K, V> {

  private final AsyncCache<K, V> cache;

  public ResultCache(Duration ttl, Clock clock) {
    if (ttl == null || ttl.isNegative() || ttl.isZero()) {
      throw new IllegalArgumentException("ttl must be positive");
    }
    this.cache =
        Caffeine.newBuilder()
            .expireAfterWrite(ttl)
            .ticker(() -> nanos(clock.instant()))
            .executor(Executors.newVirtualThreadPerTaskExecutor())
            .buildAsync();
  }

  /** The cached value for {@code key}, or a load of it on a virtual thread. */
  public CompletableFuture<V> get(K key, Function<K, V> loader) {
    CompletableFuture<V> earlier = cache.getIfPresent(key);
    if (earlier != null && isEmptyResult(earlier)) {
      // Caffeine drops a failed or null result after it completes, not atomically with it; a
      // caller arriving in between would otherwise reuse it instead of loading again.
      cache.asMap().remove(key, earlier);
    }
    return cache.get(key, loader);
  }

  private static boolean isEmptyResult(CompletableFuture<?> future) {
    return future.isDone() && (future.isCompletedExceptionally() || future.join() == null);
  }

  private static long nanos(Instant instant) {
    return Math.addExact(
        Math.multiplyExact(instant.getEpochSecond(), 1_000_000_000L), instant.getNano());
  }
}
