package ro.pricechecker.e2e;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * AC 2.14.4 by Eufrosina (QA): CI runs the Playwright suite, and the suite's backend is the e2e
 * server whose only retailer HTTP is {@link FixtureHttp} (T-3, T-4). Gradle runs tests from {@code
 * backend/}, so the repository root is its parent.
 */
@Tag("AC-2.14.4")
class E2eCiWiringTest {

  private static final Path ROOT = Path.of("..").toAbsolutePath().normalize();

  @Test
  void ac_2_14_4_backendCiJobRunsTheE2eSuite() throws IOException {
    String ci = Files.readString(ROOT.resolve(".github/workflows/ci.yml"));
    int backendJob = ci.indexOf("\n  backend:\n");
    int nextJob = ci.indexOf("\n  web:\n", backendJob);
    assertThat(backendJob).isNotNegative();
    assertThat(nextJob).isGreaterThan(backendJob);
    String job = ci.substring(backendJob, nextJob);

    assertThat(job).contains("npm --prefix web/e2e ci").contains("npm --prefix web/e2e test");
    assertThat(job.indexOf("npm --prefix web/e2e test"))
        .as("the suite runs after the backend check")
        .isGreaterThan(job.indexOf("backend/gradlew -p backend check"));
  }

  @Test
  void ac_2_14_4_playwrightStartsTheStubbedE2eBackendAndNeverReusesItInCi() throws IOException {
    String config = Files.readString(ROOT.resolve("web/e2e/playwright.config.ts"));

    assertThat(config).contains("backend/gradlew -p backend bootTestRun");
    assertThat(config).contains("reuseExistingServer: !process.env.CI");
    assertThat(config).contains("/e2e/requests");
  }

  @Test
  void ac_2_14_4_bootTestRunMainIsTheE2eServer() throws IOException {
    // bootTestRun needs exactly one main class in test sources; it must be the stubbed server.
    try (var files = Files.walk(Path.of("src/test/java"))) {
      assertThat(
              files
                  .filter(p -> p.toString().endsWith(".java"))
                  .filter(E2eCiWiringTest::hasMain)
                  .map(p -> p.getFileName().toString()))
          .containsExactly("E2eServer.java");
    }
  }

  private static boolean hasMain(Path file) {
    try {
      // Built from parts so this file does not match itself.
      return Files.readString(file).contains("static void " + "main(String[]");
    } catch (IOException e) {
      throw new java.io.UncheckedIOException(e);
    }
  }
}
