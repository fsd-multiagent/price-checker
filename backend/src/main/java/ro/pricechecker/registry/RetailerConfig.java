package ro.pricechecker.registry;

/**
 * One entry of {@code config/retailers.yaml}. Fields are raw and nullable; {@link ConfigCheck}
 * decides what is valid.
 */
public record RetailerConfig(
    String domain,
    String adapter,
    String resolution,
    Integer minIntervalMs,
    Integer concurrency,
    Integer deadlineMs) {

  public boolean enabled() {
    return "enabled".equals(adapter);
  }
}
