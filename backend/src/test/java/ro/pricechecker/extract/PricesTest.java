package ro.pricechecker.extract;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

@Tag("AC-2.1.3")
class PricesTest {

  @ParameterizedTest
  @CsvSource(
      delimiter = '|',
      value = {
        "1.299,99 lei | 129999",
        "1299.99 | 129999",
        "1 299,99 RON | 129999",
        "1.299 | 129900",
        "49,9 | 4990",
        "1,299.99 | 129999",
        "1.234.567,89 | 123456789",
        "LEI 12 | 1200",
        "12,50 Lei | 1250",
        "1299 | 129900"
      })
  void ac_2_1_3_parsesPriceTextToBani(String text, long bani) {
    assertThat(Prices.toMinor(text)).isEqualTo(bani);
  }

  @Test
  void ac_2_1_3_acceptsNonBreakingSpaceAsGrouping() {
    assertThat(Prices.toMinor("1 299,99 lei")).isEqualTo(129999L);
  }

  @ParameterizedTest
  @NullAndEmptySource
  @ValueSource(
      strings = {
        "abc",
        "0",
        "0,00",
        "-5",
        "12.345.6",
        "1,2,3",
        "12 99 lei",
        "1299 999",
        "0.999",
        "1299.999",
        "12 EUR",
        "1.299,99,1",
        "1,299.99.1",
        "1.299,999",
        "12,",
        "lei",
        "99999999999999999999999"
      })
  void ac_2_1_3_badPriceTextIsNullNeverZero(String text) {
    assertThat(Prices.toMinor(text)).isNull();
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "N/A",
        "Pret la cerere",
        "   ",
        " ",
        "1e3",
        "+5",
        ".99",
        ",99",
        "0.299",
        "01.299,99",
        "12.5.0 lei",
        "lei lei 5",
        "1 2 3",
        "1299,99 lei 5",
        "NaN",
        "Infinity"
      })
  void ac_2_1_3_moreGarbageIsNullNeverZero(String text) {
    assertThat(Prices.toMinor(text)).isNull();
  }

  @Test
  void ac_2_1_3_neverThrowsOnOddInput() {
    String[] odd = {
      "\u0000",
      "١٢٣",
      "1\n299",
      "lei",
      "ron ron",
      "9".repeat(40),
      "9".repeat(10_000),
      ",",
      ".",
      "..,"
    };
    for (String text : odd) {
      Long bani = Prices.toMinor(text);
      assertThat(bani)
          .as(text.length() > 20 ? "long input" : text)
          .satisfiesAnyOf(b -> assertThat(b).isNull(), b -> assertThat(b).isPositive());
    }
  }

  @Test
  void ac_2_1_3_convertsNumericAmounts() {
    assertThat(Prices.toMinor(new BigDecimal("1299.99"))).isEqualTo(129999L);
    assertThat(Prices.toMinor(new BigDecimal("1299.900"))).isEqualTo(129990L);
    assertThat(Prices.toMinor(new BigDecimal("12"))).isEqualTo(1200L);
  }

  @Test
  void ac_2_1_3_badNumericAmountsAreNull() {
    assertThat(Prices.toMinor((BigDecimal) null)).isNull();
    assertThat(Prices.toMinor(BigDecimal.ZERO)).isNull();
    assertThat(Prices.toMinor(new BigDecimal("-5"))).isNull();
    assertThat(Prices.toMinor(new BigDecimal("1299.999"))).isNull();
    assertThat(Prices.toMinor(new BigDecimal("1e30"))).isNull();
  }
}
