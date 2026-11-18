package ro.pricechecker.registry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Story 2.12, AC 2.12.3: the shipped config enables the cel.ro adapter, and the register test
 * passes only because cel.ro is Approved (AC-9.1). Reads the bundled copies, so it checks what the
 * jar ships.
 */
@Tag("AC-2.12.3")
class CelroEnableTest {

  private static final String CELRO = "cel.ro";
  private static final Pattern CELRO_ROW_STATUS =
      Pattern.compile("(?m)^(\\| cel\\.ro \\| `cel\\.ro` \\| )Approved \\d{4}-\\d{2}-\\d{2}( \\|)");

  private static String register() {
    return Registry.readResource(Registry.REGISTER_RESOURCE);
  }

  private static String retailers() {
    return Registry.readResource(Registry.RETAILERS_RESOURCE);
  }

  private static String knownEans() {
    return Registry.readResource(Registry.KNOWN_EANS_RESOURCE);
  }

  /** The bundled register with the cel.ro row's Status set back to {@code Proposed}. */
  private static String registerWithCelroProposed() {
    Matcher m = CELRO_ROW_STATUS.matcher(register());
    assertThat(m.find()).as("cel.ro row with an Approved status").isTrue();
    String changed = m.replaceFirst("$1Proposed$2");
    assertThat(SourceRegister.parse(changed).isApproved(CELRO)).isFalse();
    return changed;
  }

  private static String retailersWithCelro(String adapter) {
    String yaml = retailers();
    String marker = "  " + CELRO + ":\n    adapter: enabled\n";
    assertThat(yaml).contains(marker);
    return yaml.replace(marker, "  " + CELRO + ":\n    adapter: " + adapter + "\n");
  }

  @Test
  void ac_2_12_3_shippedConfigEnablesCelroAndCelroIsApproved() {
    Registry registry = new Registry();
    assertThat(registry.isApproved(CELRO)).isTrue();
    assertThat(registry.retailers())
        .filteredOn(r -> r.domain().equals(CELRO))
        .singleElement()
        .satisfies(r -> assertThat(r.adapter()).isEqualTo("enabled"));
  }

  @Test
  void ac_2_12_3_registerTestPassesWithShippedConfig() {
    assertThat(
            ConfigCheck.violations(
                SourceRegister.parse(register()),
                RegistryYaml.parseRetailers(retailers()),
                RegistryYaml.parseKnownEans(knownEans())))
        .isEmpty();
  }

  @Test
  void ac_2_12_3_enabledCelroFailsWhenCelroIsNotApproved() {
    String proposed = registerWithCelroProposed();

    assertThat(
            ConfigCheck.violations(
                SourceRegister.parse(proposed),
                RegistryYaml.parseRetailers(retailers()),
                RegistryYaml.parseKnownEans(knownEans())))
        .singleElement()
        .asString()
        .contains(CELRO, "enabled");
    assertThatThrownBy(() -> new Registry(proposed, retailers(), knownEans()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining(CELRO);
  }

  @Test
  void ac_2_12_3_disabledCelroPassesWhenCelroIsNotApproved() {
    // Control: with cel.ro Proposed only the enabled adapter breaks the check, so the passing
    // shipped config depends on the approval, not on some other part of the config.
    assertThatCode(() ->
            new Registry(registerWithCelroProposed(), retailersWithCelro("disabled"), knownEans()))
        .doesNotThrowAnyException();
  }
}
