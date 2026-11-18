package ro.pricechecker.lookup;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import ro.pricechecker.ean.EanError;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * AC 1.4.3 gaps found by Eufrosina (QA): every code is logged the same way, and the public API
 * offers no way to pass free text, so the allowlist cannot be bypassed by a caller.
 */
@Tag("AC-1.4.3")
@ExtendWith(OutputCaptureExtension.class)
class LookupLogAcceptanceGapsTest {

  private static final Instant NOW = Instant.parse("2026-11-18T10:15:30Z");

  @Test
  void ac_1_4_3_everyCodeIsOneJsonLineWithTheCodeInOutcomeAndNoEan(CapturedOutput output) {
    LookupLog log = new LookupLog(Clock.fixed(NOW, ZoneOffset.UTC));
    for (EanError error : EanError.values()) {
      log.eanRejected(error);
    }

    List<String> lines = output.getOut().lines().toList();
    assertThat(lines).hasSize(EanError.values().length);
    for (int i = 0; i < lines.size(); i++) {
      JsonNode line = JsonMapper.builder().build().readTree(lines.get(i));
      List<String> keys = new ArrayList<>(line.propertyNames());
      assertThat(LookupLogTest.ALLOWLIST).containsAll(keys);
      assertThat(keys).doesNotContain("ean", "retailer", "durationMs");
      assertThat(line.get("event").stringValue()).isEqualTo("ean_rejected");
      assertThat(line.get("outcome").stringValue()).isEqualTo(EanError.values()[i].name());
      assertThat(line.get("ts").stringValue()).isEqualTo(NOW.toString());
    }
  }

  @Test
  void ac_1_4_3_publicApiAcceptsNoFreeText() {
    List<Method> api =
        Arrays.stream(LookupLog.class.getDeclaredMethods())
            .filter(m -> !Modifier.isPrivate(m.getModifiers()) && !m.isSynthetic())
            .toList();
    assertThat(api).isNotEmpty();
    for (Method m : api) {
      for (Class<?> p : m.getParameterTypes()) {
        assertThat(p.isEnum() || p.isPrimitive())
            .as("%s takes %s; only enums or primitives may reach a log line", m, p.getName())
            .isTrue();
      }
    }
  }
}
