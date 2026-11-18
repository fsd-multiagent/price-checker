package ro.pricechecker.lookup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import ro.pricechecker.registry.Registry;
import ro.pricechecker.registry.TestRegistries;

@Tag("AC-1.6.1")
class AdapterCatalogTest {

  private final Registry registry = TestRegistries.mixed();

  @Test
  void ac_1_6_1_matchingIdsStart() {
    assertThatCode(() ->
            new AdapterCatalog(
                List.of(
                    StubAdapters.noop("a.example"),
                    StubAdapters.noop("b.example"),
                    StubAdapters.noop("c.example")),
                registry))
        .doesNotThrowAnyException();
  }

  @Test
  void ac_1_6_1_missingAdapterFailsNamingTheId() {
    assertThatThrownBy(() ->
            new AdapterCatalog(
                List.of(StubAdapters.noop("a.example"), StubAdapters.noop("b.example")), registry))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("c.example");
  }

  @Test
  void ac_1_6_1_extraAdapterFailsNamingTheId() {
    assertThatThrownBy(() ->
            new AdapterCatalog(
                List.of(
                    StubAdapters.noop("a.example"),
                    StubAdapters.noop("b.example"),
                    StubAdapters.noop("c.example"),
                    StubAdapters.noop("z.example")),
                registry))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("z.example");
  }

  @Test
  void ac_1_6_1_duplicateAdapterFailsNamingTheId() {
    assertThatThrownBy(() ->
            new AdapterCatalog(
                List.of(
                    StubAdapters.noop("a.example"),
                    StubAdapters.noop("a.example"),
                    StubAdapters.noop("b.example"),
                    StubAdapters.noop("c.example")),
                registry))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("duplicate")
        .hasMessageContaining("a.example");
  }

  @Test
  void ac_1_6_1_findReturnsAdapterById() {
    AdapterCatalog catalog =
        new AdapterCatalog(
            List.of(
                StubAdapters.noop("a.example"),
                StubAdapters.noop("b.example"),
                StubAdapters.noop("c.example")),
            registry);
    assertThat(catalog.find("b.example")).isPresent();
    assertThat(catalog.find("nope")).isEmpty();
  }
}
