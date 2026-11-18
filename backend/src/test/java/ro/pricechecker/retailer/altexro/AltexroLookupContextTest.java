package ro.pricechecker.retailer.altexro;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import ro.pricechecker.adapter.RetailerAdapter;
import ro.pricechecker.ean.Ean;
import ro.pricechecker.lookup.LookupResult;
import ro.pricechecker.lookup.LookupRow;
import ro.pricechecker.lookup.LookupService;
import ro.pricechecker.registry.Registry;

/**
 * AC 4.1.2 gap found by Eufrosina (QA): in the real application context, where the real Altex
 * Adapter is a bean and the bundled {@code retailers.yaml} ships it disabled, a Lookup has no Altex
 * row. No retailer is enabled in the bundled config, so nothing is fetched (T-3).
 */
@SpringBootTest
@Tag("AC-4.1.2")
class AltexroLookupContextTest {

  private final LookupService lookupService;
  private final List<RetailerAdapter> adapters;
  private final Registry registry;

  @Autowired
  AltexroLookupContextTest(
      LookupService lookupService, List<RetailerAdapter> adapters, Registry registry) {
    this.lookupService = lookupService;
    this.adapters = adapters;
    this.registry = registry;
  }

  @Test
  void ac_4_1_2_realAdapterBeanExistsButLookupHasNoAltexRow() {
    assertThat(adapters)
        .filteredOn(a -> a.retailerId().equals("altex.ro"))
        .singleElement()
        .isInstanceOf(AltexroAdapter.class);
    assertThat(registry.isApproved("altex.ro")).isFalse();

    LookupResult result = lookupService.lookup(Ean.parse("4006381333931"));

    assertThat(result.rows()).extracting(LookupRow::retailerId).doesNotContain("altex.ro");
  }
}
