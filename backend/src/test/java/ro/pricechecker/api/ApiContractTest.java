package ro.pricechecker.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

/** AC-1.3.1 and AC-1.3.2: the contract in {@code api/openapi.yaml} and the generated interfaces. */
class ApiContractTest {

  private static final Path CONTRACT = Path.of("../api/openapi.yaml");
  private static final String GENERATED = "ro.pricechecker.api.generated";
  private static final Pattern GENERATED_PACKAGE =
      Pattern.compile("(?m)^\\s*package\\s+" + Pattern.quote(GENERATED) + "\\s*[.;]");

  private static Map<String, Object> contract() throws IOException {
    try (Reader reader = Files.newBufferedReader(CONTRACT)) {
      return new Yaml().load(reader);
    }
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> map(Object node, String... keys) {
    Object current = node;
    for (String key : keys) {
      assertThat(current).as("parent of %s", key).isInstanceOf(Map.class);
      current = ((Map<String, Object>) current).get(key);
      assertThat(current).as("key %s", key).isNotNull();
    }
    assertThat(current).isInstanceOf(Map.class);
    return (Map<String, Object>) current;
  }

  @SuppressWarnings("unchecked")
  private static List<Object> list(Object node) {
    assertThat(node).isInstanceOf(List.class);
    return (List<Object>) node;
  }

  private static Map<String, Object> schema(String name) throws IOException {
    return map(contract(), "components", "schemas", name);
  }

  private static Map<String, Object> property(String schema, String name) throws IOException {
    return map(schema(schema), "properties", name);
  }

  private static String ref(String schema) {
    return "#/components/schemas/" + schema;
  }

  @Test
  @Tag("AC-1.3.1")
  void ac_1_3_1_isOpenApi303WithExactlyTheTwoGetEndpoints() throws IOException {
    Map<String, Object> api = contract();
    assertThat(api.get("openapi")).isEqualTo("3.0.3");
    Map<String, Object> paths = map(api, "paths");
    assertThat(paths.keySet()).containsExactlyInAnyOrder("/api/lookup", "/api/status");
    assertThat(map(paths, "/api/lookup").keySet()).containsExactly("get");
    assertThat(map(paths, "/api/status").keySet()).containsExactly("get");
  }

  @Test
  @Tag("AC-1.3.1")
  void ac_1_3_1_lookupTakesRequiredEanAndReturnsLookupResponseOrProblem() throws IOException {
    Map<String, Object> get = map(contract(), "paths", "/api/lookup", "get");
    List<Object> parameters = list(get.get("parameters"));
    assertThat(parameters).hasSize(1);
    Map<String, Object> ean = map(parameters.get(0));
    assertThat(ean)
        .containsEntry("name", "ean")
        .containsEntry("in", "query")
        .containsEntry("required", true);
    assertThat(map(ean, "schema")).containsEntry("type", "string");

    Map<String, Object> responses = map(get, "responses");
    assertThat(responses.keySet()).containsExactlyInAnyOrder("200", "400");
    assertThat(map(responses, "200", "content").keySet()).containsExactly("application/json");
    assertThat(map(responses, "200", "content", "application/json", "schema"))
        .containsEntry("$ref", ref("LookupResponse"));
    assertThat(map(responses, "400", "content").keySet())
        .containsExactly("application/problem+json");
    assertThat(map(responses, "400", "content", "application/problem+json", "schema"))
        .containsEntry("$ref", ref("Problem"));
  }

  @Test
  @Tag("AC-1.3.1")
  void ac_1_3_1_statusReturnsStatusResponse() throws IOException {
    Map<String, Object> responses = map(contract(), "paths", "/api/status", "get", "responses");
    assertThat(responses.keySet()).containsExactly("200");
    assertThat(map(responses, "200", "content", "application/json", "schema"))
        .containsEntry("$ref", ref("StatusResponse"));
  }

  @Test
  @Tag("AC-1.3.1")
  void ac_1_3_1_lookupResponseHasEanAndRows() throws IOException {
    Map<String, Object> response = schema("LookupResponse");
    assertThat(map(response, "properties").keySet()).containsExactlyInAnyOrder("ean", "rows");
    assertThat(list(response.get("required"))).containsExactlyInAnyOrder("ean", "rows");
    assertThat(property("LookupResponse", "ean")).containsEntry("type", "string");
    Map<String, Object> rows = property("LookupResponse", "rows");
    assertThat(rows).containsEntry("type", "array");
    assertThat(map(rows, "items")).containsEntry("$ref", ref("ResultRow"));
  }

  @Test
  @Tag("AC-1.3.1")
  void ac_1_3_1_resultRowHasExactlyTheAd5Fields() throws IOException {
    Map<String, Object> row = schema("ResultRow");
    assertThat(map(row, "properties").keySet())
        .containsExactlyInAnyOrder(
            "retailerId",
            "retailerName",
            "status",
            "priceMinor",
            "availability",
            "url",
            "fetchedAt",
            "lowest");
    assertThat(list(row.get("required")))
        .containsExactlyInAnyOrder("retailerId", "retailerName", "status", "lowest");
    assertThat(property("ResultRow", "retailerId")).containsEntry("type", "string");
    assertThat(property("ResultRow", "retailerName")).containsEntry("type", "string");
    assertThat(list(property("ResultRow", "status").get("enum")))
        .containsExactly("FOUND", "NOT_FOUND", "UNAVAILABLE", "DISABLED");
    assertThat(property("ResultRow", "priceMinor"))
        .containsEntry("type", "integer")
        .containsEntry("format", "int64");
    assertThat(list(property("ResultRow", "availability").get("enum")))
        .containsExactly("IN_STOCK", "OUT_OF_STOCK", "UNKNOWN");
    assertThat(property("ResultRow", "url"))
        .containsEntry("type", "string")
        .containsEntry("format", "uri");
    assertThat(property("ResultRow", "fetchedAt"))
        .containsEntry("type", "string")
        .containsEntry("format", "date-time");
    assertThat(property("ResultRow", "lowest")).containsEntry("type", "boolean");
  }

  @Test
  @Tag("AC-1.3.1")
  void ac_1_3_1_retailerStatusHasFlagsAndOptionalNotBeforeButNoPrices() throws IOException {
    Map<String, Object> response = schema("StatusResponse");
    assertThat(list(response.get("required"))).containsExactly("retailers");
    assertThat(map(property("StatusResponse", "retailers"), "items"))
        .containsEntry("$ref", ref("RetailerStatus"));

    Map<String, Object> status = schema("RetailerStatus");
    assertThat(map(status, "properties").keySet())
        .containsExactlyInAnyOrder("retailerId", "retailerName", "enabled", "blocked", "notBefore");
    assertThat(list(status.get("required")))
        .containsExactlyInAnyOrder("retailerId", "retailerName", "enabled", "blocked");
    assertThat(property("RetailerStatus", "enabled")).containsEntry("type", "boolean");
    assertThat(property("RetailerStatus", "blocked")).containsEntry("type", "boolean");
    assertThat(property("RetailerStatus", "notBefore"))
        .containsEntry("type", "string")
        .containsEntry("format", "date-time");
  }

  @Test
  @Tag("AC-1.3.1")
  void ac_1_3_1_problemIsRfc9457WithRequiredEanErrorCode() throws IOException {
    Map<String, Object> problem = schema("Problem");
    assertThat(map(problem, "properties").keySet())
        .containsExactlyInAnyOrder("type", "title", "status", "detail", "instance", "code");
    assertThat(list(problem.get("required"))).containsExactly("code");
    assertThat(property("Problem", "code")).containsEntry("$ref", ref("EanErrorCode"));
    assertThat(property("Problem", "status")).containsEntry("type", "integer");
    assertThat(list(schema("EanErrorCode").get("enum")))
        .containsExactly("EAN_EMPTY", "EAN_NON_DIGIT", "EAN_LENGTH", "EAN_CHECK_DIGIT");
  }

  @Test
  @Tag("AC-1.3.2")
  void ac_1_3_2_springInterfacesAndModelsAreGeneratedFromTheContract() {
    for (String name :
        List.of(
            "LookupApi",
            "StatusApi",
            "model.LookupResponse",
            "model.ResultRow",
            "model.StatusResponse",
            "model.RetailerStatus",
            "model.Problem",
            "model.EanErrorCode")) {
      assertThatCode(() -> Class.forName(GENERATED + "." + name))
          .as(name)
          .doesNotThrowAnyException();
    }
  }

  @Test
  @Tag("AC-1.3.2")
  void ac_1_3_2_generatedApisAreInterfaces() throws ClassNotFoundException {
    assertThat(Class.forName(GENERATED + ".LookupApi").isInterface()).isTrue();
    assertThat(Class.forName(GENERATED + ".StatusApi").isInterface()).isTrue();
  }

  @Test
  @Tag("AC-1.3.2")
  void ac_1_3_2_noSourceUnderSrcDeclaresTheGeneratedPackage() throws IOException {
    try (Stream<Path> files = Files.walk(Path.of("src"))) {
      List<Path> offenders =
          files
              .filter(p -> p.toString().endsWith(".java"))
              .filter(ApiContractTest::declaresGeneratedPackage)
              .toList();
      assertThat(offenders).isEmpty();
    }
  }

  private static boolean declaresGeneratedPackage(Path file) {
    try {
      return GENERATED_PACKAGE.matcher(Files.readString(file)).find();
    } catch (IOException e) {
      throw new IllegalStateException(file.toString(), e);
    }
  }
}
