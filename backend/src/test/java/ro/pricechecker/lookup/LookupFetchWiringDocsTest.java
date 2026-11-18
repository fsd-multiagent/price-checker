package ro.pricechecker.lookup;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * AC-0.102.5 (issue #102), found by Eufrosina (QA): {@code AGENTS.md} describes the per-retailer
 * binder and no longer says Lookups are {@code UNAVAILABLE} for lack of a {@code RetailerHttp}
 * bean. Read as text, like {@code DeployConfigTest}.
 */
class LookupFetchWiringDocsTest {

  private static final Path AGENTS = Path.of("../AGENTS.md");

  @Test
  @Tag("AC-0.102.5")
  void ac_0_102_5_agentsMdDropsTheOldUnwiredPitfall() throws IOException {
    String text = Files.readString(AGENTS);
    assertThat(text)
        .doesNotContain("empty `Optional<RetailerHttp>`")
        .doesNotContain("did not wire in `fetch.FetchClient`")
        .doesNotContain("every enabled retailer with a known EAN is `UNAVAILABLE`");
  }

  @Test
  @Tag("AC-0.102.5")
  void ac_0_102_5_agentsMdNamesTheBinderWiring() throws IOException {
    String text = Files.readString(AGENTS);
    assertThat(text).contains("RetailerHttpBinder").contains("fetchClient::bind");
  }
}
