package ro.pricechecker;

import java.nio.file.Path;
import java.time.Clock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import ro.pricechecker.lookup.LookupLog;
import ro.pricechecker.registry.BlockStore;
import ro.pricechecker.registry.RuntimeSwitch;

/**
 * Wires the Block state to its file (AD-9, FR-11): property {@code price-checker.blocks-file}, env
 * {@code PRICE_CHECKER_BLOCKS_FILE}. Each new Block is logged at {@code WARN} through {@link
 * LookupLog} (AD-14). Lives here so {@code registry} reads no property and has no logger. An
 * unreadable or unparseable file stops the application (fail closed).
 */
@Configuration(proxyBeanMethods = false)
public class BlockStoreConfig {

  @Bean
  BlockStore blockStore(
      @Value("${price-checker.blocks-file:/opt/price-checker/state/blocks.json}") String file,
      RuntimeSwitch runtimeSwitch,
      Clock clock,
      LookupLog log) {
    return new BlockStore(Path.of(file), runtimeSwitch, clock, log::blocked);
  }
}
