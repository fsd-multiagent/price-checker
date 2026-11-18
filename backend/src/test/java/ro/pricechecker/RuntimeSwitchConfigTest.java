package ro.pricechecker;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import ro.pricechecker.registry.RuntimeSwitch;

/** AC 2.3.1: the Kill switch bean reads the file named by {@code price-checker.runtime-file}. */
@Tag("AC-2.3.1")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class RuntimeSwitchConfigTest {

  private static final Path FILE = runtimeFile();

  @Autowired RuntimeSwitch runtimeSwitch;

  private static Path runtimeFile() {
    try {
      Path file = Files.createTempFile("runtime", ".yaml");
      Files.writeString(file, "retailers:\n  a.example: {fetching: allowed}\n");
      return file;
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  @DynamicPropertySource
  static void runtimeFileProperty(DynamicPropertyRegistry registry) {
    registry.add("price-checker.runtime-file", FILE::toString);
  }

  @AfterAll
  static void deleteFile() throws IOException {
    Files.deleteIfExists(FILE);
  }

  @Test
  void ac_2_3_1_beanReadsTheConfiguredFile() {
    assertThat(runtimeSwitch.refresh()).isEmpty();
    assertThat(runtimeSwitch.isAllowed("a.example")).isTrue();
    assertThat(runtimeSwitch.isAllowed("b.example")).isFalse();
  }
}
