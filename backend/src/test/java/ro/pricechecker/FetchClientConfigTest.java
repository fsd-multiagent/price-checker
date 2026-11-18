package ro.pricechecker;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.ConfigurableApplicationContext;
import ro.pricechecker.adapter.RetailerHttpBinder;
import ro.pricechecker.fetch.FetchClient;
import ro.pricechecker.fetch.UserAgent;
import ro.pricechecker.lookup.LookupLog;
import ro.pricechecker.registry.Registry;

/** AC 2.4.5: the user agent is built at startup; a bad contact URL stops the context. */
@Tag("AC-2.4.5")
class FetchClientConfigTest {

  private final ApplicationContextRunner runner =
      new ApplicationContextRunner()
          .withUserConfiguration(
              ClockConfig.class,
              RuntimeSwitchConfig.class,
              LookupLog.class,
              BlockStoreConfig.class,
              FetchClientConfig.class)
          .withBean(Registry.class);

  @Test
  void ac_2_4_5_contactUrlComesFromTheProperty() {
    runner
        .withPropertyValues("price-checker.contact-url=https://contact.example/about")
        .run(context -> {
          assertThat(context).hasNotFailed().hasSingleBean(FetchClient.class);
          assertThat(context.getBean(UserAgent.class).value())
              .isEqualTo("price-checker/dev (+https://contact.example/about)");
        });
  }

  @Test
  @Tag("AC-0.102.4")
  void ac_0_102_4_binderBindsTheSharedFetchClientPerRetailer() {
    runner
        .withPropertyValues("price-checker.contact-url=https://contact.example/about")
        .run(context -> {
          assertThat(context).hasNotFailed().hasSingleBean(RetailerHttpBinder.class);
          String id = context.getBean(Registry.class).retailers().get(0).domain();
          assertThat(context.getBean(RetailerHttpBinder.class).bind(id))
              .isInstanceOfSatisfying(
                  FetchClient.BoundHttp.class, b -> assertThat(b.retailerId()).isEqualTo(id));
        });
  }

  @Test
  void ac_2_4_5_missingContactUrlStopsTheContext() {
    runner.run(context -> assertStartupFailed(context.getStartupFailure()));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "",
        "  ",
        "not a url",
        "contact.example/about",
        "ftp://contact.example/",
        "https://contact.example/robots"
      })
  void ac_2_4_5_badContactUrlStopsTheContext(String contactUrl) {
    runner
        .withPropertyValues("price-checker.contact-url=" + contactUrl)
        .run(context -> assertStartupFailed(context.getStartupFailure()));
  }

  @Test
  void ac_2_4_5_bundledDefaultContactUrlStartsTheApp() {
    try (ConfigurableApplicationContext context =
        new SpringApplicationBuilder(PriceCheckerApplication.class)
            .web(WebApplicationType.NONE)
            .run()) {
      String ua = context.getBean(UserAgent.class).value();
      assertThat(ua).startsWith("price-checker/").contains("(+https://");
    }
  }

  @Test
  void ac_2_4_5_blankContactUrlStopsTheApp() {
    Throwable failure = null;
    try (ConfigurableApplicationContext context =
        new SpringApplicationBuilder(PriceCheckerApplication.class)
            .web(WebApplicationType.NONE)
            .run("--price-checker.contact-url=")) {
      assertThat(context).as("context must not start").isNull();
    } catch (RuntimeException e) {
      failure = e;
    }
    assertStartupFailed(failure);
  }

  private static void assertStartupFailed(Throwable failure) {
    assertThat(failure).isNotNull();
    for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
      if (cause instanceof IllegalStateException
          && cause.getStackTrace().length > 0
          && cause.getStackTrace()[0].getClassName().equals(UserAgent.class.getName())) {
        return;
      }
    }
    throw new AssertionError("no IllegalStateException from UserAgent in the chain", failure);
  }
}
