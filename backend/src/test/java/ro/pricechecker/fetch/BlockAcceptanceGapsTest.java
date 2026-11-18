package ro.pricechecker.fetch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import ro.pricechecker.lookup.LookupLog;
import ro.pricechecker.registry.BlockStore;
import ro.pricechecker.registry.MutableClock;
import ro.pricechecker.registry.RuntimeSwitch;
import ro.pricechecker.registry.TestRegistries;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Story 2.6 gap found by Eufrosina (QA): the WARN line names the time of the Block, and that time
 * is the one in {@code blocks.json} (AC-11.2). Stub transport, injected clock (T-3, T-5).
 */
@ExtendWith(OutputCaptureExtension.class)
class BlockAcceptanceGapsTest {

  private static final Instant START = Instant.parse("2026-11-18T10:00:00Z");
  private static final URI PAGE = URI.create("https://a.example/p/1");
  private static final UserAgent UA = UserAgent.build("1.0", "https://contact.example/about");

  @TempDir Path dir;

  @Test
  @Tag("AC-2.6.1")
  void ac_2_6_1_warnLineNamesTheRetailerAndTheTimeOfTheBlock(CapturedOutput output)
      throws IOException {
    MutableClock clock = new MutableClock(START);
    Path runtimeFile = dir.resolve("runtime.yaml");
    Path blocksFile = dir.resolve("blocks.json");
    Files.writeString(runtimeFile, "retailers:\n  a.example: {fetching: allowed}\n");
    RuntimeSwitch runtime = new RuntimeSwitch(runtimeFile, clock);
    assertThat(runtime.refresh()).isEmpty();
    BlockStore store = new BlockStore(blocksFile, runtime, clock, new LookupLog(clock)::blocked);
    StubTransport transport = new StubTransport();
    transport.always(StubTransport.status(403));
    FetchClient.BoundHttp http =
        new FetchClient(
                TestRegistries.fetchable(10, 1, 4000),
                runtime,
                store,
                transport,
                clock,
                new RecordingSleeper(clock),
                UA,
                RobotsTest.allowAll(clock))
            .bind("a.example");

    clock.advance(Duration.ofMinutes(7));
    Instant blockedAt = clock.instant();
    assertThat(catchThrowableOfType(FetchFailure.class, () -> http.fetch(PAGE))).isNotNull();

    JsonMapper json = JsonMapper.builder().build();
    List<JsonNode> warns =
        output
            .getOut()
            .lines()
            .filter(l -> l.startsWith("{"))
            .map(json::readTree)
            .filter(n -> "WARN".equals(n.path("level").stringValue()))
            .toList();
    assertThat(warns).hasSize(1);
    assertThat(warns.get(0).get("retailer").stringValue()).isEqualTo("a.example");
    assertThat(Instant.parse(warns.get(0).get("ts").stringValue())).isEqualTo(blockedAt);
    JsonNode entry = json.readTree(Files.readString(blocksFile)).get("retailers").get("a.example");
    assertThat(Instant.parse(entry.get("blockedAt").stringValue())).isEqualTo(blockedAt);
  }
}
