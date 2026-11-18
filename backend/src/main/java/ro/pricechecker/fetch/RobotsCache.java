package ro.pricechecker.fetch;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Per-host robots.txt answers (S-4, AD-6 check 4). The first caller for a host loads it while the
 * others wait for that one load (single-flight). An answer (rules, allow-all or disallow-all) is
 * kept {@link #TTL} from the injected clock (T-5); an unreachable robots.txt is not kept, so the
 * next request loads it again. Never prefetched: a host is loaded on its first request.
 */
final class RobotsCache {

  static final Duration TTL = Duration.ofHours(24);

  /** The outcome of one load: rules until {@code expires}, or unreachable (empty, not kept). */
  private record Verdict(Optional<RobotsRules> rules, Instant expires) {

    static final Verdict UNREACHABLE = new Verdict(Optional.empty(), Instant.MIN);

    boolean cacheable() {
      return rules.isPresent();
    }
  }

  /**
   * Loads robots.txt for one host within the caller's deadline: the rules, or empty when it is
   * unreachable. Never throws for a fetch failure.
   */
  @FunctionalInterface
  interface Loader {
    Optional<RobotsRules> load() throws InterruptedException;
  }

  private final ConcurrentHashMap<String, CompletableFuture<Verdict>> entries =
      new ConcurrentHashMap<>();
  private final Clock clock;

  RobotsCache(Clock clock) {
    this.clock = clock;
  }

  /** Seeds an answer for {@code host}, kept {@link #TTL} from now (tests). */
  void put(String host, RobotsRules rules) {
    entries.put(
        key(host),
        CompletableFuture.completedFuture(
            new Verdict(Optional.of(rules), clock.instant().plus(TTL))));
  }

  /**
   * The rules for {@code host}, loading them with {@code loader} when none are kept.
   *
   * @return empty when robots.txt is unreachable, or another caller's load did not finish before
   *     {@code deadline}
   */
  Optional<RobotsRules> rules(String host, Instant deadline, Loader loader)
      throws InterruptedException {
    String key = key(host);
    while (true) {
      CompletableFuture<Verdict> mine = new CompletableFuture<>();
      CompletableFuture<Verdict> existing = entries.putIfAbsent(key, mine);
      if (existing == null) {
        return load(key, mine, loader).rules();
      }
      Verdict verdict;
      if (existing.isDone()) {
        verdict = existing.join();
        if (!clock.instant().isBefore(verdict.expires())) {
          entries.remove(key, existing);
          continue;
        }
        return verdict.rules();
      }
      // Wall time, like the concurrency semaphore (known limit, deferred in story 2.4).
      Duration left = Duration.between(clock.instant(), deadline);
      if (!left.isPositive()) {
        return Optional.empty();
      }
      try {
        verdict = existing.get(left.toNanos(), TimeUnit.NANOSECONDS);
      } catch (TimeoutException | ExecutionException e) {
        return Optional.empty();
      }
      return verdict.rules();
    }
  }

  private Verdict load(String key, CompletableFuture<Verdict> mine, Loader loader)
      throws InterruptedException {
    Verdict verdict = Verdict.UNREACHABLE;
    try {
      Optional<RobotsRules> rules = loader.load();
      if (rules.isPresent()) {
        verdict = new Verdict(rules, clock.instant().plus(TTL));
      }
      return verdict;
    } finally {
      // Removed before completing, so a done entry in the map is always a kept answer.
      if (!verdict.cacheable()) {
        entries.remove(key, mine);
      }
      mine.complete(verdict);
    }
  }

  private static String key(String host) {
    return host.toLowerCase(Locale.ROOT);
  }
}
