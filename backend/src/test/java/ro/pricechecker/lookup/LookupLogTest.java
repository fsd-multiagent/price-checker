package ro.pricechecker.lookup;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import ro.pricechecker.ean.EanError;
import ro.pricechecker.registry.RuntimeSwitch;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** AC 1.4.3: a rejection is one JSON line with only allowlisted keys (ADR 0017). */
@Tag("AC-1.4.3")
@ExtendWith(OutputCaptureExtension.class)
class LookupLogTest {

  static final Set<String> ALLOWLIST =
      Set.of("ts", "level", "event", "ean", "retailer", "outcome", "durationMs");

  private static final Instant NOW = Instant.parse("2026-11-18T10:15:30Z");

  @Test
  void ac_1_4_3_eanRejectedWritesOneAllowlistedLine(CapturedOutput output) {
    new LookupLog(Clock.fixed(NOW, ZoneOffset.UTC)).eanRejected(EanError.EAN_CHECK_DIGIT);

    List<String> lines = output.getOut().lines().toList();
    assertThat(lines).hasSize(1);
    JsonNode line = JsonMapper.builder().build().readTree(lines.get(0));
    List<String> keys = new ArrayList<>(line.propertyNames());
    assertThat(ALLOWLIST).containsAll(keys);
    assertThat(line.get("ts").stringValue()).isEqualTo("2026-11-18T10:15:30Z");
    assertThat(line.get("level").stringValue()).isEqualTo("INFO");
    assertThat(line.get("event").stringValue()).isEqualTo("ean_rejected");
    assertThat(line.get("outcome").stringValue()).isEqualTo("EAN_CHECK_DIGIT");
    assertThat(keys).containsExactly("ts", "level", "event", "outcome");
  }

  @Test
  @Tag("AC-2.3.3")
  void ac_2_3_3_runtimeConfigRejectedWritesOneAllowlistedErrorLine(CapturedOutput output) {
    new LookupLog(Clock.fixed(NOW, ZoneOffset.UTC))
        .runtimeConfigRejected(RuntimeSwitch.Rejection.UNPARSEABLE);

    List<String> lines = output.getOut().lines().toList();
    assertThat(lines).hasSize(1);
    JsonNode line = JsonMapper.builder().build().readTree(lines.get(0));
    List<String> keys = new ArrayList<>(line.propertyNames());
    assertThat(keys).containsExactly("ts", "level", "event", "outcome");
    assertThat(line.get("ts").stringValue()).isEqualTo("2026-11-18T10:15:30Z");
    assertThat(line.get("level").stringValue()).isEqualTo("ERROR");
    assertThat(line.get("event").stringValue()).isEqualTo("runtime_config_rejected");
    assertThat(line.get("outcome").stringValue()).isEqualTo("UNPARSEABLE");
  }
}
