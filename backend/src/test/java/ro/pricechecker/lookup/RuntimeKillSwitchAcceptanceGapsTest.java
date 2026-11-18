package ro.pricechecker.lookup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.groups.Tuple.tuple;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import ro.pricechecker.adapter.AdapterOutcome;
import ro.pricechecker.adapter.PageFacts;
import ro.pricechecker.adapter.RetailerAdapter;
import ro.pricechecker.adapter.RetailerHttp;
import ro.pricechecker.cache.ResultCache;
import ro.pricechecker.ean.Ean;
import ro.pricechecker.registry.MutableClock;
import ro.pricechecker.registry.Registry;
import ro.pricechecker.registry.RuntimeSwitch;
import ro.pricechecker.registry.TestRegistries;

/**
 * Story 2.3 gaps found by Eufrosina (QA), through {@link LookupService} (T-1, T-5): the 60 s bound
 * when the file changes between polls, recovery after a rejected or missing file, and a Proposed
 * retailer whose adapter is enabled. Adapters count both their own calls and the GETs they make
 * through {@link RetailerHttp}, so "no request" is checked at the HTTP port.
 */
@ExtendWith(OutputCaptureExtension.class)
class RuntimeKillSwitchAcceptanceGapsTest {

  private static final String A_ALLOWED = "retailers:\n  a.example: {fetching: allowed}\n";
  private static final String A_STOPPED = "retailers:\n  a.example: {fetching: stopped}\n";
  private static final Duration SIXTY_SECONDS = Duration.ofSeconds(60);

  @TempDir Path dir;

  private final MutableClock clock = new MutableClock(Instant.parse("2026-11-18T10:00:00Z"));
  private final List<String> adapterCalls = new ArrayList<>();
  private final List<URI> requests = new ArrayList<>();
  private LookupService service;

  private void write(String yaml) throws IOException {
    Files.writeString(dir.resolve("runtime.yaml"), yaml);
  }

  private LookupService service(Registry registry) {
    List<RetailerAdapter> adapters =
        List.of(adapter("a.example"), adapter("b.example"), adapter("c.example"));
    RetailerHttp http =
        url -> {
          requests.add(url);
          return "";
        };
    return new LookupService(
        registry,
        new AdapterCatalog(adapters, registry),
        Optional.of(id -> http),
        clock,
        new RuntimeSwitch(dir.resolve("runtime.yaml"), clock),
        TestRegistries.blockStore(TestRegistries.runtimeAllowing(clock), clock),
        new ResultCache<>(Duration.ofMinutes(15), clock),
        new LookupLog(clock));
  }

  private LookupService service() {
    if (service == null) {
      service = service(TestRegistries.mixed());
    }
    return service;
  }

  private RetailerAdapter adapter(String id) {
    return new RetailerAdapter() {
      @Override
      public String retailerId() {
        return id;
      }

      @Override
      public AdapterOutcome fetchAndExtract(Ean ean, URI productUrl, RetailerHttp http) {
        adapterCalls.add(id);
        try {
          http.get(productUrl);
        } catch (IOException e) {
          throw new UncheckedIOException(e);
        }
        return new AdapterOutcome.Found(new PageFacts(100L, "RON", null, null, null));
      }
    };
  }

  private RowStatus statusOfA() {
    return service().lookup(Ean.parse(TestRegistries.EAN_A)).rows().stream()
        .filter(row -> row.retailerId().equals("a.example"))
        .findFirst()
        .orElseThrow()
        .status();
  }

  /**
   * Runs a Lookup every second from now on and returns how long it took until a.example showed
   * {@code expected}; gives up after 120 s so a broken switch fails the bound instead of hanging.
   */
  private Duration secondsUntilAIs(RowStatus expected) {
    Duration waited = Duration.ZERO;
    while (statusOfA() != expected && waited.compareTo(Duration.ofSeconds(120)) < 0) {
      clock.advance(Duration.ofSeconds(1));
      waited = waited.plusSeconds(1);
    }
    return waited;
  }

  @Test
  @Tag("AC-2.3.1")
  void ac_2_3_1_stopWrittenBetweenPollsShowsWithin60sOfBusyLookups() throws IOException {
    write(A_ALLOWED);
    assertThat(statusOfA()).isEqualTo(RowStatus.FOUND);

    // The operator edits the file 1 s after a poll: the worst case for a 15 s interval.
    clock.advance(Duration.ofSeconds(1));
    write(A_STOPPED);
    assertThat(secondsUntilAIs(RowStatus.DISABLED))
        .isLessThanOrEqualTo(Duration.ofSeconds(15))
        .isLessThanOrEqualTo(SIXTY_SECONDS);

    clock.advance(Duration.ofSeconds(1));
    write(A_ALLOWED);
    assertThat(secondsUntilAIs(RowStatus.FOUND))
        .isLessThanOrEqualTo(Duration.ofSeconds(15))
        .isLessThanOrEqualTo(SIXTY_SECONDS);
  }

  @Test
  @Tag("AC-2.3.1")
  void ac_2_3_1_firstLookupAfterIdleMinuteSeesTheStopAtOnce() throws IOException {
    write(A_ALLOWED);
    assertThat(statusOfA()).isEqualTo(RowStatus.FOUND);

    write(A_STOPPED);
    clock.advance(SIXTY_SECONDS);
    assertThat(statusOfA()).isEqualTo(RowStatus.DISABLED);
    assertThat(adapterCalls).containsExactly("a.example");
    assertThat(requests).containsExactly(URI.create("https://a.example/p/1"));
  }

  @Test
  @Tag("AC-2.3.2")
  void ac_2_3_2_fileAppearingAfterFailClosedStartAllowsOnNextPoll() throws IOException {
    assertThat(statusOfA()).isEqualTo(RowStatus.DISABLED);
    assertThat(requests).isEmpty();

    write(A_ALLOWED);
    clock.advance(Duration.ofSeconds(15));
    assertThat(statusOfA()).isEqualTo(RowStatus.FOUND);
  }

  @Test
  @Tag("AC-2.3.3")
  void ac_2_3_3_fixedFileAfterRejectedReadIsAppliedOnNextPoll(CapturedOutput output)
      throws IOException {
    write(A_ALLOWED);
    assertThat(statusOfA()).isEqualTo(RowStatus.FOUND);

    write("::: not yaml [");
    clock.advance(Duration.ofSeconds(15));
    assertThat(statusOfA()).isEqualTo(RowStatus.FOUND);

    write(A_STOPPED);
    clock.advance(Duration.ofSeconds(15));
    assertThat(statusOfA()).isEqualTo(RowStatus.DISABLED);
    assertThat(output.getOut().lines().filter(l -> l.contains("runtime_config_rejected")))
        .hasSize(1);
  }

  @Test
  @Tag("AC-2.3.3")
  void ac_2_3_3_fileDeletedAfterGoodKeepsLastGoodAndLogsError(CapturedOutput output)
      throws IOException {
    write(A_ALLOWED);
    assertThat(statusOfA()).isEqualTo(RowStatus.FOUND);

    Files.delete(dir.resolve("runtime.yaml"));
    clock.advance(Duration.ofSeconds(15));
    assertThat(statusOfA()).isEqualTo(RowStatus.FOUND);
    assertThat(output.getOut())
        .contains("\"runtime_config_rejected\"")
        .contains("\"MISSING\"")
        .contains("\"ERROR\"");
  }

  @Test
  @Tag("AC-2.3.4")
  void ac_2_3_4_everyRetailerAllowedSendsRequestsOnlyToTheApprovedEnabledOne() throws IOException {
    write(
        """
        retailers:
          a.example: {fetching: allowed}
          b.example: {fetching: allowed}
          c.example: {fetching: allowed}
        """);
    LookupResult result = service().lookup(Ean.parse(TestRegistries.EAN_A));

    assertThat(result.rows()).extracting(LookupRow::retailerId).doesNotContain("c.example");
    assertThat(requests).containsExactly(URI.create("https://a.example/p/1"));
  }

  @Test
  @Tag("AC-2.3.4")
  void ac_2_3_4_onlyTheProposedRetailerAllowedGivesNoRowForItAndNoRequestAtAll()
      throws IOException {
    write("retailers:\n  c.example: {fetching: allowed}\n");
    for (int poll = 0; poll < 4; poll++) {
      LookupResult result = service().lookup(Ean.parse(TestRegistries.EAN_A));
      assertThat(result.rows())
          .extracting(LookupRow::retailerId, LookupRow::status)
          .containsExactly(
              tuple("a.example", RowStatus.DISABLED), tuple("b.example", RowStatus.DISABLED));
      clock.advance(Duration.ofSeconds(15));
    }
    assertThat(adapterCalls).isEmpty();
    assertThat(requests).isEmpty();
  }
}
