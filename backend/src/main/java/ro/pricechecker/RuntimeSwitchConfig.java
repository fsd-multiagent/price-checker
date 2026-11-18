package ro.pricechecker;

import java.nio.file.Path;
import java.time.Clock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import ro.pricechecker.registry.RuntimeSwitch;

/**
 * Wires the Kill switch to its file (AD-9): property {@code price-checker.runtime-file}, env {@code
 * PRICE_CHECKER_RUNTIME_FILE}. Lives here so {@code registry} reads no property (AC 1.5.4); the
 * file can only stop a retailer, never approve or enable one (AD-8).
 */
@Configuration(proxyBeanMethods = false)
public class RuntimeSwitchConfig {

  @Bean
  RuntimeSwitch runtimeSwitch(
      @Value("${price-checker.runtime-file:/opt/price-checker/config/runtime.yaml}") String file,
      Clock clock) {
    return new RuntimeSwitch(Path.of(file), clock);
  }
}
