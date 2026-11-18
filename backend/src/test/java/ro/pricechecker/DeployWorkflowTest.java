package ro.pricechecker;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.yaml.snakeyaml.Yaml;

/**
 * Story 3.3: the deploy workflow is inspected as YAML and text; the VM deploy script runs under
 * bash with a fake {@code docker} on PATH and every directory under {@code @TempDir} (T-1).
 */
class DeployWorkflowTest {

  private static final Path WORKFLOW = Path.of("../.github/workflows/deploy.yml");
  private static final Path DEPLOY_SCRIPT = Path.of("../deploy/scripts/deploy");
  private static final Path INSTALL_SCRIPT = Path.of("../deploy/scripts/install");
  private static final Path COMPOSE = Path.of("../deploy/compose.yaml");
  private static final Path CADDYFILE = Path.of("../deploy/Caddyfile");
  private static final String SHA = "0123456789abcdef0123456789abcdef01234567";
  private static final String SITE = "pc-prod.westeurope.cloudapp.azure.example";
  private static final String USER = "registry-user";
  // Built from parts so no token-like literal sits in the repository (secret-scan).
  private static final String TOKEN = "fake" + "_" + "registry" + "_" + "token" + "_42";
  private static final Pattern PINNED = Pattern.compile("^[^@\\s]+@[0-9a-f]{40}$");

  private static final String FAKE_DOCKER =
      """
      #!/bin/bash
      log="$FAKE_DOCKER_LOG"
      printf 'ARGV' >> "$log"
      for a in "$@"; do printf ' %s' "$a" >> "$log"; done
      printf '\\n' >> "$log"
      if [ -d "$DOCKER_CONFIG" ]; then state=exists; else state=missing; fi
      printf 'CFG %s %s\\n' "$DOCKER_CONFIG" "$state" >> "$log"
      case "$1" in
        login) cat > "$log.stdin" ;;
        inspect)
          n=0
          if [ "${FAKE_FAIL:-}" = crash ]; then
            n=$(( $(cat "$log.restarts" 2>/dev/null || echo 0) + 1 ))
            echo "$n" > "$log.restarts"
          fi
          if [ "${FAKE_FAIL:-}" = proxycrash ]; then
            n=$(( $(cat "$log.restarts" 2>/dev/null || echo 0) + 1 ))
            echo "$n" > "$log.restarts"
            printf 'true 0\\ntrue 0\\ntrue %s\\n' "$n"
          else
            printf 'true %s\\ntrue 0\\ntrue 0\\n' "$n"
          fi ;;
        compose)
          for a in "$@"; do
            if [ "$a" = ps ]; then
              case "$*" in
                *" ps -a -q proxy")
                  case "${FAKE_FAIL:-}" in
                    noproxy) ;;
                    twoproxies) printf 'proxy-a\\nproxy-b\\n' ;;
                    *) echo proxy-container-id ;;
                  esac ;;
              esac
              exit 0
            fi
            if [ "$a" = pull ] && [ "${FAKE_FAIL:-}" = pull ]; then exit 1; fi
            if [ "$a" = up ] && [ "${FAKE_FAIL:-}" = up ]; then exit 1; fi
            if [ "$a" = up ]; then
              printf 'ENV %s %s %s %s\\n' "$IMAGE_TAG" "$SITE_ADDRESS" "$BACKEND_UID" "$BACKEND_GID" >> "$log"
            fi
          done ;;
      esac
      exit 0
      """;

  @TempDir Path tmp;
  private Path root;
  private Path appDir;
  private Path log;
  private Path bin;

  private record Run(int exit, String stderr) {}

  @SuppressWarnings("unchecked")
  private static Map<String, Object> map(Object o) {
    return (Map<String, Object>) o;
  }

  private static Map<String, Object> workflow() throws IOException {
    return map(new Yaml().load(Files.readString(WORKFLOW)));
  }

  private static Map<String, Object> job(String name) throws IOException {
    return map(map(workflow().get("jobs")).get(name));
  }

  private static List<Map<String, Object>> allSteps() throws IOException {
    List<Map<String, Object>> steps = new ArrayList<>();
    for (Object job : map(workflow().get("jobs")).values()) {
      for (Object step : (List<?>) map(job).get("steps")) {
        steps.add(map(step));
      }
    }
    return steps;
  }

  private static List<String> runs() throws IOException {
    return allSteps().stream().map(s -> (String) s.get("run")).filter(r -> r != null).toList();
  }

  @BeforeEach
  void setUp() throws IOException {
    root = Files.createDirectories(tmp.resolve("opt"));
    Files.createDirectories(root.resolve("config"));
    Files.createDirectories(root.resolve("state"));
    appDir = Files.createDirectories(root.resolve("app"));
    bin = Files.createDirectories(tmp.resolve("bin"));
    Path docker = bin.resolve("docker");
    Files.writeString(docker, FAKE_DOCKER);
    Files.setPosixFilePermissions(docker, PosixFilePermissions.fromString("rwxr-xr-x"));
    log = tmp.resolve("docker.log");
  }

  private byte[] bundle() throws IOException, InterruptedException {
    Path src = Files.createDirectories(tmp.resolve("bundle-src"));
    Files.copy(COMPOSE, src.resolve("compose.yaml"));
    Files.copy(CADDYFILE, src.resolve("Caddyfile"));
    Files.writeString(src.resolve("extra"), "not extracted");
    Path tar = tmp.resolve("bundle.tar");
    ProcessBuilder pb =
        new ProcessBuilder(
                "tar",
                "-c",
                "-f",
                tar.toString(),
                "-C",
                src.toString(),
                "compose.yaml",
                "Caddyfile",
                "extra")
            .redirectErrorStream(true);
    pb.environment().put("COPYFILE_DISABLE", "1");
    Process p = pb.start();
    p.getInputStream().readAllBytes();
    assertThat(p.waitFor()).isZero();
    return Files.readAllBytes(tar);
  }

  private Run deploy(Map<String, String> env, byte[] stdin, String... args)
      throws IOException, InterruptedException {
    List<String> cmd = new ArrayList<>(List.of("bash", DEPLOY_SCRIPT.toString()));
    cmd.addAll(List.of(args));
    Path err = tmp.resolve("stderr.txt");
    ProcessBuilder pb =
        new ProcessBuilder(cmd)
            .redirectOutput(tmp.resolve("stdout.txt").toFile())
            .redirectError(err.toFile());
    Map<String, String> e = pb.environment();
    e.put("PATH", bin + ":" + System.getenv("PATH"));
    e.put("PC_ROOT", root.toString());
    e.put("PC_APP_DIR", appDir.toString());
    e.put("FAKE_DOCKER_LOG", log.toString());
    e.put("PC_SETTLE_SECONDS", "0");
    e.put("TMPDIR", Files.createDirectories(tmp.resolve("tmpdir")).toString());
    e.putAll(env);
    Process p = pb.start();
    try (OutputStream in = p.getOutputStream()) {
      in.write(stdin);
    } catch (IOException ignored) {
      // The script may exit before it reads stdin (argument and state checks).
    }
    assertThat(p.waitFor(60, TimeUnit.SECONDS)).as("deploy script finished").isTrue();
    return new Run(p.exitValue(), Files.readString(err));
  }

  private byte[] goodStdin() throws IOException, InterruptedException {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    out.write((USER + "\n" + TOKEN + "\n").getBytes(StandardCharsets.UTF_8));
    out.write(bundle());
    return out.toByteArray();
  }

  private List<String> dockerCalls() throws IOException {
    if (!Files.exists(log)) {
      return List.of();
    }
    return Files.readAllLines(log).stream()
        .filter(l -> l.startsWith("ARGV "))
        .map(l -> l.substring(5))
        .toList();
  }

  private static int indexOf(List<String> calls, String needle) {
    for (int i = 0; i < calls.size(); i++) {
      if (calls.get(i).contains(needle)) {
        return i;
      }
    }
    return -1;
  }

  private static Set<String> listing(Path dir) throws IOException {
    try (Stream<Path> s = Files.list(dir)) {
      return s.map(p -> p.getFileName().toString()).collect(TreeSet::new, Set::add, Set::addAll);
    }
  }

  private static String currentId(String flag) throws IOException, InterruptedException {
    Process p = new ProcessBuilder("id", flag).start();
    String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
    p.waitFor();
    return out;
  }

  @Test
  @Tag("AC-3.3.1")
  void ac_3_3_1_happyDeployLogsInOnStdinPullsLogsOutThenStarts() throws Exception {
    Run run = deploy(Map.of(), goodStdin(), SHA, SITE);

    assertThat(run.exit()).as(run.stderr()).isZero();
    List<String> calls = dockerCalls();
    int login = indexOf(calls, "login ghcr.io");
    int pull = indexOf(calls, " pull");
    int logout = indexOf(calls, "logout ghcr.io");
    int up = indexOf(calls, " up -d");
    assertThat(login).isGreaterThanOrEqualTo(0);
    assertThat(calls.get(login)).contains("--password-stdin", "--username " + USER);
    assertThat(pull).isGreaterThan(login);
    assertThat(logout).isGreaterThan(pull);
    assertThat(up).isGreaterThan(logout);
    assertThat(calls.get(calls.size() - 1)).as("logout again on exit").isEqualTo("logout ghcr.io");
    assertThat(calls).noneMatch(c -> c.contains(TOKEN));
    assertThat(Files.readString(Path.of(log + ".stdin")).strip()).isEqualTo(TOKEN);

    String env =
        Files.readAllLines(log).stream()
            .filter(l -> l.startsWith("ENV "))
            .findFirst()
            .orElseThrow();
    assertThat(env)
        .isEqualTo("ENV %s %s %s %s".formatted(SHA, SITE, currentId("-u"), currentId("-g")));

    List<String> configs =
        Files.readAllLines(log).stream().filter(l -> l.startsWith("CFG ")).toList();
    String dockerConfig = configs.get(0).split(" ")[1];
    assertThat(dockerConfig).startsWith(tmp.resolve("tmpdir").toString());
    assertThat(configs.get(0)).endsWith(" exists");
    assertThat(Path.of(dockerConfig)).doesNotExist();
    assertThat(listing(tmp.resolve("tmpdir"))).as("temp files removed").isEmpty();

    assertThat(listing(appDir)).containsExactly("Caddyfile", "compose.yaml");
    assertThat(Files.readString(appDir.resolve("compose.yaml")))
        .isEqualTo(Files.readString(COMPOSE));
  }

  @Test
  @Tag("AC-3.3.1")
  void ac_3_3_1_failedPullStillLogsOutAndNeverStarts() throws Exception {
    Files.writeString(appDir.resolve("compose.yaml"), "previous compose\n");
    Files.writeString(appDir.resolve("Caddyfile"), "previous caddy\n");

    Run run = deploy(Map.of("FAKE_FAIL", "pull"), goodStdin(), SHA, SITE);

    assertThat(run.exit()).isNotZero();
    List<String> calls = dockerCalls();
    assertThat(calls.get(indexOf(calls, " pull")))
        .as("pulls with the bundle, not the app directory")
        .contains("-f " + tmp.resolve("tmpdir"))
        .doesNotContain(appDir.toString());
    assertThat(indexOf(calls, "logout ghcr.io")).isGreaterThan(indexOf(calls, " pull"));
    assertThat(indexOf(calls, " up")).isEqualTo(-1);
    assertThat(Files.readString(appDir.resolve("compose.yaml"))).isEqualTo("previous compose\n");
    assertThat(Files.readString(appDir.resolve("Caddyfile"))).isEqualTo("previous caddy\n");
    assertThat(listing(tmp.resolve("tmpdir"))).isEmpty();
  }

  @Test
  @Tag("AC-3.3.1")
  void ac_3_3_1_upWaitsForTheContainersAndRecreatesTheProxyOnlyForANewCaddyfile() throws Exception {
    Run first = deploy(Map.of(), goodStdin(), SHA, SITE);
    assertThat(first.exit()).as(first.stderr()).isZero();
    List<String> calls = dockerCalls();
    assertThat(calls.get(indexOf(calls, " up -d"))).contains("--wait", "--wait-timeout");
    assertThat(indexOf(calls, "--force-recreate")).as("new Caddyfile").isGreaterThan(0);
    assertThat(calls.get(indexOf(calls, "--force-recreate"))).endsWith(" proxy");

    Files.delete(log);
    Files.deleteIfExists(tmp.resolve("bundle.tar"));
    deleteTree(tmp.resolve("bundle-src"));
    Run second = deploy(Map.of(), goodStdin(), SHA, SITE);
    assertThat(second.exit()).as(second.stderr()).isZero();
    assertThat(indexOf(dockerCalls(), "--force-recreate")).as("same Caddyfile").isEqualTo(-1);
  }

  @Test
  @Tag("AC-3.3.1")
  void ac_3_3_1_containerThatFailsToStartOrRestartsFailsTheDeploy() throws Exception {
    Run up = deploy(Map.of("FAKE_FAIL", "up"), goodStdin(), SHA, SITE);
    assertThat(up.exit()).isNotZero();
    assertThat(up.stderr()).doesNotContain("is running");
    assertThat(appDir.resolve(".caddy-pending")).as("proxy recreate still owed").exists();

    Files.delete(log);
    Files.deleteIfExists(tmp.resolve("bundle.tar"));
    deleteTree(tmp.resolve("bundle-src"));
    Run retry = deploy(Map.of(), goodStdin(), SHA, SITE);
    assertThat(retry.exit()).as(retry.stderr()).isZero();
    assertThat(indexOf(dockerCalls(), "--force-recreate"))
        .as("same Caddyfile, owed")
        .isGreaterThan(0);
    assertThat(appDir.resolve(".caddy-pending")).doesNotExist();

    Files.delete(log);
    Files.deleteIfExists(tmp.resolve("bundle.tar"));
    deleteTree(tmp.resolve("bundle-src"));
    Run crash = deploy(Map.of("FAKE_FAIL", "crash"), goodStdin(), SHA, SITE);
    assertThat(crash.exit()).isEqualTo(1);
    assertThat(crash.stderr()).contains("restarted or stopped after start");
    assertThat(Files.readString(tmp.resolve("stdout.txt"))).doesNotContain("is running");
    assertThat(listing(tmp.resolve("tmpdir"))).isEmpty();
    assertThat(dockerCalls().stream().filter(c -> c.startsWith("inspect")))
        .as("the proxy is part of the settle check")
        .isNotEmpty()
        .allMatch(c -> c.endsWith(" proxy-container-id"));

    Files.delete(log);
    Files.deleteIfExists(tmp.resolve("bundle.tar"));
    deleteTree(tmp.resolve("bundle-src"));
    Run proxyCrash = deploy(Map.of("FAKE_FAIL", "proxycrash"), goodStdin(), SHA, SITE);
    assertThat(proxyCrash.exit()).as("only the proxy restarts").isEqualTo(1);
    assertThat(proxyCrash.stderr()).contains("restarted or stopped after start");
    assertThat(dockerCalls()).anyMatch(c -> c.endsWith(" ps -a -q proxy"));

    for (String mode : new String[] {"noproxy", "twoproxies"}) {
      Files.delete(log);
      Files.deleteIfExists(tmp.resolve("bundle.tar"));
      deleteTree(tmp.resolve("bundle-src"));
      Run bad = deploy(Map.of("FAKE_FAIL", mode), goodStdin(), SHA, SITE);
      assertThat(bad.exit()).as(mode).isEqualTo(1);
      assertThat(bad.stderr()).as(mode).contains("expected one proxy container");
      assertThat(Files.readString(tmp.resolve("stdout.txt"))).doesNotContain("is running");
      assertThat(listing(tmp.resolve("tmpdir"))).isEmpty();
      assertThat(indexOf(dockerCalls(), "inspect")).as(mode).isEqualTo(-1);
    }
  }

  @Test
  @Tag("AC-3.3.1")
  void ac_3_3_1_badArgumentsExit64WithoutDocker() throws Exception {
    for (String[] args :
        List.of(
            new String[] {"latest", SITE},
            new String[] {SHA.toUpperCase(), SITE},
            new String[] {SHA, "not a host"},
            new String[] {SHA, "-oProxyCommand=x"},
            new String[] {SHA},
            new String[] {SHA, SITE, "extra"})) {
      Run run = deploy(Map.of(), goodStdin(), args);
      assertThat(run.exit()).as(String.join(" ", args)).isEqualTo(64);
      assertThat(run.stderr()).contains("usage");
      Files.deleteIfExists(tmp.resolve("bundle.tar"));
      deleteTree(tmp.resolve("bundle-src"));
    }
    assertThat(dockerCalls()).isEmpty();
  }

  @Test
  @Tag("AC-3.3.1")
  void ac_3_3_1_missingStateDirectoryStopsBeforeDockerAndNamesInstall() throws Exception {
    Files.delete(root.resolve("state"));

    Run run = deploy(Map.of(), goodStdin(), SHA, SITE);

    assertThat(run.exit()).isNotZero();
    assertThat(run.stderr()).contains("install");
    assertThat(dockerCalls()).isEmpty();
    assertThat(root.resolve("state")).doesNotExist();
  }

  @Test
  @Tag("AC-3.3.2")
  void ac_3_3_2_operatorFilesStayByteIdenticalWhetherTheDeployPassesOrFails() throws Exception {
    byte[] runtime = "retailers:\n  a.example:\n    fetching: stopped\n".getBytes();
    byte[] blocks = "{\"a.example\":{\"notBefore\":\"2030-01-01T00:00:00Z\"}}".getBytes();
    Path runtimeFile = root.resolve("config").resolve("runtime" + ".yaml");
    Path blocksFile = root.resolve("state").resolve("blocks" + ".json");
    Files.write(runtimeFile, runtime);
    Files.write(blocksFile, blocks);

    for (Map<String, String> env : List.of(Map.<String, String>of(), Map.of("FAKE_FAIL", "pull"))) {
      deploy(env, goodStdin(), SHA, SITE);
      Files.deleteIfExists(tmp.resolve("bundle.tar"));
      deleteTree(tmp.resolve("bundle-src"));

      assertThat(Files.readAllBytes(runtimeFile)).isEqualTo(runtime);
      assertThat(Files.readAllBytes(blocksFile)).isEqualTo(blocks);
      assertThat(listing(root.resolve("config"))).containsExactly("runtime.yaml");
      assertThat(listing(root.resolve("state"))).containsExactly("blocks.json");
    }
  }

  @Test
  @Tag("AC-3.3.2")
  void ac_3_3_2_scriptsNeverNameTheOperatorFilesOrTrace() throws IOException {
    String deploy = Files.readString(DEPLOY_SCRIPT);
    assertThat(deploy).doesNotContain("runtime.yaml", "blocks.json", "/opt/price-checker/config");
    for (Path script : List.of(DEPLOY_SCRIPT, INSTALL_SCRIPT)) {
      String text = Files.readString(script);
      assertThat(text).as("%s", script).doesNotContain("set -x", "latest");
      assertThat(text.lines().findFirst().orElseThrow()).isEqualTo("#!/bin/bash");
      assertThat(text).contains("set -euo pipefail");
    }
  }

  @Test
  @Tag("AC-3.3.1")
  @SuppressWarnings("unchecked")
  void ac_3_3_1_workflowPushesShaTaggedImagesAndDeploysWithTheRightPermissions()
      throws IOException {
    Map<String, Object> wf = workflow();
    // SnakeYAML reads the YAML 1.1 key `on` as Boolean true.
    Map<String, Object> on = map(wf.containsKey("on") ? wf.get("on") : wf.get(true));
    assertThat(on.keySet()).containsExactly("push");
    assertThat((List<Object>) map(on.get("push")).get("branches")).containsExactly("main");

    Map<String, Object> images = job("images");
    assertThat(map(images.get("permissions")))
        .containsEntry("packages", "write")
        .containsEntry("contents", "read")
        .hasSize(2);
    Map<String, Object> deploy = job("deploy");
    assertThat(deploy.get("needs")).isEqualTo("images");
    assertThat(map(deploy.get("permissions")))
        .containsEntry("packages", "read")
        .containsEntry("id-token", "write")
        .containsEntry("contents", "read")
        .hasSize(3);
    assertThat(map(deploy.get("concurrency")))
        .containsEntry("group", "prod-ssh")
        .containsEntry("cancel-in-progress", false);

    // The image names are the ones compose pulls, tagged with the full SHA.
    String prefix = (String) map(wf.get("env")).get("IMAGE_PREFIX");
    String compose = Files.readString(COMPOSE);
    assertThat(compose).contains(prefix + "-backend:${IMAGE_TAG", prefix + "-web:${IMAGE_TAG");
    String text = Files.readString(WORKFLOW);
    assertThat(text)
        .contains("\"$IMAGE_PREFIX-backend:$GITHUB_SHA\"", "\"$IMAGE_PREFIX-web:$GITHUB_SHA\"")
        .doesNotContain(":latest");

    String ssh =
        runs().stream()
            .filter(r -> r.contains(" deploy \"$GITHUB_SHA\""))
            .findFirst()
            .orElseThrow();
    assertThat(ssh)
        .contains("printf '%s\\n%s\\n' \"$GITHUB_ACTOR\" \"$GITHUB_TOKEN\"")
        .contains("tar -c -f - -C deploy compose.yaml Caddyfile");
  }

  @Test
  @Tag("AC-3.3.1")
  void ac_3_3_1_jitRuleIsAlwaysClosedAndTheKeyAlwaysRemoved() throws IOException {
    List<Map<String, Object>> steps = allSteps();
    Map<String, Object> close =
        steps.stream()
            .filter(s -> String.valueOf(s.get("run")).contains("az network nsg rule delete"))
            .filter(s -> String.valueOf(s.get("run")).contains("jit-ssh-$GITHUB_RUN_ID"))
            .filter(s -> !String.valueOf(s.get("run")).contains("rule create"))
            .findFirst()
            .orElseThrow();
    assertThat((String) close.get("if")).startsWith("always()");
    Map<String, Object> removeKey =
        steps.stream()
            .filter(s -> String.valueOf(s.get("run")).contains("rm -f \"$RUNNER_TEMP/deploy_key\""))
            .findFirst()
            .orElseThrow();
    assertThat(removeKey.get("if")).isEqualTo("always()");
    String open =
        runs().stream()
            .filter(r -> r.contains("az network nsg rule create"))
            .findFirst()
            .orElseThrow();
    assertThat(open)
        .contains("-n \"jit-ssh-$GITHUB_RUN_ID\"", "--priority 100", "\"$ip/32\"")
        .contains("--destination-port-ranges 22");
    assertThat(runs()).anyMatch(r -> r.contains("az login") && r.contains("--federated-token"));
  }

  @Test
  @Tag("AC-3.3.3")
  void ac_3_3_3_secretsReachRunStepsOnlyThroughEnvAndNeverDockerArgv() throws IOException {
    for (String run : runs()) {
      assertThat(run).as("run step interpolates no expression").doesNotContain("${{");
      assertThat(run).doesNotContain("set -x");
      for (String line : run.lines().toList()) {
        if (line.contains("$GITHUB_TOKEN")) {
          assertThat(line.strip()).as("token line").startsWith("printf ");
        }
        if (line.contains("docker login")) {
          assertThat(line).contains("--password-stdin").doesNotContain("--password ");
        }
      }
    }
    String text = Files.readString(WORKFLOW);
    assertThat(text).doesNotContain("github.token", "set -x", "pull_request");
    Matcher secret = Pattern.compile("secrets\\.([A-Za-z0-9_]+)").matcher(text);
    Set<String> names = new TreeSet<>();
    while (secret.find()) {
      names.add(secret.group(1));
    }
    assertThat(names).containsExactly("DEPLOY_SSH_KEY", "GITHUB_TOKEN");

    Map<String, String> uses = new HashMap<>();
    for (Map<String, Object> step : allSteps()) {
      if (step.get("uses") != null) {
        String u = (String) step.get("uses");
        uses.put(u.substring(0, u.indexOf('@')), u);
      }
    }
    assertThat(uses.keySet()).containsExactly("actions/checkout");
    assertThat(uses.values()).allMatch(u -> PINNED.matcher(u).matches());
    assertThat(uses.get("actions/checkout"))
        .isEqualTo(
            Pattern.compile("uses: (actions/checkout@[0-9a-f]{40})")
                .matcher(Files.readString(Path.of("../.github/workflows/ci.yml")))
                .results()
                .findFirst()
                .orElseThrow()
                .group(1));
  }

  // --- QA additions (Eufrosina (QA), story 3.3 round 1) ---

  private byte[] tarOf(Path src, String... members) throws IOException, InterruptedException {
    Path tar = tmp.resolve("custom.tar");
    List<String> cmd = new ArrayList<>(List.of("tar", "-c", "-f", tar.toString()));
    cmd.addAll(List.of("-C", src.toString()));
    cmd.addAll(List.of(members));
    ProcessBuilder pb = new ProcessBuilder(cmd).redirectErrorStream(true);
    pb.environment().put("COPYFILE_DISABLE", "1");
    Process p = pb.start();
    p.getInputStream().readAllBytes();
    assertThat(p.waitFor()).isZero();
    return Files.readAllBytes(tar);
  }

  private static byte[] withCredentials(byte[] tar) throws IOException {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    out.write((USER + "\n" + TOKEN + "\n").getBytes(StandardCharsets.UTF_8));
    out.write(tar);
    return out.toByteArray();
  }

  @Test
  @Tag("AC-3.3.1")
  void ac_3_3_1_missingTokenLineExits65BeforeAnyDockerCall() throws Exception {
    Run run = deploy(Map.of(), (USER + "\n").getBytes(StandardCharsets.UTF_8), SHA, SITE);

    assertThat(run.exit()).isEqualTo(65);
    assertThat(run.stderr()).contains("registry user and token");
    assertThat(dockerCalls()).isEmpty();
    assertThat(listing(appDir)).isEmpty();
  }

  @Test
  @Tag("AC-3.3.1")
  void ac_3_3_1_bundleWithoutCaddyfileStopsBeforeLoginAndKeepsTheRunningFiles() throws Exception {
    Files.writeString(appDir.resolve("compose.yaml"), "previous compose\n");
    Files.writeString(appDir.resolve("Caddyfile"), "previous caddy\n");
    Path src = Files.createDirectories(tmp.resolve("partial-src"));
    Files.copy(COMPOSE, src.resolve("compose.yaml"));

    Run run = deploy(Map.of(), withCredentials(tarOf(src, "compose.yaml")), SHA, SITE);

    assertThat(run.exit()).isNotZero();
    assertThat(indexOf(dockerCalls(), "login")).isEqualTo(-1);
    assertThat(indexOf(dockerCalls(), " up")).isEqualTo(-1);
    assertThat(Files.readString(appDir.resolve("Caddyfile"))).isEqualTo("previous caddy\n");
    assertThat(listing(tmp.resolve("tmpdir"))).as("temp files removed").isEmpty();
  }

  @Test
  @Tag("AC-3.3.1")
  void ac_3_3_1_runnerPushLogsOutOfGhcrAndUsesAThrowawayDockerConfig() throws IOException {
    String push = runs().stream().filter(r -> r.contains("docker push")).findFirst().orElseThrow();
    List<String> lines = push.lines().map(String::strip).toList();
    int config = indexOf(lines, "export DOCKER_CONFIG=\"$RUNNER_TEMP/");
    int trap = indexOf(lines, "trap ");
    int login = indexOf(lines, "docker login ghcr.io");
    assertThat(config).isGreaterThanOrEqualTo(0);
    assertThat(trap).isGreaterThan(config);
    assertThat(lines.get(trap))
        .contains("docker logout ghcr.io", "rm -rf \"$DOCKER_CONFIG\"", "EXIT");
    assertThat(login).isGreaterThan(trap);
    assertThat(indexOf(lines, "docker push \"$IMAGE_PREFIX-backend:$GITHUB_SHA\""))
        .isGreaterThan(login);
    assertThat(indexOf(lines, "docker push \"$IMAGE_PREFIX-web:$GITHUB_SHA\""))
        .isGreaterThan(login);
  }

  @Test
  @Tag("AC-3.3.2")
  void ac_3_3_2_symlinkedBundleMemberCannotOverwriteAnOperatorFile() throws Exception {
    byte[] runtime = "retailers:\n  a.example:\n    fetching: stopped\n".getBytes();
    Path runtimeFile = root.resolve("config").resolve("runtime" + ".yaml");
    Files.write(runtimeFile, runtime);
    Path src = Files.createDirectories(tmp.resolve("evil-src"));
    Files.copy(COMPOSE, src.resolve("compose.yaml"));
    Files.createSymbolicLink(src.resolve("Caddyfile"), runtimeFile);

    Run run = deploy(Map.of(), withCredentials(tarOf(src, "compose.yaml", "Caddyfile")), SHA, SITE);

    assertThat(run.exit()).isEqualTo(65);
    assertThat(run.stderr()).contains("no regular file Caddyfile");
    assertThat(Files.readAllBytes(runtimeFile)).isEqualTo(runtime);
    assertThat(listing(root.resolve("config"))).containsExactly("runtime.yaml");
    assertThat(indexOf(dockerCalls(), " up")).isEqualTo(-1);
  }

  @Test
  @Tag("AC-3.3.2")
  void ac_3_3_2_deployNeverCreatesTheConfigDirectoryOrAnyOperatorFile() throws Exception {
    Files.delete(root.resolve("config"));

    Run run = deploy(Map.of(), goodStdin(), SHA, SITE);

    assertThat(run.exit()).as(run.stderr()).isZero();
    assertThat(root.resolve("config")).doesNotExist();
    assertThat(listing(root.resolve("state"))).isEmpty();
    assertThat(listing(root)).containsExactly("app", "state");
  }

  @Test
  @Tag("AC-3.3.2")
  void ac_3_3_2_installCreatesDirectoriesOnlyAndNeverNamesOperatorFiles() throws IOException {
    String install = Files.readString(INSTALL_SCRIPT);
    assertThat(install).doesNotContain("runtime.yaml", "blocks.json", "rm ", "> ", ">>", "touch ");
    String dirs =
        install.lines().filter(l -> l.contains("\"$PC_ROOT/config\"")).findFirst().orElseThrow();
    assertThat(dirs.strip()).startsWith("install -d ").contains("\"$PC_ROOT/state\"");
  }

  @Test
  @Tag("AC-3.3.3")
  void ac_3_3_3_tokenNeverReachesScriptOutputOrStaysOnDisk() throws Exception {
    for (Map<String, String> env : List.of(Map.<String, String>of(), Map.of("FAKE_FAIL", "pull"))) {
      Run run = deploy(env, goodStdin(), SHA, SITE);
      Files.deleteIfExists(tmp.resolve("bundle.tar"));
      deleteTree(tmp.resolve("bundle-src"));

      String stdout = Files.readString(tmp.resolve("stdout.txt"));
      assertThat(run.stderr()).as("stderr %s", env).doesNotContain(TOKEN);
      assertThat(stdout).as("stdout %s", env).doesNotContain(TOKEN);
      assertThat(listing(tmp.resolve("tmpdir"))).as("temp files removed %s", env).isEmpty();
      try (Stream<Path> files = Files.walk(root)) {
        for (Path f : files.filter(Files::isRegularFile).toList()) {
          assertThat(Files.readString(f)).as("%s", f).doesNotContain(TOKEN);
        }
      }
    }
  }

  @Test
  @Tag("AC-3.3.3")
  void ac_3_3_3_workflowHoldsNoCredentialLiteral() throws IOException {
    String text = Files.readString(WORKFLOW);
    assertThat(text)
        .doesNotContainPattern("gh[pousr]_[A-Za-z0-9]{20,}")
        .doesNotContain("BEGIN OPENSSH PRIVATE KEY", "BEGIN RSA PRIVATE KEY")
        .doesNotContainPattern("(?i)password\\s*[:=]\\s*\\S")
        .doesNotContainPattern("client[-_]secret");
  }

  private static String runOf(String stepName) throws IOException {
    return allSteps().stream()
        .filter(s -> String.valueOf(s.get("name")).startsWith(stepName))
        .map(s -> (String) s.get("run"))
        .findFirst()
        .orElseThrow(() -> new AssertionError("no step " + stepName));
  }

  @Test
  @Tag("AC-3.3.3")
  void ac_3_3_3_oidcTokenIsCheckedAndMaskedBeforeAzLoginUnderPipefail() throws IOException {
    String run = runOf("Azure login over OIDC");
    assertThat(run.strip()).startsWith("set -euo pipefail");
    int check = run.indexOf("[ -z \"$oidc\" ]");
    int mask = run.indexOf("::add-mask::$oidc");
    int login = run.indexOf("az login");
    assertThat(check).as("empty token check").isGreaterThan(0);
    assertThat(mask).as("token masked").isGreaterThan(check);
    assertThat(login).isGreaterThan(mask);
  }

  @Test
  @Tag("AC-3.3.1")
  void ac_3_3_1_sshStepsRunUnderPipefailWithIpFallbackAndVisibleProbeError() throws IOException {
    String open = runOf("Open the just-in-time SSH rule");
    assertThat(open.strip()).startsWith("set -euo pipefail");
    assertThat(open).as("second IP echo service").containsPattern("curl [^\\n]*\\|\\| curl ");
    assertThat(open).contains("exit 1");

    String deploy = runOf("Deploy over the forced command");
    assertThat(deploy.strip()).startsWith("set -euo pipefail");
    int lastAttempt = deploy.indexOf("\"$attempt\" -eq 9");
    assertThat(lastAttempt).isGreaterThan(0);
    assertThat(deploy.substring(lastAttempt))
        .as("ssh stderr shown on the last probe attempt")
        .contains("cat \"$probe_err\" >&2");
  }

  @Test
  @Tag("AC-3.3.1")
  void ac_3_3_1_everyJobThatOpensAJitSshRuleSharesTheProdSshGroup() throws IOException {
    // The stale-rule sweep deletes every jit-ssh-* rule, which is safe only while all
    // such jobs run one at a time (ADR 0018, ADR 0026).
    int found = 0;
    try (Stream<Path> files = Files.list(WORKFLOW.getParent())) {
      for (Path file : files.filter(f -> f.toString().endsWith(".yml")).toList()) {
        Map<String, Object> jobs = map(map(new Yaml().load(Files.readString(file))).get("jobs"));
        for (Map.Entry<String, Object> job : jobs.entrySet()) {
          boolean opensRule =
              ((List<?>) map(job.getValue()).get("steps"))
                  .stream()
                      .map(step -> String.valueOf(map(step).get("run")))
                      .anyMatch(run -> run.contains("nsg rule create") && run.contains("jit-ssh-"));
          if (opensRule) {
            found++;
            Object concurrency = map(job.getValue()).get("concurrency");
            assertThat(concurrency).as("%s job %s", file, job.getKey()).isInstanceOf(Map.class);
            assertThat(map(concurrency).get("group"))
                .as("%s job %s", file, job.getKey())
                .isEqualTo("prod-ssh");
            assertThat(map(concurrency).get("cancel-in-progress")).isEqualTo(false);
          }
        }
      }
    }
    assertThat(found).isGreaterThanOrEqualTo(1);
  }

  private static void deleteTree(Path dir) throws IOException {
    if (!Files.exists(dir)) {
      return;
    }
    try (Stream<Path> s = Files.walk(dir)) {
      for (Path p : s.sorted((a, b) -> b.compareTo(a)).toList()) {
        Files.delete(p);
      }
    }
  }
}
