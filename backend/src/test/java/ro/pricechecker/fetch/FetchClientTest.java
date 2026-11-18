package ro.pricechecker.fetch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.assertj.core.api.Assertions.tuple;
import static ro.pricechecker.fetch.StubTransport.ok;
import static ro.pricechecker.fetch.StubTransport.redirect;
import static ro.pricechecker.fetch.StubTransport.status;

import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import ro.pricechecker.adapter.AdapterOutcome;
import ro.pricechecker.adapter.RetailerHttp;
import ro.pricechecker.adapter.UnavailableReason;
import ro.pricechecker.registry.MutableClock;
import ro.pricechecker.registry.Registry;
import ro.pricechecker.registry.RuntimeSwitch;
import ro.pricechecker.registry.TestRegistries;

/** AD-6 fetch client rules against a stub transport; no request ever leaves the JVM (T-3). */
class FetchClientTest {

  private static final Instant START = Instant.parse("2026-11-18T10:00:00Z");
  private static final URI PAGE = URI.create("https://a.example/p/1");
  private static final UserAgent UA = UserAgent.build("1.0", "https://contact.example/about");

  private final MutableClock clock = new MutableClock(START);
  private final RecordingSleeper sleeper = new RecordingSleeper(clock);
  private final StubTransport transport = new StubTransport();

  private FetchClient client(Registry registry, String... allowed) {
    RuntimeSwitch runtime = TestRegistries.runtimeAllowing(clock, allowed);
    assertThat(runtime.refresh()).isEmpty();
    return new FetchClient(
        registry, runtime, transport, clock, sleeper, UA, RobotsTest.allowAll(clock));
  }

  /** a.example: 10 ms interval, concurrency 1, 4 s deadline; fetching allowed for all three. */
  private FetchClient.BoundHttp boundA() {
    return client(TestRegistries.fetchable(10, 1, 4000), "a.example", "b.example", "c.example")
        .bind("a.example");
  }

  private static AdapterOutcome outcomeOf(RetailerHttp http, URI url) {
    FetchFailure f = catchThrowableOfType(FetchFailure.class, () -> http.get(url));
    assertThat(f).as("expected a FetchFailure").isNotNull();
    return f.outcome();
  }

  private static AdapterOutcome unavailable(UnavailableReason reason) {
    return new AdapterOutcome.Unavailable(reason);
  }

  // ---- happy path ----

  @Test
  @Tag("AC-2.4.2")
  void ac_2_4_2_approvedEnabledAllowedPageIsReturnedWithOneRequest() throws IOException {
    transport.then(ok("<html>p1</html>"));

    FetchedPage page = boundA().fetch(PAGE);

    assertThat(page.body()).isEqualTo("<html>p1</html>");
    assertThat(page.url()).isEqualTo(PAGE);
    assertThat(page.fetchedAt()).isEqualTo(clock.instant());
    assertThat(transport.requests).hasSize(1);
    StubTransport.Request request = transport.requests.get(0);
    assertThat(request.url()).isEqualTo(PAGE);
    assertThat(request.userAgent()).isEqualTo("price-checker/1.0 (+https://contact.example/about)");
    assertThat(request.timeout()).isEqualTo(Duration.ofMillis(4000));
    assertThat(sleeper.sleeps).isEmpty();
  }

  @Test
  @Tag("AC-2.4.2")
  void ac_2_4_2_retailerHttpGetReturnsTheBody() throws IOException {
    RetailerHttp http = boundA();
    assertThat(http.get(PAGE)).isEqualTo("<html>page</html>");
  }

  @ParameterizedTest
  @Tag("AC-2.4.1")
  @ValueSource(
      strings = {
        "https://www.a.example/p/1",
        "https://A.Example/p/1",
        "HTTPS://a.example/p/1",
        "https://a.example:443/p/1"
      })
  void ac_2_4_1_registerDomainOrWwwOverHttpsIsSent(String url) throws IOException {
    boundA().get(URI.create(url));
    assertThat(transport.requests).hasSize(1);
  }

  // ---- gate ----

  @Test
  @Tag("AC-2.4.2")
  void ac_2_4_2_notApprovedRetailerSendsNothing() {
    RetailerHttp http =
        client(TestRegistries.fetchable(10, 1, 4000), "a.example", "b.example", "c.example")
            .bind("c.example");
    assertThat(outcomeOf(http, URI.create("https://c.example/p/1")))
        .isEqualTo(unavailable(UnavailableReason.REFUSED));
    assertThat(transport.requests).isEmpty();
  }

  @Test
  @Tag("AC-2.4.2")
  void ac_2_4_2_disabledAdapterSendsNothing() {
    RetailerHttp http =
        client(TestRegistries.fetchable(10, 1, 4000), "a.example", "b.example", "c.example")
            .bind("b.example");
    assertThat(outcomeOf(http, URI.create("https://b.example/p/1")))
        .isEqualTo(unavailable(UnavailableReason.REFUSED));
    assertThat(transport.requests).isEmpty();
  }

  @Test
  @Tag("AC-2.4.2")
  void ac_2_4_2_fetchingStoppedSendsNothing() {
    RetailerHttp http =
        client(TestRegistries.fetchable(10, 1, 4000), "b.example").bind("a.example");
    assertThat(outcomeOf(http, PAGE)).isEqualTo(unavailable(UnavailableReason.REFUSED));
    assertThat(transport.requests).isEmpty();
  }

  @Test
  @Tag("AC-2.4.2")
  void ac_2_4_2_unknownRetailerCannotBeBound() {
    FetchClient client = client(TestRegistries.fetchable(10, 1, 4000), "a.example");
    assertThatThrownBy(() -> client.bind("evil.example"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  // ---- URL checks ----

  @ParameterizedTest
  @Tag("AC-2.4.1")
  @ValueSource(
      strings = {
        "http://a.example/p/1",
        "https://evil.example/p/1",
        "https://x.a.example/p/1",
        "https://www.www.a.example/p/1",
        "https://a.example.evil.example/p/1",
        "https://evila.example/p/1",
        "https://a.example:8443/p/1",
        "https://a.example:80/p/1",
        "https://user@a.example/p/1",
        "https://user:pw@a.example/p/1",
        "ftp://a.example/p/1",
        "/p/1",
        "mailto:x@a.example"
      })
  void ac_2_4_1_offHostSchemePortOrUserinfoSendsNothing(String url) {
    assertThat(outcomeOf(boundA(), URI.create(url)))
        .isEqualTo(unavailable(UnavailableReason.REFUSED));
    assertThat(transport.requests).isEmpty();
  }

  // ---- redirects ----

  @Test
  @Tag("AC-2.4.1")
  void ac_2_4_1_redirectToWwwIsFollowedWithANewCheck() throws IOException {
    transport.then(redirect(301, "https://www.a.example/q")).then(ok("q"));

    FetchedPage page = boundA().fetch(PAGE);

    assertThat(page.body()).isEqualTo("q");
    assertThat(page.url()).isEqualTo(URI.create("https://www.a.example/q"));
    assertThat(transport.requests)
        .extracting(StubTransport.Request::url)
        .containsExactly(PAGE, URI.create("https://www.a.example/q"));
  }

  @Test
  @Tag("AC-2.4.1")
  void ac_2_4_1_relativeRedirectIsResolvedAgainstTheCurrentUrl() throws IOException {
    transport.then(redirect(302, "/q?x=1")).then(ok("q"));
    assertThat(boundA().fetch(PAGE).url()).isEqualTo(URI.create("https://a.example/q?x=1"));
  }

  @ParameterizedTest
  @Tag("AC-2.4.1")
  @ValueSource(
      strings = {
        "https://evil.example/q",
        "http://a.example/q",
        "https://x.a.example/q",
        "https://a.example:8443/q",
        "//evil.example/q"
      })
  void ac_2_4_1_redirectOffHostIsRefusedAfterOneRequest(String location) {
    transport.then(redirect(301, location));
    assertThat(outcomeOf(boundA(), PAGE)).isEqualTo(unavailable(UnavailableReason.REFUSED));
    assertThat(transport.requests).extracting(StubTransport.Request::url).containsExactly(PAGE);
  }

  @Test
  @Tag("AC-2.4.1")
  void ac_2_4_1_atMostThreeRedirectsAreFollowed() {
    transport.always(redirect(307, "https://a.example/loop"));
    assertThat(outcomeOf(boundA(), PAGE)).isEqualTo(unavailable(UnavailableReason.HTTP_ERROR));
    assertThat(transport.requests).hasSize(4);
  }

  @Test
  @Tag("AC-2.4.1")
  void ac_2_4_1_redirectWithoutLocationIsAnHttpError() {
    transport.then(status(302));
    assertThat(outcomeOf(boundA(), PAGE)).isEqualTo(unavailable(UnavailableReason.HTTP_ERROR));
    assertThat(transport.requests).hasSize(1);
  }

  @Test
  @Tag("AC-2.4.3")
  void ac_2_4_3_redirectHopTakesItsOwnRateSlot() throws IOException {
    FetchClient.BoundHttp http =
        client(TestRegistries.fetchable(2000, 1, 4000), "a.example").bind("a.example");
    transport.then(redirect(301, "https://www.a.example/q")).then(ok("q"));

    http.fetch(PAGE);

    assertThat(sleeper.sleeps).containsExactly(Duration.ofMillis(2000));
    assertThat(transport.requests).hasSize(2);
  }

  // ---- limits ----

  @Test
  @Tag("AC-2.4.3")
  void ac_2_4_3_waitsForTheNextRateSlotInsideTheDeadline() throws IOException {
    FetchClient.BoundHttp http =
        client(TestRegistries.fetchable(2000, 1, 4000), "a.example").bind("a.example");
    http.get(PAGE);
    http.get(PAGE);
    assertThat(sleeper.sleeps).containsExactly(Duration.ofMillis(2000));
    assertThat(transport.requests).hasSize(2);
  }

  @Test
  @Tag("AC-2.4.3")
  void ac_2_4_3_rateSlotAfterTheDeadlineSendsNothing() throws IOException {
    FetchClient.BoundHttp http =
        client(TestRegistries.fetchable(2000, 1, 1000), "a.example").bind("a.example");
    http.get(PAGE);

    assertThat(outcomeOf(http, PAGE)).isEqualTo(unavailable(UnavailableReason.REFUSED));
    assertThat(transport.requests).hasSize(1);
    assertThat(sleeper.sleeps).isEmpty();
  }

  @Test
  @Tag("AC-2.4.3")
  void ac_2_4_3_noConcurrencyPermitBeforeTheDeadlineSendsNothing() throws IOException {
    FetchClient.BoundHttp http =
        client(TestRegistries.fetchable(10, 1, 50), "a.example").bind("a.example");
    AtomicReference<AdapterOutcome> nested = new AtomicReference<>();
    // While the only permit is held by this request, a second one cannot get it in time.
    transport.then(url -> {
      nested.set(outcomeOf(http, PAGE));
      return ok("outer");
    });

    assertThat(http.get(PAGE)).isEqualTo("outer");
    assertThat(nested.get()).isEqualTo(unavailable(UnavailableReason.REFUSED));
    assertThat(transport.requests).hasSize(1);
  }

  // ---- retries ----

  @ParameterizedTest
  @Tag("AC-2.4.4")
  @ValueSource(ints = {500, 502, 503, 504, 599})
  void ac_2_4_4_fiveHundredsAreRetriedTwiceWithBackoff(int code) {
    transport.always(status(code));
    assertThat(outcomeOf(boundA(), PAGE)).isEqualTo(unavailable(UnavailableReason.HTTP_ERROR));
    assertThat(transport.requests).hasSize(3);
    assertThat(sleeper.sleeps).containsExactly(Duration.ofMillis(250), Duration.ofMillis(500));
  }

  @Test
  @Tag("AC-2.4.4")
  void ac_2_4_4_timeoutsAreRetriedTwiceWithBackoff() {
    transport.always(url -> {
      throw new HttpTimeoutException("timed out");
    });
    assertThat(outcomeOf(boundA(), PAGE)).isEqualTo(unavailable(UnavailableReason.TIMEOUT));
    assertThat(transport.requests).hasSize(3);
    assertThat(sleeper.sleeps).containsExactly(Duration.ofMillis(250), Duration.ofMillis(500));
  }

  @Test
  @Tag("AC-2.4.4")
  void ac_2_4_4_retryNeverChangesUrlOrUserAgent() {
    transport.always(status(503));
    outcomeOf(boundA(), PAGE);
    assertThat(transport.requests)
        .extracting(StubTransport.Request::url, StubTransport.Request::userAgent)
        .containsOnly(tuple(PAGE, UA.value()));
  }

  @Test
  @Tag("AC-2.4.4")
  void ac_2_4_4_successOnRetryReturnsThePage() throws IOException {
    transport.then(status(503)).then(ok("second"));
    assertThat(boundA().get(PAGE)).isEqualTo("second");
    assertThat(transport.requests).hasSize(2);
    assertThat(sleeper.sleeps).containsExactly(Duration.ofMillis(250));
  }

  @Test
  @Tag("AC-2.4.4")
  void ac_2_4_4_backoffThatDoesNotFitTheDeadlineStopsRetrying() {
    FetchClient.BoundHttp http =
        client(TestRegistries.fetchable(10, 1, 600), "a.example").bind("a.example");
    transport.always(status(503));
    assertThat(outcomeOf(http, PAGE)).isEqualTo(unavailable(UnavailableReason.HTTP_ERROR));
    assertThat(transport.requests).hasSize(2);
    assertThat(sleeper.sleeps).containsExactly(Duration.ofMillis(250));
  }

  @ParameterizedTest
  @Tag("AC-2.4.4")
  @ValueSource(ints = {400, 401, 403, 405, 429, 451, 304, 999})
  void ac_2_4_4_fourHundredsAreNotRetried(int code) {
    transport.always(status(code));
    assertThat(outcomeOf(boundA(), PAGE)).isEqualTo(unavailable(UnavailableReason.HTTP_ERROR));
    assertThat(transport.requests).hasSize(1);
    assertThat(sleeper.sleeps).isEmpty();
  }

  @Test
  @Tag("AC-2.4.4")
  void ac_2_4_4_otherTransportErrorsAreNotRetried() {
    transport.always(url -> {
      throw new ConnectException("refused");
    });
    assertThat(outcomeOf(boundA(), PAGE)).isEqualTo(unavailable(UnavailableReason.HTTP_ERROR));
    assertThat(transport.requests).hasSize(1);
  }

  // ---- not found ----

  @ParameterizedTest
  @Tag("AC-2.4.6")
  @ValueSource(ints = {404, 410})
  void ac_2_4_6_notFoundCarriesTheClockTime(int code) {
    transport.always(status(code));
    clock.advance(Duration.ofSeconds(7));

    FetchFailure f = catchThrowableOfType(FetchFailure.class, () -> boundA().get(PAGE));

    assertThat(f.outcome()).isEqualTo(new AdapterOutcome.NotFound());
    assertThat(f.fetchedAt()).isEqualTo(START.plusSeconds(7));
    assertThat(f).isInstanceOf(IOException.class);
    assertThat(transport.requests).hasSize(1);
  }
}
