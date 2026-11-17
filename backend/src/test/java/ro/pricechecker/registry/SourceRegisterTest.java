package ro.pricechecker.registry;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Optional;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@Tag("AC-1.5.1")
class SourceRegisterTest {

  static String register(String... rows) {
    StringBuilder sb =
        new StringBuilder(
            """
            # Source register

            ## Retailers

            | Retailer | Domain | Status | Limits | Notes |
            |---|---|---|---|---|
            """);
    for (String row : rows) {
      sb.append(row).append('\n');
    }
    sb.append(
        "\n## Still needed\n\n| Domain | Status |\n|---|---|\n| `x.example` | Approved 2026-01-01 |\n");
    return sb.toString();
  }

  static String row(String domain, String status, String limits) {
    return "| Shop | `" + domain + "` | " + status + " | " + limits + " | n |";
  }

  @Test
  void ac_1_5_1_approvedWithRealDateApproves() {
    SourceRegister r = SourceRegister.parse(register(row("a.example", "Approved 2026-11-20", "—")));
    assertThat(r.errors()).isEmpty();
    assertThat(r.isApproved("a.example")).isTrue();
    assertThat(r.approvedDomains()).containsExactly("a.example");
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "Proposed",
        "Approved",
        "Approved 2026-13-40",
        "Approved 2026-02-30",
        "approved 2026-11-20",
        "Retired 2026-11-20",
        "Not checked",
        "Rejected",
        "Approved 2026-11-20 by someone"
      })
  void ac_1_5_1_otherStatusesDoNotApprove(String status) {
    SourceRegister r = SourceRegister.parse(register(row("a.example", status, "—")));
    assertThat(r.errors()).isEmpty();
    assertThat(r.isApproved("a.example")).isFalse();
  }

  @Test
  void ac_1_5_1_tablesOutsideRetailersSectionAreIgnored() {
    SourceRegister r = SourceRegister.parse(register(row("a.example", "Proposed", "—")));
    assertThat(r.rows()).containsOnlyKeys("a.example");
    assertThat(r.isApproved("x.example")).isFalse();
  }

  @Test
  void ac_1_5_1_noRetailersTableApprovesNothing() {
    SourceRegister r =
        SourceRegister.parse(
            "# Register\n\n| Domain | Status |\n|---|---|\n| `a.example` | Approved 2026-11-20 |\n");
    assertThat(r.errors()).isNotEmpty();
    assertThat(r.approvedDomains()).isEmpty();
  }

  @Test
  void ac_1_5_1_missingDomainColumnApprovesNothing() {
    String md =
        "## Retailers\n\n| Site | Status |\n|---|---|\n| `a.example` | Approved 2026-11-20 |\n";
    SourceRegister r = SourceRegister.parse(md);
    assertThat(r.errors()).isNotEmpty();
    assertThat(r.approvedDomains()).isEmpty();
  }

  @Test
  void ac_1_5_1_missingStatusColumnApprovesNothing() {
    String md =
        "## Retailers\n\n| Domain | State |\n|---|---|\n| `a.example` | Approved 2026-11-20 |\n";
    SourceRegister r = SourceRegister.parse(md);
    assertThat(r.errors()).isNotEmpty();
    assertThat(r.approvedDomains()).isEmpty();
  }

  @Test
  void ac_1_5_1_wrongCellCountApprovesNothing() {
    SourceRegister r =
        SourceRegister.parse(
            register(
                row("a.example", "Approved 2026-11-20", "—"),
                "| Shop | `b.example` | Approved 2026-11-20 | — | n | extra |"));
    assertThat(r.errors()).isNotEmpty();
    assertThat(r.approvedDomains()).isEmpty();
    assertThat(r.isApproved("a.example")).isFalse();
  }

  @Test
  void ac_1_5_1_duplicateDomainApprovesNothing() {
    SourceRegister r =
        SourceRegister.parse(
            register(
                row("a.example", "Approved 2026-11-20", "—"), row("a.example", "Rejected", "—")));
    assertThat(r.errors()).isNotEmpty();
    assertThat(r.approvedDomains()).isEmpty();
  }

  @Test
  void ac_1_5_1_nullOrEmptyInputApprovesNothing() {
    assertThat(SourceRegister.parse(null).errors()).isNotEmpty();
    assertThat(SourceRegister.parse("").approvedDomains()).isEmpty();
  }

  @Test
  void ac_1_5_1_limitsParsedFromRegister() {
    SourceRegister r =
        SourceRegister.parse(
            register(
                row("a.example", "Proposed", "1 request / 2 s, concurrency 1"),
                row("b.example", "Proposed", "—")));
    assertThat(r.limits("a.example")).contains(new RegisterLimits(2000, 1));
    assertThat(r.limits("b.example")).isEmpty();
    assertThat(r.limits("c.example")).isEmpty();
  }

  @Test
  void ac_1_5_1_limitsFormats() {
    assertThat(RegisterLimits.parse("1 request / 2 s, concurrency 1"))
        .isEqualTo(Optional.of(new RegisterLimits(2000, 1)));
    assertThat(RegisterLimits.parse("2 requests / 1 s, concurrency 3"))
        .isEqualTo(Optional.of(new RegisterLimits(500, 3)));
    assertThat(RegisterLimits.parse("3 requests / 1 s, concurrency 1"))
        .isEqualTo(Optional.of(new RegisterLimits(334, 1)));
    assertThat(RegisterLimits.parse("1 request / 99999999999999999 s, concurrency 1")).isEmpty();
    assertThat(RegisterLimits.parse("—")).isEmpty();
    assertThat(RegisterLimits.parse("fast please")).isEmpty();
    assertThat(RegisterLimits.parse("0 requests / 2 s, concurrency 1")).isEmpty();
    assertThat(RegisterLimits.parse(null)).isEmpty();
  }
}
