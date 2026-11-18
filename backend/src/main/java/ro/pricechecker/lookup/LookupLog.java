package ro.pricechecker.lookup;

import java.time.Clock;
import java.time.Instant;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;
import ro.pricechecker.ean.Ean;
import ro.pricechecker.ean.EanError;
import ro.pricechecker.registry.BlockStore;
import ro.pricechecker.registry.RuntimeSwitch;
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

  /** A register domain: the only text a {@code retailer} field may hold. */
  private static final Pattern RETAILER_ID =
      Pattern.compile("[a-z0-9-]{1,63}(?:\\.[a-z0-9-]{1,63}){1,4}");

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

  /**
   * A rejected {@code runtime.yaml} read (AD-9): only the reason code travels in {@code outcome};
   * never the file content or an exception message.
   */
  public void runtimeConfigRejected(RuntimeSwitch.Rejection reason) {
    Map<Field, Object> fields = new EnumMap<>(Field.class);
    fields.put(Field.LEVEL, "ERROR");
    fields.put(Field.EVENT, "runtime_config_rejected");
    fields.put(Field.OUTCOME, reason.name());
    write(fields);
  }

  /**
   * A new Block (FR-11, AC-11.2, AD-14): {@code WARN}, the retailer id and the cause in {@code
   * outcome}; never a URL, page content or exception message. A {@code retailer} that is not shaped
   * like a register domain is dropped, so no free text reaches the line.
   */
  public void blocked(String retailer, BlockStore.Reason reason) {
    Map<Field, Object> fields = new EnumMap<>(Field.class);
    fields.put(Field.LEVEL, "WARN");
    fields.put(Field.EVENT, "retailer_blocked");
    if (retailer != null && RETAILER_ID.matcher(retailer).matches()) {
      fields.put(Field.RETAILER, retailer);
    }
    fields.put(Field.OUTCOME, reason.name());
    write(fields);
  }

  /**
   * One retailer row of a Lookup (AC-3.6, AC-N3.7): the validated EAN, the retailer id, the row
   * status in {@code outcome} and how long the row took. Nothing else.
   */
  public void row(String retailer, Ean ean, RowStatus status, long durationMs) {
    Map<Field, Object> fields = new EnumMap<>(Field.class);
    fields.put(Field.LEVEL, "INFO");
    fields.put(Field.EVENT, "lookup_row");
    fields.put(Field.EAN, ean.value());
    if (retailer != null && RETAILER_ID.matcher(retailer).matches()) {
      fields.put(Field.RETAILER, retailer);
    }
    fields.put(Field.OUTCOME, status.name());
    fields.put(Field.DURATION_MS, Math.max(0, durationMs));
    write(fields);
  }

  private void write(Map<Field, Object> fields) {
    Map<String, Object> line = new LinkedHashMap<>();
    line.put(Field.TS.key, Instant.now(clock).toString());
    fields.forEach((field, value) -> line.put(field.key, value));
    System.out.println(JSON.writeValueAsString(line));
  }
}
