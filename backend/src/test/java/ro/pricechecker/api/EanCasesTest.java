package ro.pricechecker.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import ro.pricechecker.api.generated.model.EanErrorCode;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * AC-1.3.3: the shared cases in {@code api/ean-cases.json} are self-consistent. A small GS1 oracle
 * here checks the data only; the real validator is {@code ean.Ean} (story 1.4).
 */
@Tag("AC-1.3.3")
class EanCasesTest {

  private static final Path CASES = Path.of("../api/ean-cases.json");
  private static final List<String> CODES =
      List.of("EAN_EMPTY", "EAN_NON_DIGIT", "EAN_LENGTH", "EAN_CHECK_DIGIT");

  private static List<JsonNode> cases() {
    JsonNode root = JsonMapper.builder().build().readTree(CASES.toFile());
    JsonNode cases = root.get("cases");
    assertThat(cases).isNotNull();
    assertThat(cases.isArray()).isTrue();
    List<JsonNode> list = new ArrayList<>();
    cases.forEach(list::add);
    assertThat(list).isNotEmpty();
    return list;
  }

  /** Test oracle: GS1 check digit (weights 3 and 1 from the right, excluding the check digit). */
  private static boolean checkDigitOk(String digits) {
    int sum = 0;
    int n = digits.length();
    for (int i = 0; i < n - 1; i++) {
      int d = digits.charAt(n - 2 - i) - '0';
      sum += (i % 2 == 0) ? 3 * d : d;
    }
    return (10 - sum % 10) % 10 == digits.charAt(n - 1) - '0';
  }

  /** Test oracle: the first failing rule in AD-13 order (trim, empty, non-digit, length, check). */
  private static String expectedCode(String input) {
    String s = input.strip();
    if (s.isEmpty()) {
      return "EAN_EMPTY";
    }
    if (!s.chars().allMatch(c -> c >= '0' && c <= '9')) {
      return "EAN_NON_DIGIT";
    }
    if (s.length() != 8 && s.length() != 13) {
      return "EAN_LENGTH";
    }
    if (!checkDigitOk(s)) {
      return "EAN_CHECK_DIGIT";
    }
    return null;
  }

  @Test
  void ac_1_3_3_everyCaseHasTheDocumentedShape() {
    for (JsonNode c : cases()) {
      assertThat(c.hasNonNull("name")).as("%s", c).isTrue();
      assertThat(c.get("input").isString()).as("%s", c).isTrue();
      assertThat(c.get("valid").isBoolean()).as("%s", c).isTrue();
      if (c.get("valid").booleanValue()) {
        assertThat(c.get("ean").isString()).as("%s", c).isTrue();
        assertThat(c.has("code")).as("%s", c).isFalse();
      } else {
        assertThat(CODES).as("%s", c).contains(c.get("code").stringValue());
        assertThat(c.has("ean")).as("%s", c).isFalse();
      }
    }
  }

  @Test
  void ac_1_3_3_validCasesAreTrimmedEan8OrEan13WithCorrectCheckDigit() {
    for (JsonNode c : cases()) {
      if (!c.get("valid").booleanValue()) {
        continue;
      }
      String input = c.get("input").stringValue();
      String ean = c.get("ean").stringValue();
      assertThat(ean).as("%s", c).isEqualTo(input.strip()).matches("\\d{8}|\\d{13}");
      assertThat(checkDigitOk(ean)).as("%s", c).isTrue();
    }
  }

  @Test
  void ac_1_3_3_errorCasesMatchTheAd13Order() {
    for (JsonNode c : cases()) {
      String expected = expectedCode(c.get("input").stringValue());
      if (c.get("valid").booleanValue()) {
        assertThat(expected).as("%s", c).isNull();
      } else {
        assertThat(c.get("code").stringValue()).as("%s", c).isEqualTo(expected);
      }
    }
  }

  @Test
  void ac_1_3_3_errorCodesAreExactlyTheContractEanErrorCodeEnum() {
    List<String> generated =
        Arrays.stream(EanErrorCode.values()).map(EanErrorCode::getValue).toList();
    assertThat(generated).containsExactlyElementsOf(CODES);
    Set<String> used = new HashSet<>();
    for (JsonNode c : cases()) {
      if (!c.get("valid").booleanValue()) {
        used.add(c.get("code").stringValue());
      }
    }
    assertThat(used).containsExactlyInAnyOrderElementsOf(generated);
  }

  @Test
  void ac_1_3_3_caseNamesAreUnique() {
    List<String> names = cases().stream().map(c -> c.get("name").stringValue()).toList();
    assertThat(names).doesNotHaveDuplicates();
  }

  @Test
  void ac_1_3_3_coversEan8Ean13PaddingAndEveryErrorCode() {
    Set<Integer> validLengths = new HashSet<>();
    Set<String> codes = new HashSet<>();
    boolean padded = false;
    for (JsonNode c : cases()) {
      String input = c.get("input").stringValue();
      if (c.get("valid").booleanValue()) {
        validLengths.add(c.get("ean").stringValue().length());
        padded |= !input.equals(input.strip());
      } else {
        codes.add(c.get("code").stringValue());
      }
    }
    assertThat(validLengths).containsExactlyInAnyOrder(8, 13);
    assertThat(padded).as("a whitespace-padded valid case").isTrue();
    assertThat(codes).containsExactlyInAnyOrderElementsOf(CODES);
  }
}
