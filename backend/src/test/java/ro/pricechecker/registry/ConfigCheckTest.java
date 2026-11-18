package ro.pricechecker.registry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static ro.pricechecker.registry.SourceRegisterTest.register;
import static ro.pricechecker.registry.SourceRegisterTest.row;

import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("AC-1.5.2")
@Tag("AC-1.5.3")
class ConfigCheckTest {

  private static final String LIMITS = "1 request / 2 s, concurrency 1";
  private static final String EAN_A = "4006381333931";
  private static final String EAN_B = "96385074";

  private static final SourceRegister APPROVED_A =
      SourceRegister.parse(
          register(
              row("a.example", "Approved 2026-11-20", LIMITS),
              row("b.example", "Proposed", LIMITS)));

  private static RetailerConfig ok(String domain, String adapter) {
    return new RetailerConfig(domain, adapter, "curated", 2000, 1, 4000);
  }

  private static List<KnownEan> twoEans(String domain) {
    return List.of(
        new KnownEan(domain, EAN_A, "https://" + domain + "/p/1"),
        new KnownEan(domain, EAN_B, "https://www." + domain + "/p/2"));
  }

  private static List<String> check(List<RetailerConfig> retailers, List<KnownEan> eans) {
    return ConfigCheck.violations(APPROVED_A, retailers, eans);
  }

  @Test
  void ac_1_5_2_consistentConfigHasNoViolations() {
    assertThat(
            check(
                List.of(ok("a.example", "enabled"), ok("b.example", "disabled")),
                twoEans("a.example")))
        .isEmpty();
  }

  @Test
  void ac_1_5_2_enabledButNotApproved() {
    assertThat(
            check(
                List.of(ok("a.example", "enabled"), ok("b.example", "enabled")),
                twoEans("a.example")))
        .singleElement()
        .asString()
        .contains("b.example", "enabled");
  }

  @Test
  void ac_1_5_2_approvedWithoutEntry() {
    assertThat(check(List.of(ok("b.example", "disabled")), List.of()))
        .anySatisfy(v -> assertThat(v).contains("a.example", "no retailers.yaml entry"));
  }

  @Test
  void ac_1_5_2_resolutionMissingOrScrape() {
    for (String resolution : new String[] {null, "scrape"}) {
      RetailerConfig r = new RetailerConfig("a.example", "enabled", resolution, 2000, 1, 4000);
      assertThat(check(List.of(r), twoEans("a.example")))
          .singleElement()
          .asString()
          .contains("resolution");
    }
  }

  @Test
  void ac_1_5_2_eachAllowedResolutionIsAccepted() {
    for (String resolution : new String[] {"search", "catalog", "curated"}) {
      RetailerConfig r = new RetailerConfig("a.example", "enabled", resolution, 2000, 1, 4000);
      assertThat(check(List.of(r), twoEans("a.example"))).as(resolution).isEmpty();
    }
  }

  @Test
  void ac_1_5_2_unknownAdapterValue() {
    assertThat(check(List.of(ok("a.example", "yes")), twoEans("a.example")))
        .singleElement()
        .asString()
        .contains("adapter");
  }

  @Test
  void ac_1_5_2_looserIntervalOrConcurrency() {
    RetailerConfig fast = new RetailerConfig("a.example", "enabled", "curated", 1999, 1, 4000);
    RetailerConfig wide = new RetailerConfig("a.example", "enabled", "curated", 2000, 2, 4000);
    RetailerConfig missing =
        new RetailerConfig("a.example", "enabled", "curated", null, null, 4000);
    assertThat(check(List.of(fast), twoEans("a.example")))
        .singleElement()
        .asString()
        .contains("minIntervalMs");
    assertThat(check(List.of(wide), twoEans("a.example")))
        .singleElement()
        .asString()
        .contains("concurrency");
    assertThat(check(List.of(missing), twoEans("a.example"))).hasSize(2);
  }

  @Test
  void ac_1_5_2_stricterLimitsAreFine() {
    RetailerConfig slow = new RetailerConfig("a.example", "enabled", "curated", 5000, 1, 4000);
    assertThat(check(List.of(slow), twoEans("a.example"))).isEmpty();
  }

  // AC 1.5.2 as amended by ADR 0024: unparseable Limits fail for an Approved or enabled entry;
  // a disabled, not Approved entry is covered by the ac_4_1_2 tests below.
  @Test
  void ac_1_5_2_unparseableRegisterLimits() {
    SourceRegister reg =
        SourceRegister.parse(register(row("c.example", "Approved 2026-11-20", "—")));
    assertThat(
            ConfigCheck.violations(reg, List.of(ok("c.example", "disabled")), twoEans("c.example")))
        .singleElement()
        .asString()
        .contains("c.example", "Limits");
  }

  @Test
  void ac_1_5_2_unparseableLimitsOnEnabledEntryIsAViolation() {
    SourceRegister reg = SourceRegister.parse(register(row("c.example", "Not checked", "—")));
    assertThat(ConfigCheck.violations(reg, List.of(ok("c.example", "enabled")), List.of()))
        .anySatisfy(v -> assertThat(v).contains("c.example", "Limits"))
        .anySatisfy(v -> assertThat(v).contains("c.example", "enabled"));
  }

  @Test
  void ac_4_1_2_disabledNotCheckedRowWithoutLimitsPasses() {
    SourceRegister reg = SourceRegister.parse(register(row("c.example", "Not checked", "—")));
    assertThat(ConfigCheck.violations(reg, List.of(ok("c.example", "disabled")), List.of()))
        .isEmpty();
  }

  @Test
  void ac_4_1_2_disabledRowWithoutLimitsStillNeedsPositiveValues() {
    SourceRegister reg = SourceRegister.parse(register(row("c.example", "Not checked", "—")));
    RetailerConfig bad = new RetailerConfig("c.example", "disabled", "curated", 0, 0, 4000);
    assertThat(ConfigCheck.violations(reg, List.of(bad), List.of()))
        .anySatisfy(v -> assertThat(v).contains("c.example", "minIntervalMs"))
        .anySatisfy(v -> assertThat(v).contains("c.example", "concurrency"));
  }

  // ADR 0024: missing values on a disabled entry without register Limits are violations too.
  @Test
  void ac_4_1_2_disabledRowWithoutLimitsMissingValuesFails() {
    SourceRegister reg = SourceRegister.parse(register(row("c.example", "Proposed", "—")));
    RetailerConfig missing =
        new RetailerConfig("c.example", "disabled", "curated", null, null, 4000);
    assertThat(ConfigCheck.violations(reg, List.of(missing), List.of()))
        .anySatisfy(v -> assertThat(v).contains("c.example", "minIntervalMs"))
        .anySatisfy(v -> assertThat(v).contains("c.example", "concurrency"));
  }

  // ADR 0024: once register Limits parse, "not looser" applies to disabled, not Approved entries.
  @Test
  void ac_4_1_2_disabledNotApprovedRowWithParseableLimitsMustNotBeLooser() {
    SourceRegister reg = SourceRegister.parse(register(row("c.example", "Not checked", LIMITS)));
    RetailerConfig fast = new RetailerConfig("c.example", "disabled", "curated", 1000, 2, 4000);
    assertThat(ConfigCheck.violations(reg, List.of(fast), List.of()))
        .anySatisfy(v -> assertThat(v).contains("c.example", "minIntervalMs", "looser"))
        .anySatisfy(v -> assertThat(v).contains("c.example", "concurrency", "looser"));
  }

  @Test
  void ac_1_5_2_domainNotInRegister() {
    assertThat(
            check(
                List.of(ok("a.example", "enabled"), ok("z.example", "disabled")),
                twoEans("a.example")))
        .anySatisfy(v -> assertThat(v).contains("z.example", "not a row"));
  }

  @Test
  void ac_1_5_2_registerParseErrorIsAViolation() {
    SourceRegister broken = SourceRegister.parse("no table here");
    assertThat(ConfigCheck.violations(broken, List.of(), List.of()))
        .anySatisfy(v -> assertThat(v).startsWith("source register:"));
  }

  @Test
  void ac_1_5_3_badCheckDigit() {
    List<KnownEan> eans =
        List.of(
            new KnownEan("a.example", "4006381333932", "https://a.example/p/1"),
            new KnownEan("a.example", EAN_B, "https://a.example/p/2"));
    assertThat(check(List.of(ok("a.example", "enabled")), eans))
        .singleElement()
        .asString()
        .contains("4006381333932");
  }

  @Test
  void ac_1_5_3_httpUrlRejected() {
    List<KnownEan> eans =
        List.of(
            new KnownEan("a.example", EAN_A, "http://a.example/p/1"),
            new KnownEan("a.example", EAN_B, "https://a.example/p/2"));
    assertThat(check(List.of(ok("a.example", "enabled")), eans))
        .singleElement()
        .asString()
        .contains("https");
  }

  @Test
  void ac_1_5_3_otherHostRejected() {
    for (String url :
        new String[] {
          "https://b.example/p/1", "https://a.example.evil.example/p/1", "https://x@a.example/p/1"
        }) {
      List<KnownEan> eans =
          List.of(
              new KnownEan("a.example", EAN_A, url),
              new KnownEan("a.example", EAN_B, "https://a.example/p/2"));
      assertThat(check(List.of(ok("a.example", "enabled")), eans)).as(url).hasSize(1);
    }
  }

  @Test
  void ac_1_5_3_approvedNeedsTwoKnownEans() {
    assertThat(check(List.of(ok("a.example", "enabled")), twoEans("a.example").subList(0, 1)))
        .singleElement()
        .asString()
        .contains("a.example", "1 distinct known EANs");
  }

  @Test
  void ac_1_5_3_duplicateEanCountsOnce() {
    List<KnownEan> eans =
        List.of(
            new KnownEan("a.example", EAN_A, "https://a.example/p/1"),
            new KnownEan("a.example", EAN_A, "https://a.example/p/2"));
    assertThat(check(List.of(ok("a.example", "enabled")), eans))
        .singleElement()
        .asString()
        .contains("1 distinct known EANs");
  }

  @Test
  void ac_1_5_3_knownEansForUnconfiguredDomain() {
    List<KnownEan> eans = List.of(new KnownEan("b.example", EAN_A, "https://b.example/p/1"));
    assertThat(check(List.of(ok("a.example", "enabled")), concat(twoEans("a.example"), eans)))
        .singleElement()
        .asString()
        .contains("b.example", "no retailers.yaml entry");
  }

  private static List<KnownEan> concat(List<KnownEan> a, List<KnownEan> b) {
    return java.util.stream.Stream.concat(a.stream(), b.stream()).toList();
  }

  @Test
  void ac_1_5_2_deadlineMissingOrNonPositive() {
    for (Integer deadline : new Integer[] {null, 0, -1}) {
      RetailerConfig r = new RetailerConfig("a.example", "enabled", "curated", 2000, 1, deadline);
      assertThat(check(List.of(r), twoEans("a.example")))
          .as(String.valueOf(deadline))
          .singleElement()
          .asString()
          .contains("deadlineMs");
    }
  }

  @Test
  void ac_1_5_2_concurrencyBelowOne() {
    RetailerConfig r = new RetailerConfig("a.example", "enabled", "curated", 2000, 0, 4000);
    assertThat(check(List.of(r), twoEans("a.example")))
        .singleElement()
        .asString()
        .contains("concurrency");
  }

  @Test
  void ac_1_5_3_gs1CheckDigit() {
    assertThat(ConfigCheck.isValidEan(EAN_A)).isTrue();
    assertThat(ConfigCheck.isValidEan(EAN_B)).isTrue();
    assertThat(ConfigCheck.isValidEan("96385075")).isFalse();
    assertThat(ConfigCheck.isValidEan("123456789012")).isFalse();
    assertThat(ConfigCheck.isValidEan("40063813339a1")).isFalse();
    assertThat(ConfigCheck.isValidEan(null)).isFalse();
  }

  @Test
  void ac_1_5_2_registryRefusesToStartOnViolation() {
    String reg = register(row("a.example", "Proposed", LIMITS));
    String retailers =
        """
        retailers:
          a.example:
            adapter: enabled
            resolution: curated
            minIntervalMs: 2000
            concurrency: 1
            deadlineMs: 4000
        """;
    assertThatThrownBy(() -> new Registry(reg, retailers, "knownEans: {}"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("a.example");
  }
}
