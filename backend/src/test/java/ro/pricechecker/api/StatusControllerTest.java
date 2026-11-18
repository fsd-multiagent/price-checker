package ro.pricechecker.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;
import ro.pricechecker.lookup.LookupLog;
import ro.pricechecker.registry.BlockStore;
import ro.pricechecker.registry.MutableClock;
import ro.pricechecker.registry.Registry;
import ro.pricechecker.registry.RuntimeSwitch;
import ro.pricechecker.registry.TestRegistries;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@code GET /api/status} over {@link TestRegistries#mixed()} (a.example Approved and enabled,
 * b.example Approved with its adapter disabled, c.example Proposed), a real {@link RuntimeSwitch}
 * and {@link BlockStore}, and a fixed clock. Each test gets a fresh context, so Blocks and the
 * Kill-switch file do not leak between tests; the first request reads {@code runtime.yaml}.
 */
@WebMvcTest(StatusController.class)
@Import({LookupLog.class, StatusControllerTest.Wiring.class})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class StatusControllerTest {

  static final Instant NOW = Instant.parse("2026-11-18T10:15:30Z");

  /** The {@code runtime.yaml} the test's {@link RuntimeSwitch} reads. */
  record RuntimeFile(Path path) {
    void write(String yaml) throws IOException {
      Files.writeString(path, yaml);
    }
  }

  @TestConfiguration(proxyBeanMethods = false)
  static class Wiring {
    @Bean
    MutableClock clock() {
      return new MutableClock(NOW);
    }

    @Bean
    Registry registry() {
      return TestRegistries.mixed();
    }

    @Bean
    RuntimeFile runtimeFile() throws IOException {
      Path file = Files.createTempFile("runtime", ".yaml");
      file.toFile().deleteOnExit();
      RuntimeFile runtime = new RuntimeFile(file);
      runtime.write(
          "retailers:\n"
              + "  a.example: {fetching: allowed}\n"
              + "  b.example: {fetching: allowed}\n"
              + "  c.example: {fetching: allowed}\n");
      return runtime;
    }

    @Bean
    RuntimeSwitch runtimeSwitch(RuntimeFile file, Clock clock) {
      return new RuntimeSwitch(file.path(), clock);
    }

    @Bean
    BlockStore blockStore(RuntimeSwitch runtime, Clock clock) {
      return TestRegistries.blockStore(runtime, clock);
    }
  }

  private final MockMvc mvc;
  private final RuntimeFile runtimeFile;
  private final RuntimeSwitch runtime;
  private final BlockStore blocks;
  private final MutableClock clock;

  @Autowired
  StatusControllerTest(
      MockMvc mvc,
      RuntimeFile runtimeFile,
      RuntimeSwitch runtime,
      BlockStore blocks,
      MutableClock clock) {
    this.mvc = mvc;
    this.runtimeFile = runtimeFile;
    this.runtime = runtime;
    this.blocks = blocks;
    this.clock = clock;
  }

  private JsonNode retailers() throws Exception {
    String body =
        mvc.perform(get("/api/status"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    JsonNode root = JsonMapper.builder().build().readTree(body);
    assertThat(root.propertyNames()).containsExactly("retailers");
    return root.get("retailers");
  }

  private static JsonNode only(JsonNode retailers, String id) {
    for (JsonNode retailer : retailers) {
      if (retailer.get("retailerId").stringValue().equals(id)) {
        return retailer;
      }
    }
    throw new AssertionError("no entry for " + id);
  }

  @Test
  @Tag("AC-2.9.1")
  void ac_2_9_1_listsOnlyApprovedRetailersWithoutPrices() throws Exception {
    JsonNode retailers = retailers();

    assertThat(retailers.size()).isEqualTo(2);
    JsonNode a = retailers.get(0);
    JsonNode b = retailers.get(1);
    assertThat(a.get("retailerId").stringValue()).isEqualTo("a.example");
    assertThat(a.get("retailerName").stringValue()).isEqualTo("a.example");
    assertThat(a.get("enabled").booleanValue()).isTrue();
    assertThat(a.get("blocked").booleanValue()).isFalse();
    assertThat(b.get("retailerId").stringValue()).isEqualTo("b.example");
    assertThat(b.get("retailerName").stringValue()).isEqualTo("b.example");
    assertThat(b.get("enabled").booleanValue()).isFalse();
    assertThat(b.get("blocked").booleanValue()).isFalse();
    retailers.forEach(retailer ->
        assertThat(retailer.propertyNames())
            .containsExactlyInAnyOrder("retailerId", "retailerName", "enabled", "blocked"));
  }

  @Test
  @Tag("AC-2.9.1")
  void ac_2_9_1_killSwitchStoppedRetailerIsNotEnabled() throws Exception {
    runtimeFile.write("retailers:\n  b.example: {fetching: allowed}\n");

    JsonNode a = only(retailers(), "a.example");

    assertThat(a.get("enabled").booleanValue()).isFalse();
    assertThat(a.get("blocked").booleanValue()).isFalse();
  }

  @Test
  @Tag("AC-2.9.1")
  void ac_2_9_1_unparseableRuntimeFileStillAnswersWithEveryRetailerNotEnabled() throws Exception {
    runtimeFile.write("retailers: [not, a, map\n");

    JsonNode retailers = retailers();

    assertThat(retailers.size()).isEqualTo(2);
    retailers.forEach(retailer -> assertThat(retailer.get("enabled").booleanValue()).isFalse());
  }

  @Test
  @Tag("AC-2.9.2")
  void ac_2_9_2_activeBlockIsReported() throws Exception {
    runtime.refresh();
    blocks.record("a.example", BlockStore.Reason.HTTP_403);

    JsonNode a = only(retailers(), "a.example");

    assertThat(a.get("blocked").booleanValue()).isEqualTo(blocks.isBlocked("a.example")).isTrue();
    assertThat(a.get("enabled").booleanValue()).isTrue();
    assertThat(a.has("notBefore")).isFalse();
  }

  @Test
  @Tag("AC-2.9.2")
  void ac_2_9_2_futureNotBeforeIsReportedInUtc() throws Exception {
    blocks.recordRetryAfter("a.example", NOW.plusSeconds(60));

    JsonNode a = only(retailers(), "a.example");

    assertThat(a.get("notBefore").stringValue()).isEqualTo("2026-11-18T10:16:30Z");
    assertThat(Instant.parse(a.get("notBefore").stringValue()))
        .isEqualTo(blocks.notBefore("a.example").orElseThrow());
    assertThat(a.get("blocked").booleanValue()).isFalse();
    assertThat(a.get("enabled").booleanValue()).isTrue();
  }

  @Test
  @Tag("AC-2.9.2")
  void ac_2_9_2_pastNotBeforeIsOmitted() throws Exception {
    blocks.recordRetryAfter("a.example", NOW.minusSeconds(1));

    JsonNode a = only(retailers(), "a.example");

    assertThat(a.has("notBefore")).isFalse();
  }

  @Test
  @Tag("AC-2.9.2")
  void ac_2_9_2_notBeforeEqualToNowIsOmitted() throws Exception {
    blocks.recordRetryAfter("a.example", NOW);

    assertThat(only(retailers(), "a.example").has("notBefore")).isFalse();
  }

  @Test
  @Tag("AC-2.9.2")
  void ac_2_9_2_runnerBlockAndRunnerNotBeforeAreReported() throws Exception {
    runtimeFile.write(
        "retailers:\n"
            + "  a.example:\n"
            + "    fetching: allowed\n"
            + "    runnerBlock: {blockedAt: \"2026-11-18T04:00:00Z\", reason: CHALLENGE}\n"
            + "  b.example:\n"
            + "    fetching: allowed\n"
            + "    notBefore: \"2026-11-18T11:00:00Z\"\n");

    JsonNode retailers = retailers();
    JsonNode a = only(retailers, "a.example");
    JsonNode b = only(retailers, "b.example");

    assertThat(a.get("blocked").booleanValue()).isTrue();
    assertThat(a.has("notBefore")).isFalse();
    assertThat(b.get("blocked").booleanValue()).isFalse();
    assertThat(b.get("notBefore").stringValue()).isEqualTo("2026-11-18T11:00:00Z");
  }

  @Test
  @Tag("AC-2.9.1")
  void ac_2_9_1_blockedNonApprovedRetailerNeverAppears() throws Exception {
    runtime.refresh();
    blocks.record("c.example", BlockStore.Reason.HTTP_403);
    blocks.recordRetryAfter("c.example", NOW.plusSeconds(60));

    JsonNode retailers = retailers();

    assertThat(retailers.size()).isEqualTo(2);
    retailers.forEach(
        retailer -> assertThat(retailer.get("retailerId").stringValue()).isNotEqualTo("c.example"));
  }

  @Test
  @Tag("AC-2.9.2")
  void ac_2_9_2_blockOnAdapterDisabledRetailerIsStillReported() throws Exception {
    runtime.refresh();
    blocks.record("b.example", BlockStore.Reason.CHALLENGE);

    JsonNode b = only(retailers(), "b.example");

    assertThat(b.get("enabled").booleanValue()).isFalse();
    assertThat(b.get("blocked").booleanValue()).isTrue();
  }

  @Test
  @Tag("AC-2.9.2")
  void ac_2_9_2_blockAndFutureNotBeforeAreBothReported() throws Exception {
    runtime.refresh();
    blocks.recordRetryAfter("a.example", NOW.plusSeconds(120));
    blocks.record("a.example", BlockStore.Reason.HTTP_403);

    JsonNode a = only(retailers(), "a.example");

    assertThat(a.get("blocked").booleanValue()).isTrue();
    assertThat(a.get("notBefore").stringValue()).isEqualTo("2026-11-18T10:17:30Z");
  }

  @Test
  @Tag("AC-2.9.2")
  void ac_2_9_2_clearedBlockIsNoLongerReported() throws Exception {
    runtime.refresh();
    blocks.record("a.example", BlockStore.Reason.HTTP_403);
    assertThat(only(retailers(), "a.example").get("blocked").booleanValue()).isTrue();

    runtimeFile.write(
        "retailers:\n"
            + "  a.example: {fetching: allowed, clearGeneration: 1}\n"
            + "  b.example: {fetching: allowed}\n");
    clock.advance(Duration.ofSeconds(15));

    JsonNode a = only(retailers(), "a.example");

    assertThat(a.get("blocked").booleanValue()).isEqualTo(blocks.isBlocked("a.example")).isFalse();
  }

  @Test
  @Tag("AC-2.9.2")
  void ac_2_9_2_notBeforeDisappearsOnceTheClockPassesIt() throws Exception {
    blocks.recordRetryAfter("a.example", NOW.plusSeconds(60));
    assertThat(only(retailers(), "a.example").has("notBefore")).isTrue();

    clock.advance(Duration.ofSeconds(61));

    assertThat(only(retailers(), "a.example").has("notBefore")).isFalse();
  }

  @Test
  @Tag("AC-2.9.2")
  void ac_2_9_2_laterOfOwnAndRunnerNotBeforeIsReported() throws Exception {
    runtimeFile.write(
        "retailers:\n"
            + "  a.example: {fetching: allowed, notBefore: \"2026-11-18T10:20:00Z\"}\n"
            + "  b.example: {fetching: allowed, notBefore: \"2026-11-18T10:20:00Z\"}\n");
    blocks.recordRetryAfter("a.example", Instant.parse("2026-11-18T10:30:00Z"));
    blocks.recordRetryAfter("b.example", Instant.parse("2026-11-18T10:16:00Z"));

    JsonNode retailers = retailers();

    assertThat(only(retailers, "a.example").get("notBefore").stringValue())
        .isEqualTo("2026-11-18T10:30:00Z");
    assertThat(only(retailers, "b.example").get("notBefore").stringValue())
        .isEqualTo("2026-11-18T10:20:00Z");
  }
}
