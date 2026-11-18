package ro.pricechecker;

import java.time.Clock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.convert.DurationStyle;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import ro.pricechecker.cache.ResultCache;
import ro.pricechecker.lookup.RetailerEan;
import ro.pricechecker.lookup.Verdict;

/**
 * Wires the Lookup cache (FR-6, AC-6.1 to 6.5): property {@code price-checker.cache-ttl}, env
 * {@code PRICE_CHECKER_CACHE_TTL}, default {@code 15m} (simple form such as {@code 30s}, or
 * ISO-8601 such as {@code PT15M}). Lives here so neither {@code lookup} nor {@code cache} reads a
 * property. A blank, unparseable or non-positive value stops the application.
 */
@Configuration(proxyBeanMethods = false)
public class LookupCacheConfig {

  @Bean
  ResultCache<RetailerEan, Verdict> lookupCache(
      @Value("${price-checker.cache-ttl:15m}") String ttl, Clock clock) {
    return new ResultCache<>(DurationStyle.detectAndParse(ttl.strip()), clock);
  }
}
