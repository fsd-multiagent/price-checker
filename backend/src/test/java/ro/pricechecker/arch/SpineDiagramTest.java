package ro.pricechecker.arch;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * AC-1.1.2: the ArchUnit allow-list is exactly the solid arrows of the spine paradigm diagram, so
 * the rules and the diagram cannot drift apart.
 */
@Tag("AC-1.1.2")
class SpineDiagramTest {

  private static final Path SPINE =
      Path.of(
          "../_bmad-output/planning-artifacts/architecture/"
              + "architecture-price-checker-2026-11-17/ARCHITECTURE-SPINE.md");

  /** {@code from --> to} or {@code from -->|label| to}, ignoring the node labels in brackets. */
  private static final Pattern SOLID_ARROW =
      Pattern.compile("^\\s*(\\w+)(?:\\[[^\\]]*\\])?\\s*-->(?:\\|[^|]*\\|)?\\s*(\\w+)");

  private static final Pattern DOTTED_ARROW = Pattern.compile("^\\s*(\\w+)\\S*\\s*-\\.->");

  private static Map<String, Set<String>> solid;
  private static Set<String> dottedSources;

  @BeforeAll
  static void parseDiagram() throws IOException {
    String spine = Files.readString(SPINE);
    int start = spine.indexOf("```mermaid");
    int end = spine.indexOf("```", start + 3);
    assertThat(start).as("spine has a mermaid diagram").isNotNegative();
    solid = new HashMap<>();
    dottedSources = new HashSet<>();
    for (String line : spine.substring(start, end).split("\n")) {
      Matcher arrow = SOLID_ARROW.matcher(line);
      if (arrow.find()) {
        solid.computeIfAbsent(arrow.group(1), k -> new HashSet<>()).add(arrow.group(2));
      }
      Matcher dotted = DOTTED_ARROW.matcher(line);
      if (dotted.find()) {
        dottedSources.add(dotted.group(1));
      }
    }
  }

  private static Set<String> arrowsFrom(String node) {
    return solid.getOrDefault(node, Set.of());
  }

  @Test
  void ac_1_1_2_everyFeaturePackageAllowsExactlyTheDiagramArrows() {
    for (String feature : ArchitectureRules.FEATURES) {
      assertThat(ArchitectureRules.ALLOWED.get(feature))
          .as("allowed arrows from %s", feature)
          .containsExactlyInAnyOrderElementsOf(arrowsFrom(feature));
    }
  }

  @Test
  void ac_1_1_2_retailerPackagesAllowExactlyTheDiagramArrows() {
    assertThat(ArchitectureRules.RETAILER_ALLOWED)
        .containsExactlyInAnyOrderElementsOf(arrowsFrom("retailerX"));
  }

  @Test
  void ac_1_1_2_everyDiagramBackendNodeIsAKnownPackage() {
    Set<String> nodes = new HashSet<>(solid.keySet());
    solid.values().forEach(nodes::addAll);
    nodes.remove("web");
    nodes.remove("retailerX");
    assertThat(ArchitectureRules.FEATURES).containsExactlyInAnyOrderElementsOf(nodes);
  }

  @Test
  void ac_1_1_2_dottedRuntimeArrowIsNotACompileTimeDependency() {
    assertThat(dottedSources).contains("lookup");
    assertThat(ArchitectureRules.ALLOWED.get("lookup")).doesNotContain("retailer");
  }
}
