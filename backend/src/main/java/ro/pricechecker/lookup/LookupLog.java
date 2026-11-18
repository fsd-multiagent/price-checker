package ro.pricechecker.lookup;

import java.time.Clock;
import java.time.Instant;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Component;
import ro.pricechecker.ean.EanError;
import tools.jackson.databind.json.JsonMapper;

/**
 * The one application log (AD-14, ADR 0017): JSON lines on stdout holding only the allowlisted
 * fields. Callers never pass free text, so no raw input, IP or user agent can reach a line.
 */
@Component
public class LookupLog {

  /** The allowlist; nothing else can be written. */
  private enum Field {
    TS("ts"),
    LEVEL("level"),
    EVENT("event"),
    EAN("ean"),
    RETAILER("retailer"),
    OUTCOME("outcome"),
    DURATION_MS("durationMs");

    private final String key;

    Field(String key) {
      this.key = key;
    }
  }

  private static final JsonMapper JSON = JsonMapper.builder().build();

  private final Clock clock;

  public LookupLog(Clock clock) {
    this.clock = clock;
  }

  /** A rejected input: the error code travels in {@code outcome}; the input is never logged. */
  public void eanRejected(EanError error) {
    Map<Field, Object> fields = new EnumMap<>(Field.class);
    fields.put(Field.LEVEL, "INFO");
    fields.put(Field.EVENT, "ean_rejected");
    fields.put(Field.OUTCOME, error.name());
    write(fields);
  }

  private void write(Map<Field, Object> fields) {
    Map<String, Object> line = new LinkedHashMap<>();
    line.put(Field.TS.key, Instant.now(clock).toString());
    fields.forEach((field, value) -> line.put(field.key, value));
    System.out.println(JSON.writeValueAsString(line));
  }
}
