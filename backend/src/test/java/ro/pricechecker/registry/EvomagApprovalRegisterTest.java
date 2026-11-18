package ro.pricechecker.registry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * AC 2.13.3: with {@code adapter: enabled} for evoMAG, the register check passes on the bundled
 * files only because the register marks evoMAG Approved (AC-9.1). The same files with evoMAG's
 * status set back to {@code Proposed} fail.
 */
@Tag("AC-2.13.3")
class EvomagApprovalRegisterTest {

  private static final String EVOMAG = "evomag.ro";

  private static List<RetailerConfig> retailers() {
    return RegistryYaml.parseRetailers(Registry.readResource(Registry.RETAILERS_RESOURCE));
  }

  private static List<KnownEan> knownEans() {
    return RegistryYaml.parseKnownEans(Registry.readResource(Registry.KNOWN_EANS_RESOURCE));
  }

  /** The bundled register with evoMAG's status cell changed to {@code status}. */
  private static String registerWithEvomag(String status) {
    String register = Registry.readResource(Registry.REGISTER_RESOURCE);
    StringBuilder out = new StringBuilder();
    int changed = 0;
    for (String line : register.split("\n", -1)) {
      if (line.contains("| `" + EVOMAG + "` |")) {
        line = line.replaceFirst("\\| Approved \\d{4}-\\d{2}-\\d{2} \\|", "| " + status + " |");
        changed++;
      }
      out.append(line).append('\n');
    }
    assertThat(changed).as("evoMAG register rows").isEqualTo(1);
    return out.substring(0, out.length() - 1);
  }

  @Test
  void ac_2_13_3_evomagIsEnabledAndApprovedAndTheRegisterCheckPasses() {
    SourceRegister register =
        SourceRegister.parse(Registry.readResource(Registry.REGISTER_RESOURCE));
    assertThat(register.isApproved(EVOMAG)).isTrue();
    assertThat(retailers())
        .filteredOn(r -> r.domain().equals(EVOMAG))
        .singleElement()
        .extracting(RetailerConfig::enabled)
        .isEqualTo(true);
    assertThat(ConfigCheck.violations(register, retailers(), knownEans())).isEmpty();
  }

  @Test
  void ac_2_13_3_sameConfigFailsWhenEvomagIsOnlyProposed() {
    SourceRegister proposed = SourceRegister.parse(registerWithEvomag("Proposed"));
    assertThat(proposed.errors()).isEmpty();
    assertThat(proposed.isApproved(EVOMAG)).isFalse();

    List<String> violations = ConfigCheck.violations(proposed, retailers(), knownEans());

    assertThat(violations).isNotEmpty().allSatisfy(v -> assertThat(v).contains(EVOMAG));
    assertThat(violations).anySatisfy(v -> assertThat(v).contains("enabled"));
  }

  @Test
  void ac_2_13_3_registryRefusesToStartWhenEvomagIsOnlyProposed() {
    String register = registerWithEvomag("Proposed");
    String retailers = Registry.readResource(Registry.RETAILERS_RESOURCE);
    String eans = Registry.readResource(Registry.KNOWN_EANS_RESOURCE);
    assertThatThrownBy(() -> new Registry(register, retailers, eans)).hasMessageContaining(EVOMAG);
  }
}
