package ro.pricechecker.ean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.EnumSet;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;

/**
 * AC 1.4.1 gaps found by Eufrosina (QA): the AD-13 check order, T-7 cases independent of the shared
 * file, and that the shared file covers every error code.
 */
@Tag("AC-1.4.1")
class EanAcceptanceGapsTest {

  private static EanError rejection(String input) {
    try {
      Ean.parse(input);
    } catch (InvalidEanException e) {
      return e.error();
    }
    throw new AssertionError("accepted: " + input);
  }

  @ParameterizedTest
  @ValueSource(strings = {"4006381333900", "1234567890128", "12345670", "00000000"})
  void ac_1_4_1_validEan8AndEan13IncludingCheckDigitZero(String ean) {
    assertThat(Ean.parse(ean).value()).isEqualTo(ean);
  }

  @ParameterizedTest
  @CsvSource({
    // non-digit is checked before length
    "12a, EAN_NON_DIGIT",
    "1234567890123456789x, EAN_NON_DIGIT",
    "'4006381333931 x', EAN_NON_DIGIT",
    // length is checked before the check digit
    "123456789, EAN_LENGTH",
    "1234567890, EAN_LENGTH",
    "12345678901, EAN_LENGTH",
    "1, EAN_LENGTH",
    "40063813339310, EAN_LENGTH",
    // a dot or plus sign is not a digit
    "4006381333.31, EAN_NON_DIGIT",
    "+4006381333931, EAN_NON_DIGIT",
  })
  void ac_1_4_1_rulesApplyInAd13Order(String input, EanError expected) {
    assertThat(rejection(input)).isEqualTo(expected);
  }

  @Test
  void ac_1_4_1_everySingleDigitChangeOfAValidEan13FailsTheCheckDigit() {
    String valid = "4006381333931";
    for (int i = 0; i < valid.length(); i++) {
      for (char d = '0'; d <= '9'; d++) {
        if (d == valid.charAt(i)) {
          continue;
        }
        String mutated = valid.substring(0, i) + d + valid.substring(i + 1);
        assertThat(rejection(mutated)).as(mutated).isEqualTo(EanError.EAN_CHECK_DIGIT);
      }
    }
  }

  @Test
  void ac_1_4_1_everySingleDigitChangeOfAValidEan8FailsTheCheckDigit() {
    String valid = "96385074";
    for (int i = 0; i < valid.length(); i++) {
      for (char d = '0'; d <= '9'; d++) {
        if (d == valid.charAt(i)) {
          continue;
        }
        String mutated = valid.substring(0, i) + d + valid.substring(i + 1);
        assertThat(rejection(mutated)).as(mutated).isEqualTo(EanError.EAN_CHECK_DIGIT);
      }
    }
  }

  @Test
  void ac_1_4_1_sharedCasesCoverEveryErrorCodeAndBothLengths() {
    Set<EanError> codes = EnumSet.noneOf(EanError.class);
    Set<Integer> validLengths = new HashSet<>();
    for (JsonNode c : EanTest.cases()) {
      if (c.get("valid").booleanValue()) {
        validLengths.add(c.get("ean").stringValue().length());
      } else {
        codes.add(EanError.valueOf(c.get("code").stringValue()));
      }
    }
    assertThat(codes).containsExactlyInAnyOrder(EanError.values());
    assertThat(validLengths).containsExactlyInAnyOrder(8, 13);
  }

  @Test
  void ac_1_4_1_exceptionMessageNeverCarriesTheInput() {
    String input = "40063813339A1";
    assertThatThrownBy(() -> Ean.parse(input))
        .isInstanceOf(InvalidEanException.class)
        .hasMessage("EAN_NON_DIGIT")
        .hasMessageNotContaining(input);
  }
}
