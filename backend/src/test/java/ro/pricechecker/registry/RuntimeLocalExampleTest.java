package ro.pricechecker.registry;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/** {@code config/runtime.local.example.yaml} is a valid file that stops every retailer (AD-9). */
@Tag("AC-3")
class RuntimeLocalExampleTest {

  private static final Path REPO_ROOT = Path.of("..");
  private static final Path EXAMPLE = REPO_ROOT.resolve("config/runtime.local.example.yaml");

  private static List<String> retailerDomains() throws IOException {
    return RegistryYaml.parseRetailers(Files.readString(REPO_ROOT.resolve("config/retailers.yaml")))
        .stream()
        .map(RetailerConfig::domain)
        .toList();
  }

  @Test
  void ac_3_runtimeSwitchAcceptsTheExampleAndStopsEveryRetailer() throws IOException {
    RuntimeSwitch runtime =
        new RuntimeSwitch(EXAMPLE, new MutableClock(Instant.parse("2026-11-18T10:00:00Z")));
    assertThat(runtime.refresh()).isEmpty();
    List<String> domains = retailerDomains();
    assertThat(domains).isNotEmpty();
    for (String domain : domains) {
      assertThat(runtime.isAllowed(domain)).as(domain).isFalse();
      assertThat(runtime.hasRunnerBlock(domain)).as(domain).isFalse();
      assertThat(runtime.notBefore(domain)).as(domain).isEmpty();
    }
  }

  @Test
  void ac_3_exampleListsEveryRetailerWithFetchingStopped() throws IOException {
    Map<?, ?> root =
        new Yaml(new SafeConstructor(new LoaderOptions())).load(Files.readString(EXAMPLE));
    Map<?, ?> retailers = (Map<?, ?>) root.get("retailers");
    List<String> domains = retailerDomains();
    assertThat(retailers.keySet().stream().map(String::valueOf).toList())
        .containsExactlyInAnyOrderElementsOf(domains);
    for (String domain : domains) {
      assertThat(retailers.get(domain)).as(domain).isEqualTo(Map.of("fetching", "stopped"));
    }
  }
}
