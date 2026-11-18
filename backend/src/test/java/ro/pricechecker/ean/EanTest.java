package ro.pricechecker.ean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** AC 1.4.1: {@code Ean.parse} against every shared case in {@code api/ean-cases.json}. */
@Tag("AC-1.4.1")
class EanTest {

  private static final Path CASES = Path.of("../api/ean-cases.json");

  static List<JsonNode> cases() {
    JsonNode root = JsonMapper.builder().build().readTree(CASES.toFile());
    List<JsonNode> list = new ArrayList<>();
    root.get("cases").forEach(list::add);
    assertThat(list).isNotEmpty();
    return list;
  }

  private static void assertRejected(String input, EanError expected) {
    assertThatThrownBy(() -> Ean.parse(input))
        .isInstanceOfSatisfying(
            InvalidEanException.class,
            e -> {
              assertThat(e.error()).isEqualTo(expected);
              assertThat(e.getMessage()).isEqualTo(expected.name());
            });
  }

  @Test
  void ac_1_4_1_everySharedCase() {
    for (JsonNode c : cases()) {
      String input = c.get("input").stringValue();
      if (c.get("valid").booleanValue()) {
        assertThat(Ean.parse(input).value()).as("%s", c).isEqualTo(c.get("ean").stringValue());
      } else {
        assertRejected(input, EanError.valueOf(c.get("code").stringValue()));
      }
    }
  }

  @Test
  void ac_1_4_1_trimsTheJsTrimWhitespaceSet() {
    int[] spaces = {
      0x09, 0x0A, 0x0B, 0x0C, 0x0D, 0x20, 0xA0, 0x1680, 0x2000, 0x200A, 0x2028, 0x2029, 0x202F,
      0x205F, 0x3000, 0xFEFF
    };
    for (int cp : spaces) {
      String s = Character.toString(cp);
      assertThat(Ean.parse(s + "5901234123457" + s).value())
          .as("U+%04X", cp)
          .isEqualTo("5901234123457");
    }
    assertThat(
            Ean.parse(Character.toString(0x3000) + "5901234123457" + Character.toString(0xFEFF))
                .value())
        .isEqualTo("5901234123457");
  }

  @Test
  void ac_1_4_1_zeroWidthSpaceIsNotTrimmed() {
    // U+200B is Cf, not Zs; JS trim() keeps it.
    assertRejected(Character.toString(0x200B) + "5901234123457", EanError.EAN_NON_DIGIT);
  }

  /** The same digits in another Unicode digit block starting at {@code zero}. */
  private static String shiftDigits(String ascii, int zero) {
    StringBuilder sb = new StringBuilder();
    ascii.chars().forEach(d -> sb.appendCodePoint(zero + d - '0'));
    return sb.toString();
  }

  @Test
  void ac_1_4_1_nonAsciiDigitsAreRejected() {
    assertRejected(shiftDigits("4006381333931", 0x0660), EanError.EAN_NON_DIGIT); // Arabic-Indic
    assertRejected(shiftDigits("4006381333931", 0xFF10), EanError.EAN_NON_DIGIT); // fullwidth
  }

  @Test
  void ac_1_4_1_nullAndWhitespaceOnlyAreEmpty() {
    assertRejected(null, EanError.EAN_EMPTY);
    assertRejected(
        Character.toString(0xA0) + Character.toString(0xFEFF) + "\t", EanError.EAN_EMPTY);
  }

  @Test
  void ac_1_4_1_constructorRejectsAnythingNotAlreadyValid() {
    assertThatThrownBy(() -> new Ean(" 5901234123457")).isInstanceOf(InvalidEanException.class);
    assertThatThrownBy(() -> new Ean("5901234123458")).isInstanceOf(InvalidEanException.class);
    assertThatThrownBy(() -> new Ean(null)).isInstanceOf(InvalidEanException.class);
    assertThat(new Ean("96385074").value()).isEqualTo("96385074");
  }
}
