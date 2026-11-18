package ro.pricechecker.fetch;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpTimeoutException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import ro.pricechecker.adapter.RetailerHttp;
import ro.pricechecker.adapter.UnavailableReason;
import ro.pricechecker.registry.BlockStore;
import ro.pricechecker.registry.Registry;
import ro.pricechecker.registry.RetailerConfig;
import ro.pricechecker.registry.RuntimeSwitch;

/**
 * The shared fetch client, the only way out to a retailer (AD-6, C-12, AC-N3.8). An adapter gets a
 * {@link BoundHttp} for its retailer id and cannot name another host. Before every attempt
 * (redirect hops and retries included) it sends nothing unless the retailer is Approved, its
 * adapter is enabled, fetching is allowed, the URL is {@code https} on the register domain or
 * {@code www.} of it (port 443, no userinfo), the host's robots.txt allows the path for the product
 * token (S-4), and a rate and concurrency permit is free before the deadline. robots.txt is loaded
 * on a host's first request through the same gate and permits, never retried, and kept per host for
 * 24 h; it fails closed (RFC 9309): a disallowed path or an unparseable file gives {@code
 * Unavailable(BLOCKED)}, an unreachable one (5xx, 429, timeout, I/O error, off-host or too many
 * redirects, no permit) gives {@code Unavailable(REFUSED)} and is not kept. Only timeouts and 5xx
 * are retried, at most {@value #MAX_RETRIES} times with backoff inside the deadline; headers, user
 * agent and route never change on a retry (S-6).
 *
 * <p>Blocks (FR-11, S-6, story 2.6): a 403, a recognized challenge page ({@link ChallengePage}, on
 * a 2xx or 5xx body) or a robots.txt disallow calls {@link BlockStore#record} and gives {@code
 * Unavailable(BLOCKED)}, never retried. While {@link BlockStore#isBlocked} is true every request to
 * that retailer, robots.txt included, is refused without sending ({@code Unavailable(BLOCKED)}).
 * Nothing here ever retries a Block another way: no proxy, no other user agent, no other headers
 * (AC-11.3).
 *
 * <p>429 (AC-11.4, story 2.7): never a Block. The client computes {@code notBefore} from {@code
 * Retry-After} ({@link RetryAfter}, 60 s when missing or unparseable) and calls {@link
 * BlockStore#recordRetryAfter}. When {@code notBefore} is before the deadline it waits until then
 * and sends the same request once more (a single 429 retry, separate from the 5xx and timeout
 * ones); otherwise, or on a second 429, the outcome is {@code Unavailable(REFUSED)}. A robots.txt
 * 429 records {@code notBefore} too and is never retried. While {@link BlockStore#notBefore} is in
 * the future every request to that retailer, robots.txt included, is refused without sending
 * ({@code Unavailable(REFUSED)}).
 */
public final class FetchClient {

  static final int MAX_RETRIES = 2;
  static final int MAX_REDIRECTS = 3;
  static final Duration FIRST_BACKOFF = Duration.ofMillis(250);

  private static final Set<Integer> REDIRECTS = Set.of(301, 302, 303, 307, 308);

  private final Registry registry;
  private final RuntimeSwitch runtimeSwitch;
  private final BlockStore blocks;
  private final Transport transport;
  private final Clock clock;
  private final Sleeper sleeper;
  private final String userAgent;
  private final String productToken;
  private final RobotsCache robots;
  private final Map<String, RetailerConfig> configs = new HashMap<>();
  private final Map<String, RetailerLimiter> limiters = new HashMap<>();

  public FetchClient(
      Registry registry,
      RuntimeSwitch runtimeSwitch,
      BlockStore blocks,
      Transport transport,
      Clock clock,
      Sleeper sleeper,
      UserAgent userAgent) {
    this(
        registry,
        runtimeSwitch,
        blocks,
        transport,
        clock,
        sleeper,
        userAgent,
        new RobotsCache(clock));
  }

  /** With a given robots.txt cache, so tests can seed it. */
  FetchClient(
      Registry registry,
      RuntimeSwitch runtimeSwitch,
      BlockStore blocks,
      Transport transport,
      Clock clock,
      Sleeper sleeper,
      UserAgent userAgent,
      RobotsCache robots) {
    this.registry = registry;
    this.runtimeSwitch = runtimeSwitch;
    this.blocks = blocks;
    this.transport = transport;
    this.clock = clock;
    this.sleeper = sleeper;
    this.userAgent = userAgent.value();
    this.productToken = userAgent.productToken();
    this.robots = robots;
    for (RetailerConfig config : registry.retailers()) {
      configs.put(config.domain(), config);
      limiters.put(
          config.domain(),
          new RetailerLimiter(
              Duration.ofMillis(config.minIntervalMs()), config.concurrency(), clock, sleeper));
    }
  }

  /**
   * A {@link RetailerHttp} bound to one retailer id from {@code retailers.yaml}.
   *
   * @throws IllegalArgumentException when the id has no entry in {@code retailers.yaml}
   */
  public BoundHttp bind(String retailerId) {
    RetailerConfig config = configs.get(retailerId);
    if (config == null) {
      throw new IllegalArgumentException("no retailers.yaml entry for " + retailerId);
    }
    return new BoundHttp(config, limiters.get(retailerId));
  }

  /** The client for one retailer; every call has its own deadline of {@code deadlineMs}. */
  public final class BoundHttp implements RetailerHttp {

    private final RetailerConfig config;
    private final RetailerLimiter limiter;

    private BoundHttp(RetailerConfig config, RetailerLimiter limiter) {
      this.config = config;
      this.limiter = limiter;
    }

    public String retailerId() {
      return config.domain();
    }

    /**
     * Returns the body of a GET to {@code url}.
     *
     * @throws FetchFailure carrying the outcome when no page was read
     */
    @Override
    public String get(URI url) throws IOException {
      return fetch(url).body();
    }

    /**
     * Fetches {@code url}, following at most {@value FetchClient#MAX_REDIRECTS} checked redirects.
     *
     * @throws FetchFailure carrying the outcome when no page was read
     */
    public FetchedPage fetch(URI url) throws FetchFailure {
      Instant deadline = clock.instant().plusMillis(config.deadlineMs());
      try {
        return follow(url, deadline);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw unavailable(UnavailableReason.TIMEOUT);
      }
    }

    private FetchedPage follow(URI start, Instant deadline)
        throws FetchFailure, InterruptedException {
      URI url = start;
      int retries = 0;
      int redirects = 0;
      boolean retriedTooMany = false;
      while (true) {
        Transport.Response response;
        UnavailableReason failure;
        try {
          check(url);
          checkRobots(url, deadline);
          response = attempt(url, deadline);
          int status = response.status();
          if (status >= 200 && status < 300) {
            if (ChallengePage.detect(response.body())) {
              throw block(BlockStore.Reason.CHALLENGE);
            }
            return new FetchedPage(url, response.body(), clock.instant());
          }
          if (status == 403) {
            throw block(BlockStore.Reason.HTTP_403);
          }
          if (status == 429) {
            // A later pause already stored (or from runtime.yaml) wins over this 429's own value.
            Instant own = tooMany(response);
            Instant notBefore = blocks.notBefore(config.domain()).orElse(own);
            if (retriedTooMany || !notBefore.isBefore(deadline)) {
              throw unavailable(UnavailableReason.REFUSED);
            }
            retriedTooMany = true;
            Duration wait = Duration.between(clock.instant(), notBefore);
            if (wait.isPositive()) {
              sleeper.sleep(wait);
            }
            continue;
          }
          if (status == 404 || status == 410) {
            throw FetchFailure.notFound(clock.instant());
          }
          if (REDIRECTS.contains(status)) {
            if (redirects >= MAX_REDIRECTS) {
              throw unavailable(UnavailableReason.HTTP_ERROR);
            }
            redirects++;
            url = redirectTarget(url, response.location());
            continue;
          }
          if (status < 500 || status > 599) {
            // 4xx and anything else unexpected is never retried.
            throw unavailable(UnavailableReason.HTTP_ERROR);
          }
          if (ChallengePage.detect(response.body())) {
            // A challenge behind a 5xx is a Block, not a server error to retry.
            throw block(BlockStore.Reason.CHALLENGE);
          }
          failure = UnavailableReason.HTTP_ERROR;
        } catch (HttpTimeoutException e) {
          failure = UnavailableReason.TIMEOUT;
        } catch (FetchFailure e) {
          throw e;
        } catch (IOException e) {
          throw unavailable(UnavailableReason.HTTP_ERROR);
        }
        Duration backoff = FIRST_BACKOFF.multipliedBy(1L << retries);
        if (retries >= MAX_RETRIES || !clock.instant().plus(backoff).isBefore(deadline)) {
          throw unavailable(failure);
        }
        retries++;
        sleeper.sleep(backoff);
      }
    }

    /** One checked attempt: gate, URL, permit, then exactly one request. */
    private Transport.Response attempt(URI url, Instant deadline)
        throws IOException, InterruptedException {
      check(url);
      Optional<RetailerLimiter.Permit> permit = limiter.acquire(deadline);
      if (permit.isEmpty()) {
        throw unavailable(UnavailableReason.REFUSED);
      }
      try {
        // The wait for a permit may have spanned a Kill switch flip.
        check(url);
        Duration left = Duration.between(clock.instant(), deadline);
        if (!left.isPositive()) {
          throw unavailable(UnavailableReason.REFUSED);
        }
        return transport.get(url, userAgent, left);
      } finally {
        permit.get().close();
      }
    }

    /** Sends nothing unless the host's robots.txt allows {@code url} for our product token. */
    private void checkRobots(URI url, Instant deadline) throws FetchFailure, InterruptedException {
      String host = url.getHost().toLowerCase(Locale.ROOT);
      Optional<RobotsRules> rules = robots.rules(host, deadline, () -> loadRobots(host, deadline));
      if (rules.isEmpty()) {
        throw unavailable(UnavailableReason.REFUSED);
      }
      if (!rules.get().allows(url)) {
        throw block(BlockStore.Reason.ROBOTS);
      }
    }

    /**
     * Loads {@code https://<host>/robots.txt}: each hop through {@link #attempt} (gate, host check,
     * permit), at most {@value FetchClient#MAX_REDIRECTS} on-host redirects, no retry.
     *
     * @return the rules, or empty when robots.txt is unreachable
     */
    private Optional<RobotsRules> loadRobots(String host, Instant deadline)
        throws InterruptedException {
      URI url;
      try {
        url = new URI("https", host, "/robots.txt", null);
      } catch (URISyntaxException e) {
        return Optional.empty();
      }
      int redirects = 0;
      while (true) {
        Transport.Response response;
        try {
          response = attempt(url, deadline);
          int status = response.status();
          if (status >= 200 && status < 300) {
            return Optional.of(
                RobotsRules.parse(response.body(), productToken).orElse(RobotsRules.DISALLOW_ALL));
          }
          if (REDIRECTS.contains(status)) {
            if (redirects >= MAX_REDIRECTS) {
              return Optional.empty();
            }
            redirects++;
            url = redirectTarget(url, response.location());
            continue;
          }
          if (status == 429) {
            tooMany(response);
            return Optional.empty();
          }
          if (status >= 400 && status < 500) {
            return Optional.of(RobotsRules.ALLOW_ALL);
          }
          return Optional.empty();
        } catch (IOException e) {
          // Timeout, transport error, refused gate, host or permit: unreachable, not kept.
          return Optional.empty();
        }
      }
    }

    private void check(URI url) throws FetchFailure {
      String domain = config.domain();
      if (!registry.isApproved(domain) || !config.enabled() || !runtimeSwitch.isAllowed(domain)) {
        throw unavailable(UnavailableReason.REFUSED);
      }
      if (blocks.isBlocked(domain)) {
        throw unavailable(UnavailableReason.BLOCKED);
      }
      if (!onRetailerHost(url, domain)) {
        throw unavailable(UnavailableReason.REFUSED);
      }
      Optional<Instant> notBefore = blocks.notBefore(domain);
      if (notBefore.isPresent() && notBefore.get().isAfter(clock.instant())) {
        throw unavailable(UnavailableReason.REFUSED);
      }
    }

    /**
     * Records the {@code notBefore} a 429 asks for and returns it. A failed {@code blocks.json}
     * write still leaves the value in memory, so the outcome is the same.
     */
    private Instant tooMany(Transport.Response response) {
      Instant notBefore = RetryAfter.notBefore(response.retryAfter(), clock.instant());
      try {
        blocks.recordRetryAfter(config.domain(), notBefore);
      } catch (UncheckedIOException e) {
        // The value is kept in memory; the write failure changes no outcome.
      }
      return notBefore;
    }

    /**
     * Records a Block for this retailer and returns the {@code BLOCKED} failure to throw. A failed
     * {@code blocks.json} write still leaves the Block active in memory (and logged), so the
     * outcome is the same.
     */
    private FetchFailure block(BlockStore.Reason reason) {
      try {
        blocks.record(config.domain(), reason);
      } catch (UncheckedIOException e) {
        // The Block is active in memory and was logged; the write failure changes no outcome.
      }
      return unavailable(UnavailableReason.BLOCKED);
    }

    private URI redirectTarget(URI from, Optional<String> location) throws FetchFailure {
      if (location.isEmpty() || location.get().isBlank()) {
        throw unavailable(UnavailableReason.HTTP_ERROR);
      }
      try {
        return from.resolve(new URI(location.get().strip()));
      } catch (URISyntaxException | IllegalArgumentException e) {
        throw unavailable(UnavailableReason.REFUSED);
      }
    }

    private FetchFailure unavailable(UnavailableReason reason) {
      return FetchFailure.unavailable(reason, clock.instant());
    }
  }

  /** {@code https}, host equal to the domain or {@code www.} of it, port 443, no userinfo. */
  static boolean onRetailerHost(URI url, String domain) {
    if (url == null
        || !url.isAbsolute()
        || url.isOpaque()
        || !"https".equalsIgnoreCase(url.getScheme())
        || url.getRawUserInfo() != null
        || url.getHost() == null
        || (url.getPort() != -1 && url.getPort() != 443)) {
      return false;
    }
    String host = url.getHost().toLowerCase(Locale.ROOT);
    return host.equals(domain) || host.equals("www." + domain);
  }
}
