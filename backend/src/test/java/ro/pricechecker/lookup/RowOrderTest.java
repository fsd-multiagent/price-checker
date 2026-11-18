package ro.pricechecker.lookup;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("AC-1.6.3")
class RowOrderTest {

  private static LookupRow found(String name, Long price, RowAvailability availability) {
    return new LookupRow(name, name, RowStatus.FOUND, price, availability, null, null, false);
  }

  private static LookupRow plain(String name, RowStatus status) {
    return LookupRow.withoutFacts(name, name, status);
  }

  private static List<String> names(List<LookupRow> rows) {
    return rows.stream().map(LookupRow::retailerName).toList();
  }

  private static List<String> lowest(List<LookupRow> rows) {
    return rows.stream().filter(LookupRow::lowest).map(LookupRow::retailerName).toList();
  }

  @Test
  void ac_1_6_3_mixedRowsOrderAndLowest() {
    List<LookupRow> out =
        RowOrder.arrange(
            List.of(
                plain("h-disabled", RowStatus.DISABLED),
                plain("g-unavailable", RowStatus.UNAVAILABLE),
                plain("f-notfound", RowStatus.NOT_FOUND),
                found("e-noprice", null, RowAvailability.UNKNOWN),
                found("d-oos300", 300L, RowAvailability.OUT_OF_STOCK),
                found("c-500", 500L, RowAvailability.IN_STOCK),
                found("b-300", 300L, RowAvailability.IN_STOCK)));
    assertThat(names(out))
        .containsExactly(
            "b-300", "d-oos300", "c-500", "e-noprice", "f-notfound", "g-unavailable", "h-disabled");
    assertThat(lowest(out)).containsExactly("b-300");
  }

  @Test
  void ac_1_6_3_tieMarksBothAndOrdersByName() {
    List<LookupRow> out =
        RowOrder.arrange(
            List.of(
                found("z", 300L, RowAvailability.IN_STOCK),
                found("a", 300L, RowAvailability.UNKNOWN)));
    assertThat(names(out)).containsExactly("a", "z");
    assertThat(lowest(out)).containsExactly("a", "z");
  }

  @Test
  void ac_1_6_3_noEligibleRowMeansNoLowest() {
    List<LookupRow> out =
        RowOrder.arrange(
            List.of(
                found("a", 100L, RowAvailability.OUT_OF_STOCK),
                found("b", null, RowAvailability.IN_STOCK),
                plain("c", RowStatus.NOT_FOUND),
                plain("d", RowStatus.UNAVAILABLE),
                plain("e", RowStatus.DISABLED)));
    assertThat(lowest(out)).isEmpty();
  }

  @Test
  void ac_1_6_3_outOfStockCheaperRowIsNotLowest() {
    List<LookupRow> out =
        RowOrder.arrange(
            List.of(
                found("a", 100L, RowAvailability.OUT_OF_STOCK),
                found("b", 200L, RowAvailability.IN_STOCK)));
    assertThat(names(out)).containsExactly("a", "b");
    assertThat(lowest(out)).containsExactly("b");
  }
}
