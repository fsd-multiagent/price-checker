package ro.pricechecker.registry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("AC-1.5.2")
@Tag("AC-1.5.3")
class RegistryYamlTest {

  @Test
  void ac_1_5_2_parsesRetailers() {
    String yaml =
        """
        retailers:
          a.example:
            adapter: disabled
            resolution: curated
            minIntervalMs: 2000
            concurrency: 1
            deadlineMs: 4000
          b.example:
            adapter: enabled
        """;
    assertThat(RegistryYaml.parseRetailers(yaml))
        .containsExactly(
            new RetailerConfig("a.example", "disabled", "curated", 2000, 1, 4000),
            new RetailerConfig("b.example", "enabled", null, null, null, null));
  }

  @Test
  void ac_1_5_2_nonIntegerNumbersBecomeNull() {
    String yaml = "retailers:\n  a.example:\n    minIntervalMs: fast\n    concurrency: 1.5\n";
    RetailerConfig r = RegistryYaml.parseRetailers(yaml).get(0);
    assertThat(r.minIntervalMs()).isNull();
    assertThat(r.concurrency()).isNull();
  }

  @Test
  void ac_1_5_2_rejectsBadShapes() {
    assertThatThrownBy(() -> RegistryYaml.parseRetailers("other: 1"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> RegistryYaml.parseRetailers("retailers: [1, 2]"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> RegistryYaml.parseRetailers("retailers:\n  a.example: 3\n"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() ->
            RegistryYaml.parseRetailers(
                "retailers:\n  a.example: {adapter: disabled}\n  a.example: {adapter: enabled}\n"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void ac_1_5_2_refusesYamlTags() {
    assertThatThrownBy(() -> RegistryYaml.parseRetailers("retailers: !!java.util.HashMap {}\n"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void ac_1_5_3_parsesKnownEans() {
    String yaml =
        """
        knownEans:
          a.example:
            - ean: "4006381333931"
              url: "https://a.example/p/1"
            - ean: "96385074"
              url: "https://a.example/p/2"
        """;
    assertThat(RegistryYaml.parseKnownEans(yaml))
        .containsExactly(
            new KnownEan("a.example", "4006381333931", "https://a.example/p/1"),
            new KnownEan("a.example", "96385074", "https://a.example/p/2"));
  }

  @Test
  void ac_1_5_3_emptyKnownEans() {
    assertThat(RegistryYaml.parseKnownEans("knownEans: {}")).isEmpty();
    assertThat(RegistryYaml.parseKnownEans("knownEans:")).isEmpty();
  }

  @Test
  void ac_1_5_3_rejectsBadKnownEanShapes() {
    assertThatThrownBy(() -> RegistryYaml.parseKnownEans("eans: {}"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> RegistryYaml.parseKnownEans("knownEans:\n  a.example: 1\n"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> RegistryYaml.parseKnownEans("knownEans:\n  a.example: [1]\n"))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
