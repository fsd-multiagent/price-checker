package ro.pricechecker.e2e;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.web.servlet.function.RouterFunction;
import org.springframework.web.servlet.function.RouterFunctions;
import org.springframework.web.servlet.function.ServerResponse;
import ro.pricechecker.registry.Registry;
import ro.pricechecker.registry.TestRegistries;

/**
 * Extra beans for the end-to-end server (story 2.14). Deliberately not annotated, so component scan
 * in other {@code @SpringBootTest}s never picks it up; only {@link E2eServer} adds it as a source.
 */
public class E2eBeans {

  /** How long the stub holds a request that has no page; well past every {@code deadlineMs}. */
  static final Duration HANG = Duration.ofSeconds(60);

  /** The e2e register, retailers and known EANs from {@code src/test/resources/e2e/}. */
  static Registry registry() {
    return TestRegistries.of(
        resource("e2e/source-register.md"),
        resource("e2e/retailers.yaml"),
        resource("e2e/known-eans.yaml"));
  }

  @Bean
  @Primary
  Registry e2eRegistry() {
    return registry();
  }

  @Bean
  FixtureHttp fixtureHttp(Registry registry) {
    Set<URI> known =
        registry.knownEans().stream().map(k -> URI.create(k.url())).collect(Collectors.toSet());
    return new FixtureHttp(known, FixtureHttp::classpathPage, HANG);
  }

  /** {@code GET /e2e/requests?ean=} returns the stub's page-request count for that EAN. */
  @Bean
  RouterFunction<ServerResponse> e2eRequests(FixtureHttp http) {
    return RouterFunctions.route()
        .GET(
            "/e2e/requests",
            request -> {
              String ean = request.param("ean").orElse("");
              return ServerResponse.ok().body(Map.of("ean", ean, "requests", http.requests(ean)));
            })
        .build();
  }

  private static String resource(String name) {
    try (InputStream in = E2eBeans.class.getClassLoader().getResourceAsStream(name)) {
      if (in == null) {
        throw new IllegalStateException("missing e2e resource " + name);
      }
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
