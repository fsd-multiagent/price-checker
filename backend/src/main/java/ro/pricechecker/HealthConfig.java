package ro.pricechecker;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import ro.pricechecker.fetch.FetchClient;
import ro.pricechecker.health.HealthCheck;
import ro.pricechecker.lookup.KnownEanLoad;
import ro.pricechecker.lookup.LookupLog;
import ro.pricechecker.registry.BlockStore;
import ro.pricechecker.registry.Registry;
import ro.pricechecker.registry.RuntimeSwitch;

/**
 * Wires the Health check under profile {@code health} (FR-8, story 3.5): the report goes to {@code
 * price-checker.health.report-dir}. The run starts from a fresh Block state: when {@code
 * price-checker.blocks-file} already exists the application refuses to start, and the file is never
 * deleted here; only {@link BlockStore} creates it (AC 3.5.1).
 */
@Configuration(proxyBeanMethods = false)
@Profile(HealthConfig.PROFILE)
public class HealthConfig {

  /** The one-shot Health check profile. */
  public static final String PROFILE = "health";

  @Bean
  HealthCheck healthCheck(
      @Value("${price-checker.health.report-dir}") String reportDir,
      @Value("${price-checker.blocks-file}") String blocksFile,
      Registry registry,
      RuntimeSwitch runtime,
      BlockStore blocks,
      FetchClient fetch,
      KnownEanLoad load,
      LookupLog log,
      Clock clock) {
    if (Files.exists(Path.of(blocksFile))) {
      throw new IllegalStateException(
          "the health profile needs a fresh blocks file; one already exists");
    }
    return new HealthCheck(registry, runtime, blocks, fetch, load, log, clock, Path.of(reportDir));
  }
}
