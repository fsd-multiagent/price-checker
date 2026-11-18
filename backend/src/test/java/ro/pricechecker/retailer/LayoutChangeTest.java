package ro.pricechecker.retailer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import ro.pricechecker.adapter.AdapterOutcome;
import ro.pricechecker.adapter.Availability;
import ro.pricechecker.adapter.PageFacts;
import ro.pricechecker.adapter.RetailerAdapter;
import ro.pricechecker.adapter.RetailerHttp;
import ro.pricechecker.ean.Ean;
import ro.pricechecker.retailer.celro.CelroAdapter;
import ro.pricechecker.retailer.evomagro.EvomagAdapter;

/**
 * Story 4.2: a simulated layout change ({@code layout-changed.html}) makes the fact check fail for
 * each real Adapter, naming the retailer and {@code price}; the normal page passes. This is the
 * only adapter test that reads {@code layout-changed.html}, and it asserts the failure, so CI stays
 * green (AC 4.2.1, AC 4.2.3). Pages come from {@code fixtures/} only (T-2, T-3).
 */
class LayoutChangeTest {

  static final String LAYOUT_CHANGED = "layout-changed.html";
  static final String NORMAL = "product-jsonld.html";

  /**
   * Fails with {@code <retailer>: missing <facts>} when the outcome lacks price, currency,
   * availability or EAN; a non-{@code Found} outcome misses all of them.
   */
  static void requireFacts(String retailerId, AdapterOutcome outcome) {
    List<String> missing = new ArrayList<>();
    if (outcome instanceof AdapterOutcome.Found(PageFacts facts)) {
      if (facts.priceMinor() == null) {
        missing.add("price");
      }
      if (facts.currency() == null) {
        missing.add("currency");
      }
      if (facts.availability() == null || facts.availability() == Availability.UNKNOWN) {
        missing.add("availability");
      }
      if (facts.eanOnPage() == null) {
        missing.add("ean");
      }
    } else {
      missing.addAll(List.of("price", "currency", "availability", "ean"));
    }
    if (!missing.isEmpty()) {
      throw new AssertionError(retailerId + ": missing " + String.join(", ", missing));
    }
  }

  static Stream<Arguments> adapters() {
    return Stream.of(
        Arguments.of(new CelroAdapter(), "5941234567899", "https://www.cel.ro/produs-sintetic/"),
        Arguments.of(
            new EvomagAdapter(), "5901234123457", "https://www.evomag.ro/produs-sintetic"));
  }

  /** Serves one file from {@code fixtures/<retailer>/}; nothing contacts the retailer (T-3). */
  private static RetailerHttp fixture(String retailerId, String name) {
    return url -> {
      try {
        return Files.readString(Path.of("..", "fixtures", retailerId, name));
      } catch (IOException e) {
        throw new UncheckedIOException(e);
      }
    };
  }

  private static AdapterOutcome run(RetailerAdapter adapter, String ean, String url, String page) {
    return adapter.fetchAndExtract(
        Ean.parse(ean), URI.create(url), fixture(adapter.retailerId(), page));
  }

  @ParameterizedTest(name = "{2}")
  @MethodSource("adapters")
  @Tag("AC-4.2.1")
  @Tag("AC-4.2.3")
  void ac_4_2_1_normalPagePassesTheFactCheck(RetailerAdapter adapter, String ean, String url) {
    AdapterOutcome outcome = run(adapter, ean, url, NORMAL);

    assertThatCode(() -> requireFacts(adapter.retailerId(), outcome)).doesNotThrowAnyException();
  }

  @ParameterizedTest(name = "{2}")
  @MethodSource("adapters")
  @Tag("AC-4.2.1")
  @Tag("AC-4.2.3")
  void ac_4_2_1_layoutChangedPageFailsNamingRetailerAndPrice(
      RetailerAdapter adapter, String ean, String url) {
    AdapterOutcome outcome = run(adapter, ean, url, LAYOUT_CHANGED);

    assertThat(outcome).isInstanceOf(AdapterOutcome.Found.class);
    assertThatThrownBy(() -> requireFacts(adapter.retailerId(), outcome))
        .isInstanceOf(AssertionError.class)
        .hasMessage(adapter.retailerId() + ": missing price");
  }
}
