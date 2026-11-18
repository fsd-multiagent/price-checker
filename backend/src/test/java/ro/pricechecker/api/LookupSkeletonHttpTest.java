package ro.pricechecker.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import ro.pricechecker.adapter.AdapterOutcome;
import ro.pricechecker.adapter.RetailerAdapter;
import ro.pricechecker.adapter.RetailerHttp;
import ro.pricechecker.cache.ResultCache;
import ro.pricechecker.ean.Ean;
import ro.pricechecker.lookup.AdapterCatalog;
import ro.pricechecker.lookup.LookupLog;
import ro.pricechecker.lookup.LookupService;
import ro.pricechecker.registry.Registry;
import ro.pricechecker.registry.TestRegistries;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * AC 1.6.4 gap found by Eufrosina (QA): through the real {@link LookupService}, an adapter that
 * throws still yields a 2xx whose non-FOUND rows carry only the four allowed fields.
 */
@WebMvcTest(LookupController.class)
@Import({LookupLog.class, LookupSkeletonHttpTest.Wiring.class})
class LookupSkeletonHttpTest {

  @TestConfiguration(proxyBeanMethods = false)
  static class Wiring {
    @Bean
    Clock clock() {
      return Clock.fixed(Instant.parse("2026-11-18T10:15:30Z"), ZoneOffset.UTC);
    }

    @Bean
    LookupService lookupService(Clock clock, LookupLog log) {
      Registry registry = TestRegistries.mixed();
      List<RetailerAdapter> adapters =
          List.of(throwing("a.example"), throwing("b.example"), throwing("c.example"));
      RetailerHttp http = url -> "";
      return new LookupService(
          registry,
          new AdapterCatalog(adapters, registry),
          Optional.of(http),
          clock,
          TestRegistries.runtimeAllowing(clock, "a.example", "b.example", "c.example"),
          TestRegistries.blockStore(TestRegistries.runtimeAllowing(clock), clock),
          new ResultCache<>(Duration.ofMinutes(15), clock),
          log);
    }

    private static RetailerAdapter throwing(String id) {
      return new RetailerAdapter() {
        @Override
        public String retailerId() {
          return id;
        }

        @Override
        public AdapterOutcome fetchAndExtract(Ean ean, URI productUrl, RetailerHttp http) {
          throw new IllegalStateException("adapter exploded");
        }
      };
    }
  }

  private final MockMvc mvc;

  @Autowired
  LookupSkeletonHttpTest(MockMvc mvc) {
    this.mvc = mvc;
  }

  @Test
  @Tag("AC-1.6.4")
  void ac_1_6_4_throwingAdapterStillGives200WithFourFieldRows() throws Exception {
    String body =
        mvc.perform(get("/api/lookup").param("ean", TestRegistries.EAN_A))
            .andExpect(status().is2xxSuccessful())
            .andReturn()
            .getResponse()
            .getContentAsString();
    JsonNode rows = JsonMapper.builder().build().readTree(body).get("rows");
    assertThat(rows.size()).isEqualTo(2);
    assertThat(rows.get(0).get("retailerId").stringValue()).isEqualTo("a.example");
    assertThat(rows.get(0).get("status").stringValue()).isEqualTo("UNAVAILABLE");
    assertThat(rows.get(1).get("retailerId").stringValue()).isEqualTo("b.example");
    assertThat(rows.get(1).get("status").stringValue()).isEqualTo("DISABLED");
    rows.forEach(row -> {
      assertThat(row.propertyNames())
          .containsExactlyInAnyOrder("retailerId", "retailerName", "status", "lowest");
      assertThat(row.get("lowest").booleanValue()).isFalse();
    });
    assertThat(body).doesNotContain("adapter exploded");
  }
}
