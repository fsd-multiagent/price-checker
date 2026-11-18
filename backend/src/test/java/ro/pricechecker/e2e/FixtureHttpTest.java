package ro.pricechecker.e2e;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import ro.pricechecker.registry.Registry;

class FixtureHttpTest {

  private static final URI PAGE = URI.create("https://a.example/e2e/5940000000011");
  private static final URI NO_PAGE = URI.create("https://a.example/e2e/5940000000028");

  private final FixtureHttp http =
      new FixtureHttp(
          Set.of(PAGE, NO_PAGE),
          url -> url.equals(PAGE) ? Optional.of("<html>page</html>") : Optional.empty(),
          Duration.ofMillis(50));

  @Test
  void ac_2_14_4_servesKnownPageAndCountsPerEan() throws IOException {
    assertThat(http.get(PAGE)).isEqualTo("<html>page</html>");
    assertThat(http.get(PAGE)).isEqualTo("<html>page</html>");
    assertThat(http.requests("5940000000011")).isEqualTo(2);
    assertThat(http.requests("5940000000028")).isZero();
  }

  @Test
  void ac_2_14_4_throwsForUnknownUrlWithoutCounting() {
    assertThatThrownBy(() -> http.get(URI.create("https://b.example/e2e/5940000000011")))
        .isInstanceOf(IOException.class);
    assertThat(http.requests("5940000000011")).isZero();
  }

  @Test
  void ac_2_14_2_hangsForKnownUrlWithoutPageThenThrows() {
    assertThatThrownBy(() -> http.get(NO_PAGE)).isInstanceOf(IOException.class);
    assertThat(http.requests("5940000000028")).isEqualTo(1);
  }

  @Test
  void ac_2_14_2_releaseEndsAHangInProgressBeforeItsTimeout() throws Exception {
    FixtureHttp longHang =
        new FixtureHttp(Set.of(NO_PAGE), url -> Optional.empty(), Duration.ofMinutes(10));
    Thread caller =
        Thread.ofVirtual()
            .start(() ->
                assertThatThrownBy(() -> longHang.get(NO_PAGE)).isInstanceOf(IOException.class));
    while (longHang.requests("5940000000028") == 0) {
      Thread.onSpinWait();
    }

    longHang.close();

    assertThat(caller.join(Duration.ofSeconds(10))).isTrue();
    assertThatThrownBy(() -> longHang.get(NO_PAGE)).isInstanceOf(IOException.class);
    assertThat(longHang.requests("5940000000028")).isEqualTo(2);
  }

  @Test
  void ac_2_14_4_e2eRegistryLoadsAndEveryPageIsKnown() {
    Registry registry = E2eBeans.registry();
    assertThat(registry.approvedDomains()).hasSize(2);
    assertThat(registry.knownEans()).hasSize(6);
    long withPage =
        registry.knownEans().stream()
            .filter(k -> FixtureHttp.classpathPage(URI.create(k.url())).isPresent())
            .count();
    assertThat(withPage).isEqualTo(5);
  }
}
