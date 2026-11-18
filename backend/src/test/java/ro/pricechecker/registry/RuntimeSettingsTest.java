package ro.pricechecker.registry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** The one {@code runtime.yaml} parser (AD-9). */
class RuntimeSettingsTest {

  @Test
  @Tag("AC-2.3.1")
  void ac_2_3_1_allowedAndStoppedAreRead() {
    RuntimeSettings settings =
        RuntimeSettings.parse(
            """
            retailers:
              a.example: {fetching: allowed}
              b.example: {fetching: stopped}
            """);
    assertThat(settings.isAllowed("a.example")).isTrue();
    assertThat(settings.isAllowed("b.example")).isFalse();
  }

  @Test
  @Tag("AC-2.3.2")
  void ac_2_3_2_missingRetailerIsStopped() {
    RuntimeSettings settings =
        RuntimeSettings.parse("retailers:\n  b.example: {fetching: allowed}\n");
    assertThat(settings.isAllowed("a.example")).isFalse();
  }

  @Test
  @Tag("AC-2.3.2")
  void ac_2_3_2_noGoodReadStopsEverything() {
    assertThat(RuntimeSettings.NONE.isAllowed("a.example")).isFalse();
  }

  @Test
  @Tag("AC-2.3.1")
  void ac_2_3_1_laterStoryKeysAreIgnored() {
    RuntimeSettings settings =
        RuntimeSettings.parse(
            """
            retailers:
              a.example:
                fetching: allowed
                clearGeneration: 3
                runnerBlock: {blockedAt: "2026-11-18T04:00:00Z", reason: CHALLENGE}
                notBefore: "2026-11-18T05:00:00Z"
            """);
    assertThat(settings.isAllowed("a.example")).isTrue();
  }

  @ParameterizedTest
  @Tag("AC-2.3.3")
  @ValueSource(
      strings = {
        "",
        "::: not yaml [",
        "- a\n- b\n",
        "other: {}\n",
        "retailers:\n",
        "retailers: [a.example]\n",
        "retailers:\n  a.example: allowed\n",
        "retailers:\n  a.example: {fetching: yes}\n",
        "retailers:\n  a.example: {fetching: Allowed}\n",
        "retailers:\n  a.example: {fetching: \"allowed \"}\n",
        "retailers:\n  a.example: {other: 1}\n",
        "retailers:\n  a.example: {fetching: allowed}\n  b.example: {fetching: maybe}\n",
        "retailers:\n  a.example: {fetching: allowed}\n  a.example: {fetching: stopped}\n",
        "retailers:\n  1: {fetching: allowed}\n",
      })
  void ac_2_3_3_badFileIsRejectedWhole(String yaml) {
    assertThatThrownBy(() -> RuntimeSettings.parse(yaml))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
