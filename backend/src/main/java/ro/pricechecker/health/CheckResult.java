package ro.pricechecker.health;

import java.util.List;

/**
 * One Known EAN's Health check result (FR-8, story 3.5). {@code reason} and {@code page} are {@code
 * null} when absent; {@code page} is relative to the report directory.
 */
public record CheckResult(
    String retailer,
    String ean,
    Outcome outcome,
    String reason,
    String status,
    List<String> missing,
    boolean blocked,
    String page) {

  /** The verdict of one check. */
  public enum Outcome {
    PASSED,
    FAILED,
    SKIPPED
  }

  public CheckResult {
    missing = List.copyOf(missing);
  }

  CheckResult withPage(String value) {
    return new CheckResult(retailer, ean, outcome, reason, status, missing, blocked, value);
  }
}
