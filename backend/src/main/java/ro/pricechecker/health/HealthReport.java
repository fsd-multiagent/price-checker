package ro.pricechecker.health;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * The Health check report (story 3.5): {@code report.json} in the report directory. It passes only
 * when {@code runtime.yaml} was read and no check failed; skipped checks do not fail it.
 */
public record HealthReport(String runtimeConfig, List<CheckResult> checks) {

  static final String FILE = "report.json";

  private static final JsonMapper JSON =
      JsonMapper.builder().enable(SerializationFeature.INDENT_OUTPUT).build();

  public HealthReport {
    checks = List.copyOf(checks);
  }

  public boolean passed() {
    return runtimeConfig == null
        && checks.stream().noneMatch(c -> c.outcome() == CheckResult.Outcome.FAILED);
  }

  /** Writes {@code report.json} into {@code dir}, creating it when needed. */
  void write(Path dir) throws IOException {
    Map<String, Object> root = new LinkedHashMap<>();
    root.put("passed", passed());
    root.put("runtimeConfig", runtimeConfig);
    List<Map<String, Object>> list = new ArrayList<>();
    for (CheckResult c : checks) {
      Map<String, Object> check = new LinkedHashMap<>();
      check.put("retailer", c.retailer());
      check.put("ean", c.ean());
      check.put("outcome", c.outcome().name());
      if (c.reason() != null) {
        check.put("reason", c.reason());
      }
      check.put("status", c.status());
      check.put("missing", c.missing());
      check.put("blocked", c.blocked());
      if (c.page() != null) {
        check.put("page", c.page());
      }
      list.add(check);
    }
    root.put("checks", list);
    Files.createDirectories(dir);
    Files.writeString(dir.resolve(FILE), JSON.writeValueAsString(root), StandardCharsets.UTF_8);
  }
}
