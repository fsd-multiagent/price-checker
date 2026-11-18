package ro.pricechecker.fetch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static ro.pricechecker.fetch.StubTransport.ok;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import ro.pricechecker.registry.MutableClock;
import ro.pricechecker.registry.Registry;
import ro.pricechecker.registry.RuntimeSwitch;
import ro.pricechecker.registry.TestRegistries;

/** AC 3.5.1: the health pace doubles the request interval and allows one request at a time. */
@Tag("AC-3.5.1")
class FetchPaceTest {

  private static final UserAgent UA = UserAgent.build("1.0", "https://contact.example/about");
  private static final URI PAGE_1 = URI.create("https://a.example/p/1");
  private static final URI PAGE_2 = URI.create("https://a.example/p/2");

  private FetchClient client(
      Registry registry, Transport transport, Clock clock, Sleeper sleeper, FetchClient.Pace pace) {
    RuntimeSwitch runtime = TestRegistries.runtimeAllowing(clock, "a.example");
    assertThat(runtime.refresh()).isEmpty();
    return new FetchClient(
        registry,
        runtime,
        TestRegistries.blockStore(runtime, clock),
        transport,
        clock,
        sleeper,
        UA,
        RobotsTest.allowAll(clock),
        pace);
  }

  private List<Instant> twoSends(FetchClient.Pace pace) throws Exception {
    MutableClock clock = new MutableClock(Instant.parse("2026-11-18T10:00:00Z"));
    List<Instant> sent = new ArrayList<>();
    StubTransport transport =
        new StubTransport()
            .always(url -> {
              sent.add(clock.instant());
              return ok("page");
            });
    FetchClient.BoundHttp http =
        client(
                TestRegistries.fetchable(10, 4, 4000),
                transport,
                clock,
                new RecordingSleeper(clock),
                pace)
            .bind("a.example");
    http.get(PAGE_1);
    http.get(PAGE_2);
    return sent;
  }

  @Test
  void ac_3_5_1_doubledIntervalSeparatesTwoSendsByTwentyMs() throws Exception {
    List<Instant> sent = twoSends(new FetchClient.Pace(2, 1));

    assertThat(sent).hasSize(2);
    assertThat(Duration.between(sent.get(0), sent.get(1)))
        .isGreaterThanOrEqualTo(Duration.ofMillis(20));
  }

  @Test
  void ac_3_5_1_normalPaceKeepsTheConfiguredInterval() throws Exception {
    List<Instant> sent = twoSends(FetchClient.Pace.NORMAL);

    assertThat(Duration.between(sent.get(0), sent.get(1))).isEqualTo(Duration.ofMillis(10));
  }

  @Test
  void ac_3_5_1_maxConcurrencyOneNeverSendsTwoAtOnce() throws Exception {
    AtomicInteger inFlight = new AtomicInteger();
    AtomicInteger maxInFlight = new AtomicInteger();
    StubTransport transport =
        new StubTransport()
            .always(url -> {
              maxInFlight.accumulateAndGet(inFlight.incrementAndGet(), Math::max);
              Thread.sleep(30);
              inFlight.decrementAndGet();
              return ok("page");
            });
    FetchClient.BoundHttp http =
        client(
                TestRegistries.fetchable(10, 4, 4000),
                transport,
                Clock.systemUTC(),
                Sleeper.SYSTEM,
                new FetchClient.Pace(2, 1))
            .bind("a.example");

    CompletableFuture<String> first = CompletableFuture.supplyAsync(() -> get(http, PAGE_1));
    CompletableFuture<String> second = CompletableFuture.supplyAsync(() -> get(http, PAGE_2));

    assertThat(first.get(5, TimeUnit.SECONDS)).isEqualTo("page");
    assertThat(second.get(5, TimeUnit.SECONDS)).isEqualTo("page");
    assertThat(maxInFlight.get()).isEqualTo(1);
  }

  @Test
  void ac_3_5_1_paceRejectsValuesBelowOne() {
    assertThatThrownBy(() -> new FetchClient.Pace(0, 1))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new FetchClient.Pace(1, 0))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private static String get(FetchClient.BoundHttp http, URI url) {
    try {
      return http.get(url);
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }
}
