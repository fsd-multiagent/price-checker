package ro.pricechecker;

import java.time.Clock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import ro.pricechecker.fetch.FetchClient;
import ro.pricechecker.fetch.JdkTransport;
import ro.pricechecker.fetch.Sleeper;
import ro.pricechecker.fetch.UserAgent;
import ro.pricechecker.registry.BlockStore;
import ro.pricechecker.registry.Registry;
import ro.pricechecker.registry.RuntimeSwitch;

/**
 * Wires the shared fetch client (AD-6): the contact URL comes from {@code
 * price-checker.contact-url} (env {@code PRICE_CHECKER_CONTACT_URL}); the user agent is built and
 * checked here at startup, so a missing contact URL or a forbidden word stops the application (AC
 * 2.4.5). Lives here so {@code fetch} reads no property. Not yet wired into {@code LookupService}
 * (story 2.8), so nothing is fetched yet.
 */
@Configuration(proxyBeanMethods = false)
public class FetchClientConfig {

  /** Used when the artifact carries no {@code Implementation-Version}. */
  static final String DEV_VERSION = "dev";

  @Bean
  UserAgent userAgent(@Value("${price-checker.contact-url:}") String contactUrl) {
    String version = PriceCheckerApplication.class.getPackage().getImplementationVersion();
    return UserAgent.build(version == null ? DEV_VERSION : version, contactUrl);
  }

  @Bean
  FetchClient fetchClient(
      Registry registry,
      RuntimeSwitch runtimeSwitch,
      BlockStore blockStore,
      Clock clock,
      UserAgent userAgent) {
    return new FetchClient(
        registry, runtimeSwitch, blockStore, new JdkTransport(), clock, Sleeper.SYSTEM, userAgent);
  }
}
