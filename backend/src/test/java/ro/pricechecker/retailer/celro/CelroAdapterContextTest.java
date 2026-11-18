package ro.pricechecker.retailer.celro;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import ro.pricechecker.adapter.RetailerAdapter;
import ro.pricechecker.retailer.evomagro.EvomagAdapter;

/** Story 2.10: Spring test contexts hold the real cel.ro and evoMAG adapters (story 2.11). */
@SpringBootTest
class CelroAdapterContextTest {

  private final ApplicationContext context;

  @Autowired
  CelroAdapterContextTest(ApplicationContext context) {
    this.context = context;
  }

  @Test
  @Tag("AC-2.10.1")
  void ac_2_10_1_contextHoldsRealCelroAndEvomagAdapters() {
    Map<String, Class<?>> typeById = new TreeMap<>();
    context
        .getBeansOfType(RetailerAdapter.class)
        .values()
        .forEach(a -> assertThat(typeById.put(a.retailerId(), a.getClass())).isNull());
    // altex.ro has its own real (disabled) adapter since story 4.1.
    assertThat(typeById).containsOnlyKeys("altex.ro", "cel.ro", "evomag.ro");
    assertThat(typeById.get("cel.ro")).isEqualTo(CelroAdapter.class);
    assertThat(typeById.get("evomag.ro")).isEqualTo(EvomagAdapter.class);
  }
}
