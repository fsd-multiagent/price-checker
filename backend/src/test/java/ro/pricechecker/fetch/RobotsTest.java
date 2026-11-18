package ro.pricechecker.fetch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static ro.pricechecker.fetch.StubTransport.ok;
import static ro.pricechecker.fetch.StubTransport.redirect;
import static ro.pricechecker.fetch.StubTransport.status;

import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpTimeoutException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import ro.pricechecker.adapter.AdapterOutcome;
import ro.pricechecker.adapter.UnavailableReason;
import ro.pricechecker.registry.MutableClock;
import ro.pricechecker.registry.Registry;
import ro.pricechecker.registry.RuntimeSwitch;
import ro.pricechecker.registry.TestRegistries;

/**
 * Story 2.5: robots.txt is checked before every page attempt (S-4, AD-6 check 4, RFC 9309). Stub
 * transport, injected clock and sleeper only (T-3, T-5).
 */
class RobotsTest {

  private static final Instant START = Instant.parse("2026-11-18T10:00:00Z");
  private static final URI ROBOTS = URI.create("https://a.example/robots.txt");
  private static final URI PAGE = URI.create("https://a.example/p/1");
  private static final URI SEARCH = URI.create("https://a.example/cauta?q=1");
  private static final UserAgent UA = UserAgent.build("1.0", "https://contact.example/about");
  private static final String DISALLOW_SEARCH = "User-agent: *\nDisallow: /cauta\n";

  private final MutableClock clock = new MutableClock(START);
  private final RecordingSleeper sleeper = new RecordingSleeper(clock);
  private final StubTransport transport = new StubTransport();
  private volatile StubTransport.Reply robots = url -> ok(DISALLOW_SEARCH);

  /** A cache answering allow-all for a.example and www.a.example, for the story 2.4 tests. */
  static RobotsCache allowAll(Clock clock) {
    RobotsCache cache = new RobotsCache(clock);
    cache.put("a.example", RobotsRules.ALLOW_ALL);
    cache.put("www.a.example", RobotsRules.ALLOW_ALL);
    return cache;
  }

  private FetchClient.BoundHttp boundA(int minIntervalMs, int deadlineMs) {
    return boundA(minIntervalMs, deadlineMs, new RobotsCache(clock));
  }

  private FetchClient.BoundHttp boundA(int minIntervalMs, int deadlineMs, RobotsCache cache) {
    Registry registry = TestRegistries.fetchable(minIntervalMs, 1, deadlineMs);
    RuntimeSwitch runtime = TestRegistries.runtimeAllowing(clock, "a.example");
    assertThat(runtime.refresh()).isEmpty();
    // robots.txt answers from the `robots` field; every other path is a page.
    transport.always(url -> url.getPath().equals("/robots.txt") ? robots.answer(url) : ok("page"));
    return new FetchClient(
            registry,
            runtime,
            TestRegistries.blockStore(runtime, clock),
            transport,
            clock,
            sleeper,
            UA,
            cache)
        .bind("a.example");
  }

  private FetchClient.BoundHttp boundA() {
    return boundA(10, 4000);
  }

  private static AdapterOutcome outcomeOf(FetchClient.BoundHttp http, URI url) {
    FetchFailure f = catchThrowableOfType(FetchFailure.class, () -> http.fetch(url));
    assertThat(f).as("expected a FetchFailure").isNotNull();
    return f.outcome();
  }

  private static AdapterOutcome unavailable(UnavailableReason reason) {
    return new AdapterOutcome.Unavailable(reason);
  }

  private void assertUrls(URI... urls) {
    assertThat(transport.requests).extracting(StubTransport.Request::url).containsExactly(urls);
  }

  // ---- AC-2.5.1: a disallowed path is never sent ----

  @Test
  @Tag("AC-2.5.1")
  void ac_2_5_1_allowedPathIsSentAfterTheRobotsRequest() throws IOException {
    assertThat(boundA().get(PAGE)).isEqualTo("page");
    assertUrls(ROBOTS, PAGE);
    assertThat(transport.requests.get(0).userAgent()).isEqualTo(UA.value());
  }

  @Test
  @Tag("AC-2.5.1")
  void ac_2_5_1_disallowedPathSendsOnlyTheRobotsRequest() {
    assertThat(outcomeOf(boundA(), SEARCH)).isEqualTo(unavailable(UnavailableReason.BLOCKED));
    assertUrls(ROBOTS);
  }

  @Test
  @Tag("AC-2.5.1")
  void ac_2_5_1_redirectHopToADisallowedPathIsNotSent() {
    FetchClient.BoundHttp http = boundA();
    transport.then(ok(DISALLOW_SEARCH)).then(redirect(302, "/cauta?q=1"));

    assertThat(outcomeOf(http, PAGE)).isEqualTo(unavailable(UnavailableReason.BLOCKED));
    assertUrls(ROBOTS, PAGE);
  }

  @Test
  @Tag("AC-2.5.1")
  void ac_2_5_1_ourProductTokenGroupApplies() {
    robots =
        url -> ok("User-agent: *\nDisallow: /cauta\n\nUser-agent: price-checker\nDisallow: /p/\n");
    assertThat(outcomeOf(boundA(), PAGE)).isEqualTo(unavailable(UnavailableReason.BLOCKED));
    assertUrls(ROBOTS);
  }

  @Test
  @Tag("AC-2.5.1")
  void ac_2_5_1_wwwHostHasItsOwnRobots() throws IOException {
    FetchClient.BoundHttp http = boundA();
    http.get(PAGE);
    http.get(URI.create("https://www.a.example/p/1"));
    assertUrls(
        ROBOTS,
        PAGE,
        URI.create("https://www.a.example/robots.txt"),
        URI.create("https://www.a.example/p/1"));
  }

  @ParameterizedTest
  @Tag("AC-2.5.1")
  @ValueSource(
      strings = {"https://evil.example/p/1", "http://a.example/p/1", "https://x.a.example/p/1"})
  void ac_2_5_1_offHostUrlLoadsNoRobotsEither(String url) {
    // The host check runs first: a URL the gate refuses never triggers a robots.txt request.
    assertThat(outcomeOf(boundA(), URI.create(url)))
        .isEqualTo(unavailable(UnavailableReason.REFUSED));
    assertThat(transport.requests).isEmpty();
  }

  @Test
  @Tag("AC-2.5.1")
  void ac_2_5_1_disallowedPathTakesNoRateSlot() throws IOException {
    FetchClient.BoundHttp http = boundA(2000, 4000);
    assertThat(outcomeOf(http, SEARCH)).isEqualTo(unavailable(UnavailableReason.BLOCKED));
    // Only the robots request ran, as the first request of the host: no wait for a page slot.
    assertThat(sleeper.sleeps).isEmpty();
    assertThat(outcomeOf(http, SEARCH)).isEqualTo(unavailable(UnavailableReason.BLOCKED));
    assertThat(sleeper.sleeps).isEmpty();
    assertUrls(ROBOTS);
  }

  @Test
  @Tag("AC-2.5.1")
  void ac_2_5_1_disallowAllForOurTokenBlocksEveryPath() {
    robots = url -> ok("User-agent: price-checker\nDisallow: /\n");
    FetchClient.BoundHttp http = boundA();
    assertThat(outcomeOf(http, PAGE)).isEqualTo(unavailable(UnavailableReason.BLOCKED));
    assertThat(outcomeOf(http, URI.create("https://a.example/")))
        .isEqualTo(unavailable(UnavailableReason.BLOCKED));
    assertUrls(ROBOTS);
  }

  // ---- AC-2.5.3: an unparseable or unreachable robots.txt sends no page ----

  @ParameterizedTest
  @Tag("AC-2.5.3")
  @ValueSource(strings = {"<!doctype html>\n<html><body>Hello</body></html>", "User-agent: *\0\n"})
  void ac_2_5_3_unparseableRobotsBlocksEverythingFor24h(String body) {
    robots = url -> ok(body);
    FetchClient.BoundHttp http = boundA();

    assertThat(outcomeOf(http, PAGE)).isEqualTo(unavailable(UnavailableReason.BLOCKED));
    clock.advance(Duration.ofHours(23));
    assertThat(outcomeOf(http, PAGE)).isEqualTo(unavailable(UnavailableReason.BLOCKED));
    assertUrls(ROBOTS);
  }

  @ParameterizedTest
  @Tag("AC-2.5.3")
  @ValueSource(ints = {500, 503, 599, 429})
  void ac_2_5_3_robotsServerErrorOr429RefusesAndIsNotKept(int code) {
    robots = url -> status(code);
    FetchClient.BoundHttp http = boundA();

    assertThat(outcomeOf(http, PAGE)).isEqualTo(unavailable(UnavailableReason.REFUSED));
    assertThat(outcomeOf(http, PAGE)).isEqualTo(unavailable(UnavailableReason.REFUSED));
    // Not retried, not kept: one robots request per page request, and no page.
    assertUrls(ROBOTS, ROBOTS);
    assertThat(sleeper.sleeps).hasSize(1); // only the rate slot of the second robots request
  }

  @Test
  @Tag("AC-2.5.3")
  void ac_2_5_3_robotsTimeoutRefusesWithoutRetry() {
    robots =
        url -> {
          throw new HttpTimeoutException("timed out");
        };
    assertThat(outcomeOf(boundA(), PAGE)).isEqualTo(unavailable(UnavailableReason.REFUSED));
    assertUrls(ROBOTS);
    assertThat(sleeper.sleeps).isEmpty();
  }

  @Test
  @Tag("AC-2.5.3")
  void ac_2_5_3_robotsTransportErrorRefuses() {
    robots =
        url -> {
          throw new ConnectException("refused");
        };
    assertThat(outcomeOf(boundA(), PAGE)).isEqualTo(unavailable(UnavailableReason.REFUSED));
    assertUrls(ROBOTS);
  }

  @Test
  @Tag("AC-2.5.3")
  void ac_2_5_3_unreachableRobotsIsLoadedAgainOnTheNextRequest() throws IOException {
    robots = url -> status(503);
    FetchClient.BoundHttp http = boundA();
    assertThat(outcomeOf(http, PAGE)).isEqualTo(unavailable(UnavailableReason.REFUSED));

    robots = url -> ok(DISALLOW_SEARCH);
    assertThat(http.get(PAGE)).isEqualTo("page");
    assertUrls(ROBOTS, ROBOTS, PAGE);
  }

  @ParameterizedTest
  @Tag("AC-2.5.3")
  @ValueSource(
      strings = {
        "https://evil.example/robots.txt",
        "http://a.example/robots.txt",
        "https://x.a.example/robots.txt"
      })
  void ac_2_5_3_offHostRobotsRedirectIsNotFollowed(String location) {
    robots = url -> redirect(301, location);
    assertThat(outcomeOf(boundA(), PAGE)).isEqualTo(unavailable(UnavailableReason.REFUSED));
    assertUrls(ROBOTS);
  }

  @Test
  @Tag("AC-2.5.3")
  void ac_2_5_3_onHostRobotsRedirectIsFollowed() throws IOException {
    URI www = URI.create("https://www.a.example/robots.txt");
    robots = url -> url.equals(ROBOTS) ? redirect(301, www.toString()) : ok(DISALLOW_SEARCH);
    FetchClient.BoundHttp http = boundA();

    assertThat(http.get(PAGE)).isEqualTo("page");
    assertThat(outcomeOf(http, SEARCH)).isEqualTo(unavailable(UnavailableReason.BLOCKED));
    assertUrls(ROBOTS, www, PAGE);
  }

  @Test
  @Tag("AC-2.5.3")
  void ac_2_5_3_moreThanThreeRobotsRedirectsRefuse() {
    robots = url -> redirect(307, "/robots.txt");
    assertThat(outcomeOf(boundA(), PAGE)).isEqualTo(unavailable(UnavailableReason.REFUSED));
    assertUrls(ROBOTS, ROBOTS, ROBOTS, ROBOTS);
  }

  @ParameterizedTest
  @Tag("AC-2.5.3")
  @ValueSource(ints = {400, 401, 403, 404, 410, 451})
  void ac_2_5_3_robotsClientErrorAllowsAllAndIsKept(int code) throws IOException {
    robots = url -> status(code);
    FetchClient.BoundHttp http = boundA();
    http.get(SEARCH);
    http.get(PAGE);
    assertUrls(ROBOTS, SEARCH, PAGE);
  }

  @ParameterizedTest
  @Tag("AC-2.5.3")
  @ValueSource(strings = {"", "\n\n", "# nothing here\n"})
  void ac_2_5_3_emptyRobotsAllowsAll(String body) throws IOException {
    robots = url -> ok(body);
    boundA().get(SEARCH);
    assertUrls(ROBOTS, SEARCH);
  }

  @Test
  @Tag("AC-2.5.3")
  void ac_2_5_3_noPermitForTheRobotsFetchRefusesAndIsNotKept() throws IOException {
    // www.a.example is known; a.example's robots.txt must wait for the permit the outer page holds.
    RobotsCache cache = new RobotsCache(clock);
    cache.put("www.a.example", RobotsRules.ALLOW_ALL);
    FetchClient.BoundHttp http = boundA(10, 50, cache);
    AtomicReference<AdapterOutcome> nested = new AtomicReference<>();
    URI outer = URI.create("https://www.a.example/p/1");
    transport.then(url -> {
      nested.set(outcomeOf(http, PAGE));
      return ok("outer");
    });

    assertThat(http.get(outer)).isEqualTo("outer");
    assertThat(nested.get()).isEqualTo(unavailable(UnavailableReason.REFUSED));
    assertUrls(outer);

    http.get(PAGE);
    assertUrls(outer, ROBOTS, PAGE);
  }

  @Test
  @Tag("AC-2.5.3")
  void ac_2_5_3_robotsRateSlotAfterTheDeadlineRefuses() throws IOException {
    RobotsCache cache = new RobotsCache(clock);
    cache.put("www.a.example", RobotsRules.ALLOW_ALL);
    FetchClient.BoundHttp http = boundA(2000, 1000, cache);
    http.get(URI.create("https://www.a.example/p/1"));

    assertThat(outcomeOf(http, PAGE)).isEqualTo(unavailable(UnavailableReason.REFUSED));
    assertThat(transport.requests).hasSize(1);
  }

  // ---- AC-2.5.2: single-flight, permits and 24 h cache ----

  @Test
  @Tag("AC-2.5.2")
  void ac_2_5_2_robotsTakesARateSlotBeforeThePage() throws IOException {
    boundA(2000, 4000).get(PAGE);
    assertUrls(ROBOTS, PAGE);
    assertThat(sleeper.sleeps).containsExactly(Duration.ofMillis(2000));
  }

  @Test
  @Tag("AC-2.5.2")
  void ac_2_5_2_robotsIsKept24hThenLoadedAgain() throws IOException {
    FetchClient.BoundHttp http = boundA();
    http.get(PAGE);
    clock.advance(Duration.ofHours(24).minusSeconds(1));
    http.get(PAGE);
    assertUrls(ROBOTS, PAGE, PAGE);

    clock.advance(Duration.ofSeconds(1));
    http.get(PAGE);
    assertUrls(ROBOTS, PAGE, PAGE, ROBOTS, PAGE);
  }

  @Test
  @Tag("AC-2.5.2")
  void ac_2_5_2_retryDoesNotLoadRobotsAgain() throws IOException {
    FetchClient.BoundHttp http = boundA();
    transport.then(ok(DISALLOW_SEARCH)).then(status(503));
    assertThat(http.get(PAGE)).isEqualTo("page");
    assertUrls(ROBOTS, PAGE, PAGE);
  }

  @Test
  @Tag("AC-2.5.2")
  void ac_2_5_2_twoFirstRequestsShareOneRobotsLoad() throws Exception {
    FetchClient.BoundHttp http = boundA();
    CompletableFuture<Thread> second = new CompletableFuture<>();
    AtomicReference<String> secondBody = new AtomicReference<>();
    robots =
        url -> {
          // While this load is in flight, a second first request starts and must wait for it.
          Thread t =
              new Thread(() -> {
                try {
                  secondBody.set(http.get(URI.create("https://a.example/p/2")));
                } catch (IOException e) {
                  secondBody.set(e.toString());
                }
              });
          t.start();
          second.complete(t);
          long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
          while (t.getState() != Thread.State.TIMED_WAITING && System.nanoTime() < until) {
            Thread.onSpinWait();
          }
          assertThat(t.getState()).isEqualTo(Thread.State.TIMED_WAITING);
          return ok(DISALLOW_SEARCH);
        };

    assertThat(http.get(PAGE)).isEqualTo("page");
    Thread t = second.get(5, TimeUnit.SECONDS);
    t.join(5000);

    assertThat(secondBody.get()).isEqualTo("page");
    assertThat(transport.requests)
        .extracting(StubTransport.Request::url)
        .filteredOn(ROBOTS::equals)
        .hasSize(1);
    assertThat(transport.requests).hasSize(3);
  }

  @Test
  @Tag("AC-2.5.2")
  void ac_2_5_2_waiterPastItsDeadlineIsRefused() throws InterruptedException {
    RobotsCache cache = new RobotsCache(clock);
    AtomicReference<Optional<RobotsRules>> waiter = new AtomicReference<>();
    Optional<RobotsRules> first =
        cache.rules(
            "a.example",
            START.plusSeconds(4),
            () -> {
              waiter.set(
                  cache.rules(
                      "a.example", clock.instant(), () -> Optional.of(RobotsRules.ALLOW_ALL)));
              return Optional.of(RobotsRules.DISALLOW_ALL);
            });

    assertThat(first).contains(RobotsRules.DISALLOW_ALL);
    assertThat(waiter.get()).isEmpty();
  }
}
