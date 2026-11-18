package ro.pricechecker.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.net.URI;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import ro.pricechecker.ean.Ean;
import ro.pricechecker.lookup.LookupLog;
import ro.pricechecker.lookup.LookupResult;
import ro.pricechecker.lookup.LookupRow;
import ro.pricechecker.lookup.LookupService;
import ro.pricechecker.lookup.RowAvailability;
import ro.pricechecker.lookup.RowStatus;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** AC 1.4.2 and AC 1.4.3: {@code GET /api/lookup} validates first and logs every rejection. */
@WebMvcTest(LookupController.class)
@Import({LookupLog.class, LookupControllerTest.FixedClock.class})
@ExtendWith(OutputCaptureExtension.class)
class LookupControllerTest {

  private static final Path CASES = Path.of("../api/ean-cases.json");
  private static final Set<String> ALLOWLIST =
      Set.of("ts", "level", "event", "ean", "retailer", "outcome", "durationMs");
  private static final String USER_AGENT = "Mozilla/5.0 (UA-MARKER-7f3c)";
  private static final String CLIENT_IP = "203.0.113.77";

  @TestConfiguration(proxyBeanMethods = false)
  static class FixedClock {
    @Bean
    Clock clock() {
      return Clock.fixed(Instant.parse("2026-11-18T10:15:30Z"), ZoneOffset.UTC);
    }
  }

  @MockitoBean private LookupService lookupService;

  private final MockMvc mvc;

  @Autowired
  LookupControllerTest(MockMvc mvc) {
    this.mvc = mvc;
  }

  private static List<JsonNode> invalidCases() {
    JsonNode root = JsonMapper.builder().build().readTree(CASES.toFile());
    List<JsonNode> list = new ArrayList<>();
    root.get("cases")
        .forEach(c -> {
          if (!c.get("valid").booleanValue()) {
            list.add(c);
          }
        });
    assertThat(list).isNotEmpty();
    return list;
  }

  private static List<JsonNode> logLines(CapturedOutput output, int from) {
    return output
        .getOut()
        .substring(from)
        .lines()
        .filter(l -> l.startsWith("{"))
        .map(l -> JsonMapper.builder().build().readTree(l))
        .filter(n -> n.has("event"))
        .toList();
  }

  private void expectRejected(CapturedOutput output, String query, String code, String rawInput)
      throws Exception {
    int from = output.getOut().length();
    mvc.perform(
            get(URI.create("/api/lookup" + query))
                .header("User-Agent", USER_AGENT)
                .header("X-Forwarded-For", CLIENT_IP)
                .with(r -> {
                  r.setRemoteAddr(CLIENT_IP);
                  return r;
                }))
        .andExpect(status().isBadRequest())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.code").value(code))
        .andExpect(jsonPath("$.status").value(400));

    List<JsonNode> lines = logLines(output, from);
    assertThat(lines).as(query).hasSize(1);
    JsonNode line = lines.get(0);
    line.propertyNames().forEach(k -> assertThat(ALLOWLIST).as(query).contains(k));
    assertThat(line.get("event").stringValue()).isEqualTo("ean_rejected");
    assertThat(line.get("outcome").stringValue()).isEqualTo(code);

    String written = output.getOut().substring(from);
    assertThat(written)
        .doesNotContain(USER_AGENT)
        .doesNotContain("UA-MARKER")
        .doesNotContain(CLIENT_IP);
    String trimmed = rawInput.strip();
    if (!trimmed.isEmpty()) {
      assertThat(written).as(query).doesNotContain(trimmed);
    }
  }

  @Test
  @Tag("AC-1.4.2")
  @Tag("AC-1.4.3")
  void ac_1_4_2_everyInvalidSharedCaseIs400ProblemJsonAndLoggedOnce(CapturedOutput output)
      throws Exception {
    for (JsonNode c : invalidCases()) {
      String input = c.get("input").stringValue();
      // Pass the value as a raw parameter so MockMvc does not re-interpret it.
      int from = output.getOut().length();
      mvc.perform(get("/api/lookup").param("ean", input).header("User-Agent", USER_AGENT))
          .andExpect(status().isBadRequest())
          .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
          .andExpect(jsonPath("$.code").value(c.get("code").stringValue()));
      List<JsonNode> lines = logLines(output, from);
      assertThat(lines).as("%s", c).hasSize(1);
      assertThat(lines.get(0).get("outcome").stringValue()).isEqualTo(c.get("code").stringValue());
      lines.get(0).propertyNames().forEach(k -> assertThat(ALLOWLIST).contains(k));
      String written = output.getOut().substring(from);
      assertThat(written).doesNotContain(USER_AGENT);
      if (!input.isBlank()) {
        assertThat(written).as("%s", c).doesNotContain(input.strip());
      }
    }
  }

  @Test
  @Tag("AC-1.4.2")
  @Tag("AC-1.4.3")
  void ac_1_4_2_missingParameterIsEanEmpty(CapturedOutput output) throws Exception {
    expectRejected(output, "", "EAN_EMPTY", "");
  }

  @Test
  @Tag("AC-1.4.2")
  @Tag("AC-1.4.3")
  void ac_1_4_2_rejectionLogHasNoInputIpOrUserAgent(CapturedOutput output) throws Exception {
    expectRejected(output, "?ean=96385075", "EAN_CHECK_DIGIT", "96385075");
    expectRejected(output, "?ean=4006%20381333931", "EAN_NON_DIGIT", "4006 381333931");
    expectRejected(output, "?ean=", "EAN_EMPTY", "");
  }

  @Test
  @Tag("AC-1.4.2")
  void ac_1_4_2_nonAsciiDigitsAreEanNonDigit(CapturedOutput output) throws Exception {
    String arabicIndic =
        "4006381333931"
            .chars()
            .mapToObj(d -> Character.toString(0x0660 + d - '0'))
            .reduce("", String::concat);
    mvc.perform(get("/api/lookup").param("ean", arabicIndic))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("EAN_NON_DIGIT"));
    assertThat(output.getOut()).doesNotContain(arabicIndic);
  }

  @Test
  @Tag("AC-1.4.2")
  void ac_1_4_2_validEanIsTrimmedAndReturnsEmptyRows(CapturedOutput output) throws Exception {
    when(lookupService.lookup(any(Ean.class)))
        .thenAnswer(i -> new LookupResult(i.<Ean>getArgument(0).value(), List.of()));
    mvc.perform(get(URI.create("/api/lookup?ean=%205901234123457%09")))
        .andExpect(status().isOk())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
        .andExpect(jsonPath("$.ean").value("5901234123457"))
        .andExpect(jsonPath("$.rows").isArray())
        .andExpect(jsonPath("$.rows").isEmpty());
    mvc.perform(
            get("/api/lookup")
                .param(
                    "ean",
                    Character.toString(0x3000) + "5901234123457" + Character.toString(0xFEFF)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.ean").value("5901234123457"));
    assertThat(output.getOut()).doesNotContain("ean_rejected");
  }

  @Test
  @Tag("AC-1.6.4")
  void ac_1_6_4_rowsSerializeAndNonFoundRowsCarryOnlyFourFields() throws Exception {
    Instant at = Instant.parse("2026-11-18T10:15:30Z");
    when(lookupService.lookup(any(Ean.class)))
        .thenReturn(
            new LookupResult(
                "5901234123457",
                List.of(
                    new LookupRow(
                        "a.example",
                        "a.example",
                        RowStatus.FOUND,
                        12345L,
                        RowAvailability.IN_STOCK,
                        URI.create("https://a.example/p"),
                        at,
                        true),
                    new LookupRow(
                        "b.example",
                        "b.example",
                        RowStatus.UNAVAILABLE,
                        null,
                        null,
                        null,
                        null,
                        false))));
    String body =
        mvc.perform(get("/api/lookup").param("ean", "5901234123457"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.rows[0].status").value("FOUND"))
            .andExpect(jsonPath("$.rows[0].priceMinor").value(12345))
            .andExpect(jsonPath("$.rows[0].availability").value("IN_STOCK"))
            .andExpect(jsonPath("$.rows[0].url").value("https://a.example/p"))
            .andExpect(jsonPath("$.rows[0].fetchedAt").value("2026-11-18T10:15:30Z"))
            .andExpect(jsonPath("$.rows[0].lowest").value(true))
            .andExpect(jsonPath("$.rows[1].status").value("UNAVAILABLE"))
            .andReturn()
            .getResponse()
            .getContentAsString();
    JsonNode row = JsonMapper.builder().build().readTree(body).get("rows").get(1);
    assertThat(row.propertyNames())
        .containsExactlyInAnyOrder("retailerId", "retailerName", "status", "lowest");
    assertThat(row.get("lowest").booleanValue()).isFalse();
  }
}
