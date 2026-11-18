package ro.pricechecker.lookup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.groups.Tuple.tuple;

import java.lang.reflect.RecordComponent;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Supplier;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import ro.pricechecker.adapter.AdapterOutcome;
import ro.pricechecker.adapter.Availability;
import ro.pricechecker.adapter.PageFacts;
import ro.pricechecker.adapter.RetailerAdapter;
import ro.pricechecker.adapter.RetailerHttp;
import ro.pricechecker.adapter.UnavailableReason;
import ro.pricechecker.cache.ResultCache;
import ro.pricechecker.ean.Ean;
import ro.pricechecker.registry.Registry;
import ro.pricechecker.registry.TestRegistries;

/**
 * Story 1.6 gaps found by Eufrosina (QA): the shape of the AD-7 port types, the one-to-one check in
 * a real Spring context, several Approved retailers at once, name ties inside every status group,
 * and every adapter outcome kept inside its own row.
 */
class LookupSkeletonAcceptanceGapsTest {

  private static final Clock CLOCK =
      Clock.fixed(Instant.parse("2026-11-18T10:15:30Z"), ZoneOffset.UTC);
  private static final RetailerHttp HTTP = url -> "";
  private static final String EAN = TestRegistries.EAN_A;

  private final List<String> calls = new CopyOnWriteArrayList<>();

  /** a, b, d Approved and enabled; e Approved and disabled; c Proposed and disabled. */
  private static Registry fiveRetailers() {
    String limits = "1 request / 2 s, concurrency 1";
    StringBuilder reg =
        new StringBuilder(
            """
            # Source register

            ## Retailers

            | Retailer | Domain | Status | Limits | Notes |
            |---|---|---|---|---|
            """);
    for (String[] r :
        new String[][] {
          {"a.example", "Approved 2026-11-20"},
          {"b.example", "Approved 2026-11-20"},
          {"c.example", "Proposed"},
          {"d.example", "Approved 2026-11-20"},
          {"e.example", "Approved 2026-11-20"}
        }) {
      reg.append("| Shop | `")
          .append(r[0])
          .append("` | ")
          .append(r[1])
          .append(" | ")
          .append(limits)
          .append(" | n |\n");
    }
    String cfg = "resolution: curated, minIntervalMs: 2000, concurrency: 1, deadlineMs: 4000}";
    String retailers =
        "retailers:\n"
            + "  a.example: {adapter: enabled, "
            + cfg
            + "\n  b.example: {adapter: enabled, "
            + cfg
            + "\n  c.example: {adapter: disabled, "
            + cfg
            + "\n  d.example: {adapter: enabled, "
            + cfg
            + "\n  e.example: {adapter: disabled, "
            + cfg
            + "\n";
    StringBuilder eans = new StringBuilder("knownEans:\n");
    for (String d : List.of("a.example", "b.example", "d.example", "e.example")) {
      eans.append("  ")
          .append(d)
          .append(":\n    - {ean: \"")
          .append(EAN)
          .append("\", url: \"https://")
          .append(d)
          .append("/p/1\"}\n    - {ean: \"")
          .append(TestRegistries.EAN_B)
          .append("\", url: \"https://")
          .append(d)
          .append("/p/2\"}\n");
    }
    return TestRegistries.of(reg.toString(), retailers, eans.toString());
  }

  private RetailerAdapter adapter(String id, Supplier<AdapterOutcome> outcome) {
    return new RetailerAdapter() {
      @Override
      public String retailerId() {
        return id;
      }

      @Override
      public AdapterOutcome fetchAndExtract(Ean ean, URI productUrl, RetailerHttp http) {
        calls.add(id);
        return outcome.get();
      }
    };
  }

  private LookupResult lookup(Map<String, Supplier<AdapterOutcome>> outcomes) {
    Registry registry = fiveRetailers();
    List<RetailerAdapter> adapters = new ArrayList<>();
    for (String id : List.of("a.example", "b.example", "c.example", "d.example", "e.example")) {
      adapters.add(adapter(id, outcomes.getOrDefault(id, AdapterOutcome.NotFound::new)));
    }
    return new LookupService(
            registry,
            new AdapterCatalog(adapters, registry),
            Optional.of(id -> HTTP),
            CLOCK,
            TestRegistries.runtimeAllowing(
                CLOCK, "a.example", "b.example", "c.example", "d.example", "e.example"),
            TestRegistries.blockStore(TestRegistries.runtimeAllowing(CLOCK), CLOCK),
            new ResultCache<>(Duration.ofMinutes(15), CLOCK),
            new LookupLog(CLOCK))
        .lookup(Ean.parse(EAN));
  }

  private static Supplier<AdapterOutcome> found(Long price, Availability availability) {
    return () -> new AdapterOutcome.Found(new PageFacts(price, "RON", availability, null, null));
  }

  private static void assertOnlyFourFields(LookupRow row) {
    assertThat(row.priceMinor()).as(row.retailerId()).isNull();
    assertThat(row.availability()).as(row.retailerId()).isNull();
    assertThat(row.url()).as(row.retailerId()).isNull();
    assertThat(row.fetchedAt()).as(row.retailerId()).isNull();
    assertThat(row.lowest()).as(row.retailerId()).isFalse();
    assertThat(row.retailerId()).isNotBlank();
    assertThat(row.retailerName()).isNotBlank();
  }

  // ---- AC 1.6.1 ----

  @Test
  @Tag("AC-1.6.1")
  void ac_1_6_1_pageFactsHasOnlyTheFiveAllowedFields() {
    assertThat(Arrays.stream(PageFacts.class.getRecordComponents()).map(RecordComponent::getName))
        .containsExactly("priceMinor", "currency", "availability", "url", "eanOnPage");
  }

  @Test
  @Tag("AC-1.6.1")
  void ac_1_6_1_adapterOutcomeIsSealedToFoundNotFoundUnavailable() {
    assertThat(AdapterOutcome.class.isSealed()).isTrue();
    assertThat(Arrays.stream(AdapterOutcome.class.getPermittedSubclasses()).map(Class::getName))
        .containsExactlyInAnyOrder(
            AdapterOutcome.Found.class.getName(),
            AdapterOutcome.NotFound.class.getName(),
            AdapterOutcome.Unavailable.class.getName());
    assertThat(
            Arrays.stream(AdapterOutcome.Unavailable.class.getRecordComponents())
                .map(RecordComponent::getType))
        .containsExactly(UnavailableReason.class);
  }

  private ApplicationContextRunner context(String... adapterIds) {
    ApplicationContextRunner runner =
        new ApplicationContextRunner()
            .withBean(Registry.class, TestRegistries::mixed)
            .withBean(AdapterCatalog.class);
    for (String id : adapterIds) {
      runner = runner.withBean("adapter-" + id, RetailerAdapter.class, () -> StubAdapters.noop(id));
    }
    return runner;
  }

  @Test
  @Tag("AC-1.6.1")
  void ac_1_6_1_springContextStartsWhenAdapterBeansMatchConfig() {
    context("a.example", "b.example", "c.example")
        .run(ctx -> {
          assertThat(ctx).hasNotFailed();
          assertThat(ctx.getBean(AdapterCatalog.class).find("c.example")).isPresent();
        });
  }

  @Test
  @Tag("AC-1.6.1")
  void ac_1_6_1_springContextFailsWhenAnAdapterBeanIsMissing() {
    context("a.example", "b.example")
        .run(ctx -> {
          assertThat(ctx).hasFailed();
          assertThat(ctx.getStartupFailure()).rootCause().hasMessageContaining("c.example");
        });
  }

  @Test
  @Tag("AC-1.6.1")
  void ac_1_6_1_springContextFailsWhenAnAdapterBeanHasNoConfig() {
    context("a.example", "b.example", "c.example", "unknown.example")
        .run(ctx -> {
          assertThat(ctx).hasFailed();
          assertThat(ctx.getStartupFailure()).rootCause().hasMessageContaining("unknown.example");
        });
  }

  // ---- AC 1.6.2 ----

  @Test
  @Tag("AC-1.6.2")
  void ac_1_6_2_everyApprovedRetailerGetsExactlyOneRowAndProposedNone() {
    LookupResult result = lookup(Map.of());
    assertThat(result.rows())
        .extracting(LookupRow::retailerId)
        .containsExactlyInAnyOrder("a.example", "b.example", "d.example", "e.example")
        .doesNotHaveDuplicates()
        .doesNotContain("c.example");
    assertThat(result.rows())
        .filteredOn(r -> r.retailerId().equals("e.example"))
        .extracting(LookupRow::status)
        .containsExactly(RowStatus.DISABLED);
    assertThat(calls).containsExactlyInAnyOrder("a.example", "b.example", "d.example");
  }

  @Test
  @Tag("AC-1.6.2")
  void ac_1_6_2_rowCountDoesNotDependOnOutcomes() {
    LookupResult result =
        lookup(
            Map.of(
                "a.example", found(100L, Availability.IN_STOCK),
                "b.example",
                    () -> {
                      throw new IllegalStateException("boom");
                    },
                "d.example", () -> new AdapterOutcome.Unavailable(UnavailableReason.BLOCKED)));
    assertThat(result.rows()).hasSize(4);
  }

  // ---- AC 1.6.3 ----

  @Test
  @Tag("AC-1.6.3")
  void ac_1_6_3_serviceReturnsRowsInOrderWithLowestMarked() {
    LookupResult result =
        lookup(
            Map.of(
                "a.example", found(500L, Availability.IN_STOCK),
                "b.example", found(null, Availability.IN_STOCK),
                "d.example", found(200L, Availability.UNKNOWN)));
    assertThat(result.rows())
        .extracting(LookupRow::retailerId, LookupRow::status, LookupRow::lowest)
        .containsExactly(
            tuple("d.example", RowStatus.FOUND, true),
            tuple("a.example", RowStatus.FOUND, false),
            tuple("b.example", RowStatus.FOUND, false),
            tuple("e.example", RowStatus.DISABLED, false));
  }

  private static LookupRow plain(String name, RowStatus status) {
    return LookupRow.withoutFacts(name, name, status);
  }

  private static LookupRow foundRow(String name, Long price) {
    return new LookupRow(
        name, name, RowStatus.FOUND, price, RowAvailability.IN_STOCK, null, null, false);
  }

  @Test
  @Tag("AC-1.6.3")
  void ac_1_6_3_tiesInsideEveryStatusGroupAreOrderedByName() {
    List<LookupRow> out =
        RowOrder.arrange(
            List.of(
                plain("z-dis", RowStatus.DISABLED),
                plain("a-dis", RowStatus.DISABLED),
                plain("z-un", RowStatus.UNAVAILABLE),
                plain("a-un", RowStatus.UNAVAILABLE),
                plain("z-nf", RowStatus.NOT_FOUND),
                plain("a-nf", RowStatus.NOT_FOUND),
                foundRow("z-np", null),
                foundRow("a-np", null),
                foundRow("z-200", 200L),
                foundRow("a-200", 200L),
                foundRow("m-100", 100L)));
    assertThat(out)
        .extracting(LookupRow::retailerName)
        .containsExactly(
            "m-100", "a-200", "z-200", "a-np", "z-np", "a-nf", "z-nf", "a-un", "z-un", "a-dis",
            "z-dis");
    assertThat(out)
        .filteredOn(LookupRow::lowest)
        .extracting(LookupRow::retailerName)
        .containsExactly("m-100");
  }

  @Test
  @Tag("AC-1.6.3")
  void ac_1_6_3_orderDoesNotDependOnInputOrder() {
    List<LookupRow> rows =
        List.of(
            plain("d", RowStatus.DISABLED),
            foundRow("c", 300L),
            plain("u", RowStatus.UNAVAILABLE),
            foundRow("b", 100L),
            plain("n", RowStatus.NOT_FOUND),
            foundRow("p", null));
    List<LookupRow> expected = RowOrder.arrange(rows);
    for (int shift = 1; shift < rows.size(); shift++) {
      List<LookupRow> rotated = new ArrayList<>(rows.subList(shift, rows.size()));
      rotated.addAll(rows.subList(0, shift));
      assertThat(RowOrder.arrange(rotated)).as("rotation %d", shift).isEqualTo(expected);
      assertThat(RowOrder.arrange(rotated.reversed())).as("reversed %d", shift).isEqualTo(expected);
    }
  }

  @Test
  @Tag("AC-1.6.3")
  void ac_1_6_3_lowestFlagFromInputIsRecomputed() {
    LookupRow stale =
        new LookupRow("a", "a", RowStatus.FOUND, 900L, RowAvailability.IN_STOCK, null, null, true);
    List<LookupRow> out = RowOrder.arrange(List.of(stale, foundRow("b", 100L)));
    assertThat(out)
        .extracting(LookupRow::retailerName, LookupRow::lowest)
        .containsExactly(tuple("b", true), tuple("a", false));
  }

  // ---- AC 1.6.4 ----

  @Test
  @Tag("AC-1.6.4")
  void ac_1_6_4_everyUnavailableReasonIsAnUnavailableRowWithOnlyFourFields() {
    for (UnavailableReason reason : UnavailableReason.values()) {
      calls.clear();
      LookupResult result =
          lookup(Map.of("a.example", () -> new AdapterOutcome.Unavailable(reason)));
      LookupRow row =
          result.rows().stream().filter(r -> r.retailerId().equals("a.example")).findFirst().get();
      assertThat(row.status()).as("%s", reason).isEqualTo(RowStatus.UNAVAILABLE);
      assertOnlyFourFields(row);
    }
  }

  @Test
  @Tag("AC-1.6.4")
  void ac_1_6_4_nullOutcomeIsUnavailable() {
    LookupResult result = lookup(Map.of("a.example", () -> null));
    LookupRow row =
        result.rows().stream().filter(r -> r.retailerId().equals("a.example")).findFirst().get();
    assertThat(row.status()).isEqualTo(RowStatus.UNAVAILABLE);
    assertOnlyFourFields(row);
  }

  @Test
  @Tag("AC-1.6.4")
  void ac_1_6_4_oneThrowingAdapterDoesNotAffectTheOthers() {
    LookupResult result =
        lookup(
            Map.of(
                "a.example", found(100L, Availability.IN_STOCK),
                "b.example",
                    () -> {
                      throw new RuntimeException("boom");
                    },
                "d.example", found(300L, Availability.IN_STOCK)));
    assertThat(result.rows())
        .extracting(LookupRow::retailerId, LookupRow::status)
        .containsExactly(
            tuple("a.example", RowStatus.FOUND),
            tuple("d.example", RowStatus.FOUND),
            tuple("b.example", RowStatus.UNAVAILABLE),
            tuple("e.example", RowStatus.DISABLED));
    assertThat(calls).containsExactlyInAnyOrder("a.example", "b.example", "d.example");
  }

  @Test
  @Tag("AC-1.6.4")
  void ac_1_6_4_everyNonFoundRowCarriesOnlyFourFields() {
    LookupResult result =
        lookup(
            Map.of(
                "a.example", AdapterOutcome.NotFound::new,
                "b.example", () -> new AdapterOutcome.Unavailable(UnavailableReason.TIMEOUT),
                "d.example",
                    () -> {
                      throw new IllegalArgumentException("boom");
                    }));
    assertThat(result.rows())
        .isNotEmpty()
        .allSatisfy(row -> {
          assertThat(row.status()).isNotEqualTo(RowStatus.FOUND);
          assertOnlyFourFields(row);
        });
  }
}
