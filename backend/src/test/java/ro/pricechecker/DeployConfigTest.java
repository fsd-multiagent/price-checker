package ro.pricechecker;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

/** Story 3.2: the deploy files are inspected as text and YAML; no Docker or Caddy is needed. */
class DeployConfigTest {

  private static final Path COMPOSE = Path.of("../deploy/compose.yaml");
  private static final Path DEPLOY_CADDYFILE = Path.of("../deploy/Caddyfile");
  private static final Path WEB_CADDYFILE = Path.of("../web/Caddyfile");
  private static final List<Path> DOCKERFILES =
      List.of(Path.of("Dockerfile"), Path.of("../web/Dockerfile"));
  private static final Pattern EXACT_TAG =
      Pattern.compile("^[^:@\\s]+(:v?\\d+\\.\\d+\\.\\d+[\\w.-]*|@sha256:[0-9a-f]{64})$");
  private static final List<String> FILTERED =
      List.of(
          "request>remote_ip",
          "request>remote_port",
          "request>client_ip",
          "request>headers",
          "request>uri");

  @SuppressWarnings("unchecked")
  private static Map<String, Object> map(Object o) {
    return (Map<String, Object>) o;
  }

  private static Map<String, Object> services() throws IOException {
    return map(map(new Yaml().load(Files.readString(COMPOSE))).get("services"));
  }

  /** Top-level blocks as {header, body}; the global options block has header "". */
  private static List<String[]> topLevelBlocks(String caddyfile) {
    List<String[]> blocks = new ArrayList<>();
    int depth = 0;
    StringBuilder header = new StringBuilder();
    StringBuilder body = new StringBuilder();
    for (char c : caddyfile.toCharArray()) {
      if (c == '{' && depth == 0 && !header.toString().endsWith("$")) {
        depth = 1;
      } else if (depth > 0) {
        if (c == '{') {
          depth++;
        } else if (c == '}') {
          depth--;
          if (depth == 0) {
            blocks.add(new String[] {header.toString().trim(), body.toString()});
            header.setLength(0);
            body.setLength(0);
            continue;
          }
        }
        body.append(c);
      } else {
        header.append(c);
      }
    }
    return blocks;
  }

  private static String caddyfileWithoutPlaceholderBraces(Path path) throws IOException {
    // "{$SITE_ADDRESS}" is an env placeholder, not a block: protect it from the brace scan.
    return Files.readString(path).replace("{$", "(DOLLAR)").replace("{path}", "(path)");
  }

  @Test
  @Tag("AC-3.2.1")
  void ac_3_2_1_composeHasProxyWebAndOneBackend() throws IOException {
    Map<String, Object> services = services();
    assertThat(services.keySet()).containsExactlyInAnyOrder("proxy", "web", "backend");
    assertThat((String) map(services.get("proxy")).get("image")).startsWith("caddy:2.");
    for (Object service : services.values()) {
      Map<String, ?> s = map(service);
      assertThat(s).doesNotContainKeys("deploy", "scale");
    }
    Map<String, ?> backend = map(services.get("backend"));
    assertThat(backend.get("container_name")).isNotNull();
  }

  @Test
  @Tag("AC-3.2.1")
  void ac_3_2_1_composeImagesAreExactTagsAndOnlyProxyPublishesPorts() throws IOException {
    Map<String, Object> services = services();
    assertThat((String) map(services.get("proxy")).get("image")).matches(EXACT_TAG);
    for (String name : List.of("web", "backend")) {
      Map<String, ?> s = map(services.get(name));
      assertThat((String) s.get("image")).matches("^ghcr\\.io/[^:]+:\\$\\{IMAGE_TAG:\\?[^}]*\\}$");
      assertThat(s).as("%s publishes no ports", name).doesNotContainKey("ports");
    }
  }

  @Test
  @Tag("AC-3.2.1")
  void ac_3_2_1_backendMountsConfigReadOnlyAndStateFromHost() throws IOException {
    Map<String, ?> backend = map(services().get("backend"));
    List<?> volumes = (List<?>) backend.get("volumes");
    List<String> mounts = volumes.stream().map(Object::toString).toList();
    assertThat(mounts)
        .contains(
            "/opt/price-checker/config:/opt/price-checker/config:ro",
            "/opt/price-checker/state:/opt/price-checker/state");
    // The mounts must hold the files the backend actually reads (application.yaml defaults).
    Map<String, Object> app =
        map(
            map(new Yaml().load(Files.readString(Path.of("src/main/resources/application.yaml"))))
                .get("price-checker"));
    String runtimeDir = Path.of((String) app.get("runtime-file")).getParent().toString();
    String blocksDir = Path.of((String) app.get("blocks-file")).getParent().toString();
    assertThat(mounts).contains(runtimeDir + ":" + runtimeDir + ":ro", blocksDir + ":" + blocksDir);
  }

  @Test
  @Tag("AC-3.2.1")
  void ac_3_2_1_webImageGeneratesTheClientLikeTheNpmScript() throws IOException {
    String dockerfile = Files.readString(Path.of("../web/Dockerfile"));
    String script = Files.readString(Path.of("../web/package.json"));
    Matcher props =
        Pattern.compile("(-g \\S+) -o \\S+ (--additional-properties=\\S+?)\"").matcher(script);
    assertThat(props.find()).as("package.json generate script").isTrue();
    Matcher version =
        Pattern.compile("\"version\"\\s*:\\s*\"([^\"]+)\"")
            .matcher(Files.readString(Path.of("../web/openapitools.json")));
    assertThat(version.find()).isTrue();
    assertThat(dockerfile)
        .contains("openapi-generator-cli:v" + version.group(1))
        .contains(props.group(1))
        .contains(props.group(2));
  }

  @Test
  @Tag("AC-3.2.1")
  void ac_3_2_1_everyFromIsAnExactTag() throws IOException {
    for (Path file : DOCKERFILES) {
      List<String> froms =
          Files.readAllLines(file).stream()
              .filter(l -> l.startsWith("FROM "))
              .map(l -> l.split("\\s+")[1])
              .toList();
      assertThat(froms).as("%s has FROM lines", file).isNotEmpty();
      for (String image : froms) {
        assertThat(image).as("%s: %s", file, image).matches(EXACT_TAG).doesNotContain("latest");
      }
    }
  }

  @Test
  @Tag("AC-3.2.1")
  void ac_3_2_1_dockerfilesRunAsNonRoot() throws IOException {
    for (Path file : DOCKERFILES) {
      List<String> users =
          Files.readAllLines(file).stream().filter(l -> l.startsWith("USER ")).toList();
      assertThat(users).as("%s has USER", file).isNotEmpty();
      assertThat(users.get(users.size() - 1))
          .as("%s final USER", file)
          .matches("USER [1-9]\\d*(:\\d+)?");
    }
  }

  @Test
  @Tag("S-10")
  void s10_dockerignoreKeepsSecretsOutOfTheContext() throws IOException {
    String ignore = Files.readString(Path.of("../.dockerignore"));
    assertThat(ignore.lines().map(String::strip).toList())
        .contains(
            "**/.env", "**/.env.*", "**/*.tfstate*", "**/*.tfvars", ".git", "**/node_modules");
  }

  @Test
  @Tag("AC-3.2.2")
  void ac_3_2_2_siteIsHttpsWithRedirectsAndRoutesApiToBackend() throws IOException {
    String text = Files.readString(DEPLOY_CADDYFILE);
    assertThat(text).doesNotContain("http://", "auto_https off", "disable_redirects");
    List<String[]> blocks = topLevelBlocks(caddyfileWithoutPlaceholderBraces(DEPLOY_CADDYFILE));
    String[] site =
        blocks.stream().filter(b -> b[0].contains("SITE_ADDRESS")).findFirst().orElseThrow();
    assertThat(site[1])
        .containsPattern("handle /api/\\*\\s*\\{\\s*reverse_proxy backend:8080")
        .containsPattern("handle\\s*\\{\\s*reverse_proxy web:8080");
  }

  @Test
  @Tag("AC-3.2.3")
  void ac_3_2_3_noSiteBlockLogsAndTheGlobalFilterDeletesIdentifyingFields() throws IOException {
    for (Path file : List.of(DEPLOY_CADDYFILE, WEB_CADDYFILE)) {
      String text = Files.readString(file);
      for (String field : FILTERED) {
        assertThat(text).as("%s", file).containsPattern(Pattern.quote(field) + "\\s+delete");
      }
      List<String[]> blocks = topLevelBlocks(caddyfileWithoutPlaceholderBraces(file));
      assertThat(blocks).as("%s has blocks", file).hasSizeGreaterThanOrEqualTo(2);
      for (String[] block : blocks) {
        Matcher log = Pattern.compile("(?m)^\\s*log\\b").matcher(block[1]);
        if (block[0].isEmpty()) {
          assertThat(log.find()).as("%s global block has log", file).isTrue();
        } else {
          assertThat(log.find()).as("%s site %s has no log", file, block[0]).isFalse();
        }
      }
    }
  }
}
