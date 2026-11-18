package ro.pricechecker.lookup;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import ro.pricechecker.adapter.AdapterOutcome;
import ro.pricechecker.adapter.RetailerAdapter;
import ro.pricechecker.adapter.RetailerHttp;
import ro.pricechecker.adapter.RetailerHttpBinder;
import ro.pricechecker.cache.ResultCache;
import ro.pricechecker.ean.Ean;
import ro.pricechecker.registry.BlockStore;
import ro.pricechecker.registry.Registry;
import ro.pricechecker.registry.RuntimeSwitch;
import ro.pricechecker.registry.TestRegistries;

/**
 * AC-0.102.4 (issue #102), found by Eufrosina (QA): a Spring context with no {@link
 * RetailerHttpBinder} bean still starts, and its Lookups return {@code UNAVAILABLE} for every
 * loadable row without calling an adapter. Only {@code *.example} retailers (T-3).
 */
class LookupWithoutFetchClientContextTest {

  static final AtomicInteger ADAPTER_CALLS = new AtomicInteger();

  private final ApplicationContextRunner runner =
      new ApplicationContextRunner().withUserConfiguration(Wiring.class, LookupService.class);

  @Test
  @Tag("AC-0.102.4")
  void ac_0_102_4_contextWithoutBinderStartsAndRowsAreUnavailable() {
    ADAPTER_CALLS.set(0);
    runner.run(context -> {
      assertThat(context).hasNotFailed().doesNotHaveBean(RetailerHttpBinder.class);
      List<LookupRow> rows =
          context.getBean(LookupService.class).lookup(Ean.parse(TestRegistries.EAN_A)).rows();
      assertThat(rows).extracting(LookupRow::status).containsOnly(RowStatus.UNAVAILABLE).hasSize(2);
      assertThat(ADAPTER_CALLS).hasValue(0);
    });
  }

  @Configuration(proxyBeanMethods = false)
  static class Wiring {

    @Bean
    Clock clock() {
      return Clock.fixed(Instant.parse("2026-11-18T10:00:00Z"), ZoneOffset.UTC);
    }

    @Bean
    Registry registry() {
      return TestRegistries.twoEnabled(4000);
    }

    @Bean
    AdapterCatalog adapterCatalog(Registry registry) {
      return new AdapterCatalog(
          List.of(counting("a.example"), counting("b.example"), counting("c.example")), registry);
    }

    @Bean
    RuntimeSwitch runtimeSwitch(Clock clock) {
      return TestRegistries.runtimeAllowing(clock, "a.example", "b.example");
    }

    @Bean
    BlockStore blockStore(RuntimeSwitch runtime, Clock clock) {
      return TestRegistries.blockStore(runtime, clock);
    }

    @Bean
    ResultCache<RetailerEan, Verdict> lookupCache(Clock clock) {
      return new ResultCache<>(Duration.ofMinutes(15), clock);
    }

    @Bean
    LookupLog lookupLog(Clock clock) {
      return new LookupLog(clock);
    }

    private static RetailerAdapter counting(String id) {
      return new RetailerAdapter() {
        @Override
        public String retailerId() {
          return id;
        }

        @Override
        public AdapterOutcome fetchAndExtract(Ean ean, URI productUrl, RetailerHttp http) {
          ADAPTER_CALLS.incrementAndGet();
          return new AdapterOutcome.NotFound();
        }
      };
    }
  }
}
