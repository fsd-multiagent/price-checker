package ro.pricechecker.e2e;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import ro.pricechecker.adapter.RetailerHttp;
import ro.pricechecker.registry.RetailerConfig;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Story 2.14 by Eufrosina (QA): the same journeys as {@code web/e2e/journeys.spec.ts}, checked over
 * HTTP against the e2e backend context ({@link E2eBeans}: test register, {@link FixtureHttp} as the
 * only retailer HTTP). The Playwright suite runs only in CI; this test runs in {@code
 * backend/gradlew -p backend check}, so the orchestrator's tests gate also covers the backend half
 * of each AC (T-1, T-8). Each test uses its own EAN, as the Playwright suite does (T-5).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(E2eBeans.class)
class E2eJourneysApiTest {

  private static final Path DIR = tempDir();
  private static final HttpClient CLIENT =
      HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
  private static final JsonMapper JSON = JsonMapper.builder().build();

  @LocalServerPort int port;
  @Autowired FixtureHttp fixtureHttp;
  @Autowired Map<String, RetailerHttp> retailerHttpBeans;

  private static Path tempDir() {
    try {
      Path dir = Files.createTempDirectory("price-checker-e2e-api");
      StringBuilder runtime = new StringBuilder("retailers:\n");
      for (RetailerConfig retailer : E2eBeans.registry().retailers()) {
        runtime.append("  ").append(retailer.domain()).append(": {fetching: allowed}\n");
      }
      Files.writeString(dir.resolve("runtime.yaml"), runtime.toString());
      return dir;
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  @DynamicPropertySource
  static void files(DynamicPropertyRegistry registry) {
    registry.add("price-checker.runtime-file", () -> DIR.resolve("runtime.yaml").toString());
    registry.add("price-checker.blocks-file", () -> DIR.resolve("blocks.json").toString());
  }

  /** Ends any stub hang still holding a pool thread once a test has checked its rows (T-5). */
  @AfterEach
  void releaseHangs() {
    fixtureHttp.release();
  }

  @Test
  @Tag("AC-2.14.1")
  void ac_2_14_1_uj1_trailingWhitespaceEanFindsBothRowsAndMarksTheCheaperLowest() throws Exception {
    JsonNode body = lookup("5940000000011  ");

    assertThat(body.get("ean").asString()).isEqualTo("5940000000011");
    List<JsonNode> rows = rows(body);
    assertThat(rows).extracting(r -> r.get("status").asString()).containsOnly("FOUND");
    assertThat(rows)
        .extracting(r -> r.get("retailerId").asString())
        .containsExactlyInAnyOrder("cel.ro", "evomag.ro");
    assertThat(rows).filteredOn(r -> r.get("lowest").asBoolean()).hasSize(1);
    JsonNode lowest = row(rows, "cel.ro");
    JsonNode other = row(rows, "evomag.ro");
    assertThat(lowest.get("lowest").asBoolean()).isTrue();
    assertThat(lowest.get("priceMinor").asLong()).isLessThan(other.get("priceMinor").asLong());
  }

  @Test
  @Tag("AC-2.14.2")
  void ac_2_14_2_uj2_hangingRetailerIsUnavailableAndTheOtherIsLowestWithinTheDeadline()
      throws Exception {
    long started = System.nanoTime();
    JsonNode body = lookup("5940000000028");
    Duration took = Duration.ofNanos(System.nanoTime() - started);

    List<JsonNode> rows = rows(body);
    JsonNode hanging = row(rows, "evomag.ro");
    assertThat(hanging.get("status").asString()).isEqualTo("UNAVAILABLE");
    assertThat(hanging.get("lowest").asBoolean()).isFalse();
    assertThat(hanging.has("priceMinor")).isFalse();
    JsonNode found = row(rows, "cel.ro");
    assertThat(found.get("status").asString()).isEqualTo("FOUND");
    assertThat(found.get("lowest").asBoolean()).isTrue();
    assertThat(rows).filteredOn(r -> r.get("status").asString().equals("FOUND")).hasSize(1);
    // The Lookup deadline (1500 ms) ended the row, not the stub's 60 s hang. The bound (30 s) is
    // deliberately loose so a slow runner cannot fail it; the Playwright test checks the same AC.
    assertThat(took).isLessThan(E2eBeans.HANG.dividedBy(2));
  }

  @Test
  @Tag("AC-2.14.3")
  void ac_2_14_3_repeatedLookupKeepsFetchedAtAndSendsNoSecondRequest() throws Exception {
    String ean = "5940000000035";
    List<JsonNode> first = rows(lookup(ean));
    int afterFirst = stubRequests(ean);
    assertThat(afterFirst).isEqualTo(2);

    List<JsonNode> second = rows(lookup(ean));

    for (String id : List.of("cel.ro", "evomag.ro")) {
      assertThat(row(second, id).get("status").asString()).isEqualTo("FOUND");
      assertThat(row(second, id).get("fetchedAt").asString())
          .isEqualTo(row(first, id).get("fetchedAt").asString());
    }
    assertThat(stubRequests(ean)).isEqualTo(afterFirst);
    assertThat(fixtureHttp.requests(ean)).isEqualTo(afterFirst);
  }

  @Test
  @Tag("AC-2.14.4")
  void ac_2_14_4_fixtureStubIsTheOnlyRetailerHttpInTheE2eContext() {
    assertThat(retailerHttpBeans.values()).containsExactly(fixtureHttp);
  }

  @Test
  @Tag("AC-2.14.4")
  void ac_2_14_4_unknownEanSendsNothingToTheStub() throws Exception {
    // A valid EAN outside the e2e known-EANs file never reaches retailer HTTP (AD-10, T-3).
    String ean = "5940000000042";
    List<JsonNode> rows = rows(lookup(ean));

    assertThat(rows).extracting(r -> r.get("status").asString()).containsOnly("NOT_FOUND");
    assertThat(fixtureHttp.requests(ean)).isZero();
  }

  private JsonNode lookup(String ean) throws IOException, InterruptedException {
    HttpResponse<String> response =
        get("/api/lookup?ean=" + URLEncoder.encode(ean, StandardCharsets.UTF_8));
    assertThat(response.statusCode()).isEqualTo(200);
    return JSON.readTree(response.body());
  }

  private int stubRequests(String ean) throws IOException, InterruptedException {
    HttpResponse<String> response = get("/e2e/requests?ean=" + ean);
    assertThat(response.statusCode()).isEqualTo(200);
    return JSON.readTree(response.body()).get("requests").asInt();
  }

  private HttpResponse<String> get(String pathAndQuery) throws IOException, InterruptedException {
    HttpRequest request =
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + pathAndQuery))
            .timeout(Duration.ofSeconds(20))
            .GET()
            .build();
    return CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
  }

  private static List<JsonNode> rows(JsonNode body) {
    return StreamSupport.stream(body.get("rows").spliterator(), false).toList();
  }

  private static JsonNode row(List<JsonNode> rows, String retailerId) {
    return rows.stream()
        .filter(r -> r.get("retailerId").asString().equals(retailerId))
        .findFirst()
        .orElseThrow(() -> new AssertionError("no row for " + retailerId));
  }
}
