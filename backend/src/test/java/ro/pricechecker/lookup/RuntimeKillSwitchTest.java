package ro.pricechecker.lookup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.groups.Tuple.tuple;

import java.io.IOException;
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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The Kill switch through {@link LookupService} (FR-7, S-8, AD-9, AD-10): the I/O matrix of story
 * 2.3. Registry: a.example (Approved, enabled), b.example (Approved, disabled), c.example
 * (Proposed).
 */
@ExtendWith(OutputCaptureExtension.class)
class RuntimeKillSwitchTest {

  private static final String A_ALLOWED = "retailers:\n  a.example: {fetching: allowed}\n";
  private static final String A_STOPPED = "retailers:\n  a.example: {fetching: stopped}\n";

  @TempDir Path dir;

  private final MutableClock clock = new MutableClock(Instant.parse("2026-11-18T10:00:00Z"));
  private final List<String> calls = new ArrayList<>();
  private LookupService service;

  private Path file() {
    return dir.resolve("runtime.yaml");
  }

  private void write(String yaml) throws IOException {
    Files.writeString(file(), yaml);
  }

  private LookupService service() {
    if (service == null) {
      Registry registry = TestRegistries.mixed();
      List<RetailerAdapter> adapters =
          List.of(adapter("a.example"), adapter("b.example"), adapter("c.example"));
      RetailerHttp http = url -> "";
      service =
          new LookupService(
              registry,
              new AdapterCatalog(adapters, registry),
              Optional.of(http),
              clock,
              new RuntimeSwitch(file(), clock),
              TestRegistries.blockStore(TestRegistries.runtimeAllowing(clock), clock),
              new ResultCache<>(Duration.ofMinutes(15), clock),
              new LookupLog(clock));
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
        calls.add(id);
        return new AdapterOutcome.Found(new PageFacts(100L, "RON", null, null, null));
      }
    };
  }

  private RowStatus statusOfA() {
    return lookup().rows().stream()
        .filter(row -> row.retailerId().equals("a.example"))
        .findFirst()
        .orElseThrow()
        .status();
  }

  private LookupResult lookup() {
    return service().lookup(Ean.parse(TestRegistries.EAN_A));
  }

  private static List<JsonNode> logLines(CapturedOutput output) {
    JsonMapper json = JsonMapper.builder().build();
    // Story 2.8: every Lookup also logs one lookup_row line per retailer; only rejections count
    // here.
    return output
        .getOut()
        .lines()
        .map(json::readTree)
        .filter(n -> "runtime_config_rejected".equals(n.path("event").stringValue()))
        .toList();
  }

  @Test
  @Tag("AC-2.3.1")
  void ac_2_3_1_allowedRowIsBuiltAsBefore() throws IOException {
    write(A_ALLOWED);
    assertThat(statusOfA()).isEqualTo(RowStatus.FOUND);
    assertThat(calls).containsExactly("a.example");
  }

  @Test
  @Tag("AC-2.3.1")
  void ac_2_3_1_stoppedRowIsDisabledWithoutAdapterCall() throws IOException {
    write(A_STOPPED);
    assertThat(statusOfA()).isEqualTo(RowStatus.DISABLED);
    assertThat(calls).isEmpty();
  }

  @Test
  @Tag("AC-2.3.1")
  void ac_2_3_1_stopThenAllowEachVisibleAfter15s() throws IOException {
    write(A_ALLOWED);
    assertThat(statusOfA()).isEqualTo(RowStatus.FOUND);

    write(A_STOPPED);
    clock.advance(Duration.ofSeconds(15));
    assertThat(statusOfA()).isEqualTo(RowStatus.DISABLED);
    assertThat(calls).containsExactly("a.example");

    write(A_ALLOWED);
    clock.advance(Duration.ofSeconds(15));
    assertThat(statusOfA()).isEqualTo(RowStatus.FOUND);
    // Story 2.8: the FOUND verdict from the first Lookup is still within the cache TTL.
    assertThat(calls).containsExactly("a.example");
  }

  @Test
  @Tag("AC-2.3.1")
  void ac_2_3_1_changeNotDueAfter14sKeepsOldSettings() throws IOException {
    write(A_ALLOWED);
    assertThat(statusOfA()).isEqualTo(RowStatus.FOUND);

    write(A_STOPPED);
    clock.advance(Duration.ofSeconds(14));
    assertThat(statusOfA()).isEqualTo(RowStatus.FOUND);
  }

  @Test
  @Tag("AC-2.3.2")
  void ac_2_3_2_retailerMissingFromFileIsDisabled() throws IOException {
    write("retailers:\n  b.example: {fetching: allowed}\n");
    assertThat(statusOfA()).isEqualTo(RowStatus.DISABLED);
    assertThat(calls).isEmpty();
  }

  @Test
  @Tag("AC-2.3.2")
  void ac_2_3_2_missingFileDisablesEveryRowAndLogsEachRejectedRead(CapturedOutput output) {
    assertThat(lookup().rows())
        .extracting(LookupRow::retailerId, LookupRow::status)
        .containsExactly(
            tuple("a.example", RowStatus.DISABLED), tuple("b.example", RowStatus.DISABLED));
    clock.advance(Duration.ofSeconds(15));
    lookup();
    assertThat(calls).isEmpty();
    assertThat(logLines(output))
        .extracting(line -> line.get("outcome").stringValue())
        .containsExactly("MISSING", "MISSING");
  }

  @Test
  @Tag("AC-2.3.2")
  void ac_2_3_2_garbageWithNoGoodReadDisablesEveryRow(CapturedOutput output) throws IOException {
    write("::: not yaml [");
    assertThat(lookup().rows()).extracting(LookupRow::status).containsOnly(RowStatus.DISABLED);
    assertThat(calls).isEmpty();
    assertThat(logLines(output)).hasSize(1);
  }

  @Test
  @Tag("AC-2.3.3")
  void ac_2_3_3_garbageAfterGoodKeepsLastGoodAndLogsOneAllowlistedError(CapturedOutput output)
      throws IOException {
    write(A_ALLOWED);
    assertThat(statusOfA()).isEqualTo(RowStatus.FOUND);
    assertThat(logLines(output)).isEmpty();

    write("retailers: {a.example: [ \"secret-content\"");
    clock.advance(Duration.ofSeconds(15));
    assertThat(statusOfA()).isEqualTo(RowStatus.FOUND);

    List<JsonNode> lines = logLines(output);
    assertThat(lines).hasSize(1);
    JsonNode line = lines.get(0);
    assertThat(LookupLogTest.ALLOWLIST).containsAll(line.propertyNames());
    assertThat(line.get("level").stringValue()).isEqualTo("ERROR");
    assertThat(line.get("event").stringValue()).isEqualTo("runtime_config_rejected");
    assertThat(line.get("outcome").stringValue()).isEqualTo("UNPARSEABLE");
    assertThat(output.getOut()).doesNotContain("secret-content");
  }

  @Test
  @Tag("AC-2.3.3")
  void ac_2_3_3_badValueRejectsWholeReadAndKeepsLastGood(CapturedOutput output) throws IOException {
    write(A_ALLOWED);
    lookup();

    write("retailers:\n  a.example: {fetching: yes}\n");
    clock.advance(Duration.ofSeconds(15));
    assertThat(statusOfA()).isEqualTo(RowStatus.FOUND);
    assertThat(logLines(output))
        .extracting(line -> line.get("outcome").stringValue())
        .containsExactly("UNPARSEABLE");
  }

  @Test
  @Tag("AC-2.3.4")
  void ac_2_3_4_allowedProposedRetailerHasNoRowAndNoCall() throws IOException {
    write(
        """
        retailers:
          a.example: {fetching: allowed}
          b.example: {fetching: allowed}
          c.example: {fetching: allowed}
        """);
    assertThat(lookup().rows())
        .extracting(LookupRow::retailerId, LookupRow::status)
        .containsExactly(
            tuple("a.example", RowStatus.FOUND), tuple("b.example", RowStatus.DISABLED));
    assertThat(calls).containsExactly("a.example");
  }
}
