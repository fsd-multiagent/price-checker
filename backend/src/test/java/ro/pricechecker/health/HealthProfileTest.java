package ro.pricechecker.health;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import ro.pricechecker.PriceCheckerApplication;
import ro.pricechecker.fetch.FetchClient;

/**
 * AC 3.5.1: the {@code health} profile starts no web server, paces the fetch client (interval ×2,
 * concurrency 1) and refuses an existing {@code blocks.json}. {@code runtime.yaml} is missing here,
 * so the run sends nothing and fails (AC 3.5.3).
 */
@Tag("AC-3.5.1")
class HealthProfileTest {

  @TempDir Path dir;

  /** Command-line arguments, so they win over {@code application-health.yaml}. */
  private ConfigurableApplicationContext run() {
    return new SpringApplicationBuilder(PriceCheckerApplication.class)
        .profiles("health")
        .run(
            "--price-checker.runtime-file=" + dir.resolve("runtime.yaml"),
            "--price-checker.blocks-file=" + dir.resolve("blocks.json"),
            "--price-checker.health.report-dir=" + dir.resolve("report"),
            "--price-checker.contact-url=https://contact.example/about");
  }

  @Test
  void ac_3_5_1_healthProfileHasNoWebServerAndPacedFetching() {
    try (ConfigurableApplicationContext context = run()) {
      assertThat(context).isNotInstanceOf(WebServerApplicationContext.class);
      assertThat(context.getBean(FetchClient.class).pace()).isEqualTo(new FetchClient.Pace(2, 1));
      assertThat(Files.exists(dir.resolve("report").resolve("report.json"))).isTrue();
      assertThat(Files.exists(dir.resolve("blocks.json"))).isFalse();
      assertThat(SpringApplication.exit(context)).isEqualTo(1);
    }
  }

  @Test
  void ac_3_5_1_existingBlocksFileStopsStartup() throws IOException {
    Path blocks = Files.writeString(dir.resolve("blocks.json"), "{}");

    assertThatThrownBy(() -> run()).isInstanceOf(Exception.class);
    assertThat(blocks).exists().hasContent("{}");
    assertThat(dir.resolve("report")).doesNotExist();
  }
}
