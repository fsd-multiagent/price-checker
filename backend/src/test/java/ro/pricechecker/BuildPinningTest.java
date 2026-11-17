package ro.pricechecker;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringBootVersion;

/** AC-1.1.1: Java 21, Spring Boot 4.x, every version pinned (S-12) and listed in the spine. */
@Tag("AC-1.1.1")
class BuildPinningTest {

  private static final Path BUILD = Path.of("build.gradle.kts");
  private static final Path SETTINGS = Path.of("settings.gradle.kts");
  private static final Path WRAPPER = Path.of("gradle/wrapper/gradle-wrapper.properties");
  private static final Path SPINE =
      Path.of(
          "../_bmad-output/planning-artifacts/architecture/"
              + "architecture-price-checker-2026-11-17/ARCHITECTURE-SPINE.md");

  private static String read(Path path) throws IOException {
    return Files.readString(path);
  }

  private static String group(String text, String regex) {
    Matcher m = Pattern.compile(regex).matcher(text);
    assertThat(m.find()).as("pattern %s", regex).isTrue();
    return m.group(1);
  }

  private static String stackTable() throws IOException {
    String spine = read(SPINE);
    int start = spine.indexOf("## Stack");
    assertThat(start).as("spine has a Stack section").isNotNegative();
    int end = spine.indexOf("\n## ", start + 1);
    return spine.substring(start, end < 0 ? spine.length() : end);
  }

  @Test
  void ac_1_1_1_testsRunOnJava21() {
    assertThat(Runtime.version().feature()).isEqualTo(21);
  }

  @Test
  void ac_1_1_1_springBootIsMajorVersion4() {
    assertThat(SpringBootVersion.getVersion()).startsWith("4.");
  }

  @Test
  void ac_1_1_1_toolchainIsJava21() throws IOException {
    assertThat(read(BUILD)).contains("JavaLanguageVersion.of(21)");
  }

  @Test
  void ac_1_1_1_buildFilesUseNoDynamicVersions() throws IOException {
    for (Path file : new Path[] {BUILD, SETTINGS}) {
      String text = read(file);
      assertThat(text).as("%s", file).doesNotContain("latest.release", "latest.integration");
      assertThat(Pattern.compile("version\\s*\\(?\\s*\"[^\"]*\\+\"").matcher(text).find())
          .as("%s has a '+' version", file)
          .isFalse();
      assertThat(Pattern.compile("\"[\\w.-]+:[\\w.-]+:[^\"]*\\+\"").matcher(text).find())
          .as("%s has a '+' dependency", file)
          .isFalse();
    }
  }

  @Test
  void ac_1_1_1_gradleWrapperDistributionIsChecksumPinned() throws IOException {
    String wrapper = read(WRAPPER);
    assertThat(group(wrapper, "distributionSha256Sum=([0-9a-f]+)")).hasSize(64);
    assertThat(group(wrapper, "distributionUrl=(\\S+)")).startsWith("https\\://");
  }

  @Test
  void ac_1_1_1_spotlessChecksJavaWithGoogleJavaFormat() throws IOException {
    String build = read(BUILD);
    assertThat(build).contains("id(\"com.diffplug.spotless\")").contains("googleJavaFormat(");
  }

  @Test
  void ac_1_1_1_compilerTreatsWarningsAsErrors() throws IOException {
    assertThat(read(BUILD)).contains("\"-Xlint:all\"").contains("\"-Werror\"");
  }

  @Test
  void ac_1_1_1_springBootPluginVersionMatchesRuntime() throws IOException {
    String pinned = group(read(BUILD), "id\\(\"org.springframework.boot\"\\) version \"([^\"]+)\"");
    assertThat(SpringBootVersion.getVersion()).isEqualTo(pinned);
  }

  @Test
  void ac_1_1_1_spineStackTableListsExactPinnedVersions() throws IOException {
    String build = read(BUILD);
    String stack = stackTable();
    String boot = group(build, "id\\(\"org.springframework.boot\"\\) version \"([^\"]+)\"");
    String spotless = group(build, "id\\(\"com.diffplug.spotless\"\\) version \"([^\"]+)\"");
    String gjf = group(build, "googleJavaFormat\\(\"([^\"]+)\"\\)");
    String archunit = group(build, "com.tngtech.archunit:archunit-junit5:([^\"]+)\"");
    String gradle = group(read(WRAPPER), "gradle-([0-9.]+)-bin\\.zip");

    assertThat(group(stack, "\\| Spring Boot \\| ([^ |]+)")).isEqualTo(boot);
    assertThat(group(stack, "\\| Gradle wrapper[^|]*\\| ([^ ,|]+)")).isEqualTo(gradle);
    assertThat(group(stack, "\\| ArchUnit \\| ([^ |]+)")).isEqualTo(archunit);
    assertThat(group(stack, "\\| Spotless[^|]*\\| ([^ |]+) \\+")).isEqualTo(spotless);
    assertThat(group(stack, "\\| Spotless[^|]*\\| [^ |]+ \\+ ([^ |]+)")).isEqualTo(gjf);
    assertThat(group(stack, "\\| Java[^|]*\\| (\\d+)")).isEqualTo("21");
  }

  @Test
  void ac_1_1_1_foojayResolverVersionIsPinnedAndListed() throws IOException {
    String foojay =
        group(
            read(SETTINGS),
            "id\\(\"org.gradle.toolchains.foojay-resolver-convention\"\\) version \"([^\"]+)\"");
    assertThat(group(stackTable(), "\\| Gradle foojay-resolver-convention[^|]*\\| ([^ |]+)"))
        .isEqualTo(foojay);
  }
}
