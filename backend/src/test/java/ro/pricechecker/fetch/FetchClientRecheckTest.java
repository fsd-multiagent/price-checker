package ro.pricechecker.fetch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static ro.pricechecker.fetch.StubTransport.ok;
import static ro.pricechecker.fetch.StubTransport.redirect;
import static ro.pricechecker.fetch.StubTransport.status;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpTimeoutException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ro.pricechecker.adapter.AdapterOutcome;
import ro.pricechecker.adapter.UnavailableReason;
import ro.pricechecker.registry.MutableClock;
import ro.pricechecker.registry.RuntimeSwitch;
import ro.pricechecker.registry.TestRegistries;

/**
 * QA expansion for story 2-4: the gate is checked again before every attempt, so a Kill switch flip
 * during a request stops the next hop, retry or permit wait; retries share one cap and stay inside
 * the deadline. Stub transport, injected clock and sleeper only (T-3, T-5).
 */
class FetchClientRecheckTest {

  private static final Instant START = Instant.parse("2026-11-18T10:00:00Z");
  private static final URI PAGE = URI.create("https://a.example/p/1");
  private static final UserAgent UA = UserAgent.build("1.0", "https://contact.example/about");

  @TempDir Path dir;

  private final MutableClock clock = new MutableClock(START);
  private final StubTransport transport = new StubTransport();
  private final List<Duration> sleeps = new ArrayList<>();
  private Path runtimeFile;
  private RuntimeSwitch runtime;

  @BeforeEach
  void allowA() throws IOException {
    runtimeFile = dir.resolve("runtime.yaml");
    writeFetching("allowed");
    runtime = new RuntimeSwitch(runtimeFile, clock);
    assertThat(runtime.refresh()).isEmpty();
  }

  private void writeFetching(String value) {
    try {
      Files.writeString(runtimeFile, "retailers:\n  a.example: {fetching: " + value + "}\n");
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /** The owner stops a.example; the next poll (15 s later) picks it up. */
  private void stopA() {
    writeFetching("stopped");
    clock.advance(Duration.ofSeconds(15));
    assertThat(runtime.refresh()).isEmpty();
    assertThat(runtime.isAllowed("a.example")).isFalse();
  }

  private FetchClient.BoundHttp boundA(int minIntervalMs, int deadlineMs, Sleeper sleeper) {
    return new FetchClient(
            TestRegistries.fetchable(minIntervalMs, 1, deadlineMs),
            runtime,
            TestRegistries.blockStore(runtime, clock),
            transport,
            clock,
            sleeper,
            UA,
            RobotsTest.allowAll(clock))
        .bind("a.example");
  }

  private Sleeper recording() {
    return d -> {
      sleeps.add(d);
      clock.advance(d);
    };
  }

  private static AdapterOutcome outcomeOf(FetchClient.BoundHttp http, URI url) {
    FetchFailure f = catchThrowableOfType(FetchFailure.class, () -> http.fetch(url));
    assertThat(f).as("expected a FetchFailure").isNotNull();
    return f.outcome();
  }

  // ---- AC-2.4.2: the gate is re-checked before every attempt ----

  @Test
  @Tag("AC-2.4.2")
  void ac_2_4_2_killSwitchStoppedDuringARequestSendsNoRetry() {
    FetchClient.BoundHttp http = boundA(10, 60_000, recording());
    transport.then(url -> {
      stopA();
      return status(503);
    });

    assertThat(outcomeOf(http, PAGE))
        .isEqualTo(new AdapterOutcome.Unavailable(UnavailableReason.REFUSED));
    assertThat(transport.requests).hasSize(1);
  }

  @Test
  @Tag("AC-2.4.2")
  void ac_2_4_2_killSwitchStoppedDuringARequestFollowsNoRedirect() {
    FetchClient.BoundHttp http = boundA(10, 60_000, recording());
    transport.then(url -> {
      stopA();
      return redirect(301, "https://www.a.example/q");
    });

    assertThat(outcomeOf(http, PAGE))
        .isEqualTo(new AdapterOutcome.Unavailable(UnavailableReason.REFUSED));
    assertThat(transport.requests).extracting(StubTransport.Request::url).containsExactly(PAGE);
  }

  @Test
  @Tag("AC-2.4.2")
  void ac_2_4_2_killSwitchStoppedWhileWaitingForARateSlotSendsNothing() throws IOException {
    // The second request waits 20 s for its rate slot; the owner stops a.example meanwhile.
    Sleeper flipWhileWaiting =
        d -> {
          sleeps.add(d);
          clock.advance(d);
          writeFetching("stopped");
          assertThat(runtime.refresh()).isEmpty();
        };
    FetchClient.BoundHttp http = boundA(20_000, 60_000, flipWhileWaiting);
    http.fetch(PAGE);

    assertThat(outcomeOf(http, PAGE))
        .isEqualTo(new AdapterOutcome.Unavailable(UnavailableReason.REFUSED));
    assertThat(sleeps).containsExactly(Duration.ofSeconds(20));
    assertThat(transport.requests).hasSize(1);
  }

  @Test
  @Tag("AC-2.4.2")
  void ac_2_4_2_killSwitchAllowedAgainLetsTheNextRequestThrough() throws IOException {
    FetchClient.BoundHttp http = boundA(10, 60_000, recording());
    stopA();
    assertThat(outcomeOf(http, PAGE))
        .isEqualTo(new AdapterOutcome.Unavailable(UnavailableReason.REFUSED));
    assertThat(transport.requests).isEmpty();

    writeFetching("allowed");
    clock.advance(Duration.ofSeconds(15));
    assertThat(runtime.refresh()).isEmpty();

    assertThat(http.get(PAGE)).isEqualTo("<html>page</html>");
    assertThat(transport.requests).hasSize(1);
  }

  // ---- AC-2.4.4: one retry cap, inside the deadline ----

  @Test
  @Tag("AC-2.4.4")
  void ac_2_4_4_timeoutsAndFiveHundredsShareOneCapOfTwoRetries() {
    FetchClient.BoundHttp http = boundA(10, 4000, recording());
    transport
        .then(url -> {
          throw new HttpTimeoutException("timed out");
        })
        .then(status(503))
        .then(url -> {
          throw new HttpTimeoutException("timed out");
        })
        .always(ok("never reached"));

    assertThat(outcomeOf(http, PAGE))
        .isEqualTo(new AdapterOutcome.Unavailable(UnavailableReason.TIMEOUT));
    assertThat(transport.requests).hasSize(3);
    assertThat(sleeps).containsExactly(Duration.ofMillis(250), Duration.ofMillis(500));
  }

  @Test
  @Tag("AC-2.4.4")
  void ac_2_4_4_eachAttemptGetsOnlyTheTimeLeftBeforeTheDeadline() {
    FetchClient.BoundHttp http = boundA(10, 4000, recording());
    transport.always(url -> {
      clock.advance(Duration.ofMillis(1000));
      throw new HttpTimeoutException("timed out");
    });

    outcomeOf(http, PAGE);

    // 4000; minus 1000 spent and 250 backoff; minus another 1000 and 500 backoff.
    assertThat(transport.requests)
        .extracting(StubTransport.Request::timeout)
        .containsExactly(Duration.ofMillis(4000), Duration.ofMillis(2750), Duration.ofMillis(1250));
  }

  @Test
  @Tag("AC-2.4.4")
  void ac_2_4_4_slowAttemptsStopRetryingWhenTheBackoffNoLongerFits() {
    FetchClient.BoundHttp http = boundA(10, 4000, recording());
    transport.always(url -> {
      clock.advance(Duration.ofMillis(3800));
      throw new HttpTimeoutException("timed out");
    });

    assertThat(outcomeOf(http, PAGE))
        .isEqualTo(new AdapterOutcome.Unavailable(UnavailableReason.TIMEOUT));
    assertThat(transport.requests).hasSize(1);
    assertThat(sleeps).isEmpty();
  }

  // ---- AC-2.4.6: outcomes and fetchedAt from the injected clock ----

  @Test
  @Tag("AC-2.4.6")
  void ac_2_4_6_notFoundAfterARedirectIsNotFound() {
    FetchClient.BoundHttp http = boundA(10, 4000, recording());
    transport.then(redirect(301, "https://www.a.example/gone")).then(status(410));

    FetchFailure f = catchThrowableOfType(FetchFailure.class, () -> http.fetch(PAGE));

    assertThat(f.outcome()).isEqualTo(new AdapterOutcome.NotFound());
    assertThat(transport.requests).hasSize(2);
  }

  @Test
  @Tag("AC-2.4.6")
  void ac_2_4_6_pageReadAfterARetryCarriesTheClockTimeAfterTheBackoff() throws IOException {
    FetchClient.BoundHttp http = boundA(10, 4000, recording());
    transport.then(status(502)).then(ok("p"));

    FetchedPage page = http.fetch(PAGE);

    assertThat(page.fetchedAt()).isEqualTo(START.plusMillis(250));
  }

  @Test
  @Tag("AC-2.4.6")
  void ac_2_4_6_unavailableCarriesTheClockTimeOfTheLastAttempt() {
    FetchClient.BoundHttp http = boundA(10, 4000, recording());
    transport.always(status(503));

    FetchFailure f = catchThrowableOfType(FetchFailure.class, () -> http.fetch(PAGE));

    assertThat(f.outcome()).isEqualTo(new AdapterOutcome.Unavailable(UnavailableReason.HTTP_ERROR));
    assertThat(f.fetchedAt()).isEqualTo(START.plusMillis(750));
  }
}
