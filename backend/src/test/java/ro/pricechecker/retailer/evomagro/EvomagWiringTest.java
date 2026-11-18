package ro.pricechecker.retailer.evomagro;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import ro.pricechecker.adapter.RetailerAdapter;

/**
 * StubAdapters registers no evoMAG stub because a real adapter serves the id, so the catalog sees
 * exactly one evoMAG adapter.
 */
@SpringBootTest
@Tag("AC-2.11.3")
class EvomagWiringTest {

  private final List<RetailerAdapter> adapters;

  @Autowired
  EvomagWiringTest(List<RetailerAdapter> adapters) {
    this.adapters = adapters;
  }

  @Test
  void ac_2_11_3_contextHasExactlyOneEvomagAdapter() {
    assertThat(adapters)
        .filteredOn(a -> a.retailerId().equals(EvomagAdapter.RETAILER_ID))
        .singleElement()
        .isInstanceOf(EvomagAdapter.class);
  }
}
