package ro.pricechecker.arch;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * AC 2.6.4 (AC-11.3, S-6): a Block is never worked around. The main sources hold no proxy, no
 * second user agent and no header-changing code: the only header the application sets is the one
 * fixed {@code User-Agent}, in {@code fetch/JdkTransport.java}, and the only proxy setting is
 * {@code NO_PROXY} there.
 */
@Tag("AC-2.6.4")
class NoRetryWorkaroundTest {

  private static final String TRANSPORT = "ro/pricechecker/fetch/JdkTransport.java";
  private static final String USER_AGENT_HEADER = ".header(\"User-Agent\", userAgent)";

  private static final Pattern COMMENT = Pattern.compile("//[^\\n]*|/\\*.*?\\*/", Pattern.DOTALL);

  /** Proxy types and settings, other than {@code NO_PROXY}. */
  private static final Pattern PROXY =
      Pattern.compile(
          "\\bProxySelector\\b|\\bjava\\.net\\.Proxy\\b|\\bnew\\s+Proxy\\s*\\(|\\bProxy\\.Type\\b"
              + "|\\.proxy\\s*\\((?!\\s*HttpClient\\.Builder\\.NO_PROXY\\s*\\))"
              + "|(?i:\\b(?:https?|socks)\\.?proxy(?:Host|Port)\\b)");

  /** Any header-setting call; reading response headers ({@code headers()}) is fine. */
  private static final Pattern HEADER_CALL =
      Pattern.compile(
          "\\.(?:header|setHeader|addHeader|setRequestProperty)\\s*\\("
              + "|\\.headers\\s*\\((?!\\s*\\))");

  /** A literal user-agent string, or a user agent picked from a list. */
  private static final Pattern OTHER_USER_AGENT =
      Pattern.compile("\"[^\"\\n]*(?:Mozilla/|Chrome/|Safari/|Gecko|curl/|Wget)[^\"\\n]*\"");

  /** Returns, per offending file, which rule it breaks. */
  static Map<String, List<String>> violations(Map<String, String> sourcesByPath) {
    Map<String, List<String>> found = new TreeMap<>();
    sourcesByPath.forEach((path, raw) -> {
      String source = COMMENT.matcher(raw).replaceAll("");
      String unixPath = path.replace('\\', '/');
      List<String> rules = new ArrayList<>();
      if (PROXY.matcher(source).find()) {
        rules.add("proxy");
      }
      if (OTHER_USER_AGENT.matcher(source).find()) {
        rules.add("user agent");
      }
      int headerCalls = count(HEADER_CALL.matcher(source));
      boolean onlyTheFixedUserAgent =
          unixPath.equals(TRANSPORT)
              && headerCalls == 1
              && source.split(Pattern.quote(USER_AGENT_HEADER), -1).length == 2;
      if (headerCalls > 0 && !onlyTheFixedUserAgent) {
        rules.add("header");
      }
      if (!rules.isEmpty()) {
        found.put(unixPath, rules);
      }
    });
    return found;
  }

  private static int count(Matcher matcher) {
    int n = 0;
    while (matcher.find()) {
      n++;
    }
    return n;
  }

  private static Map<String, String> mainSources() throws IOException {
    Path root = Path.of("src/main/java");
    Map<String, String> sources = new TreeMap<>();
    try (Stream<Path> files = Files.walk(root)) {
      for (Path file :
          (Iterable<Path>) files.filter(p -> p.toString().endsWith(".java"))::iterator) {
        sources.put(root.relativize(file).toString(), Files.readString(file));
      }
    }
    return sources;
  }

  @Test
  void ac_2_6_4_mainSourcesHaveNoProxyUserAgentOrHeaderWorkaround() throws IOException {
    Map<String, String> sources = mainSources();
    assertThat(sources).containsKey(TRANSPORT.replace('/', java.io.File.separatorChar));
    assertThat(violations(sources)).isEmpty();
  }

  @Test
  void ac_2_6_4_theOneUserAgentIsSentFromOnePlace() throws IOException {
    long callers =
        mainSources().values().stream()
            .filter(s -> s.contains("transport.get("))
            .mapToLong(s -> s.split(Pattern.quote("transport.get("), -1).length - 1)
            .sum();
    assertThat(callers).as("one Transport call, with the fixed user agent").isEqualTo(1);
    assertThat(
            mainSources().values().stream()
                .anyMatch(s -> s.contains("transport.get(url, userAgent, left)")))
        .isTrue();
  }

  @Test
  void ac_2_6_4_proxyIsReported() {
    assertThat(
            violations(
                Map.of(
                    "ro/pricechecker/fetch/A.java",
                    "class A { Object p = java.net.ProxySelector.getDefault(); }",
                    "ro/pricechecker/fetch/B.java",
                    "class B { void b(java.net.http.HttpClient.Builder x) { x.proxy(sel); } }")))
        .containsOnlyKeys("ro/pricechecker/fetch/A.java", "ro/pricechecker/fetch/B.java");
  }

  @Test
  void ac_2_6_4_otherUserAgentIsReported() {
    assertThat(
            violations(
                Map.of(
                    "ro/pricechecker/fetch/A.java",
                    "class A { String ua = \"Mozilla/5.0 (X11; Linux x86_64)\"; }")))
        .containsEntry("ro/pricechecker/fetch/A.java", List.of("user agent"));
  }

  @Test
  void ac_2_6_4_extraHeaderIsReported() {
    String transport =
        "class T { void t() { b.header(\"User-Agent\", userAgent).header(\"Referer\", r); } }";
    assertThat(
            violations(
                Map.of(
                    TRANSPORT,
                    transport,
                    "ro/pricechecker/fetch/A.java",
                    "class A { void a() { b.setHeader(k, v); } }")))
        .containsOnlyKeys(TRANSPORT, "ro/pricechecker/fetch/A.java");
  }

  @Test
  void ac_2_6_4_commentsDoNotCount() {
    assertThat(
            violations(
                Map.of(
                    "ro/pricechecker/fetch/A.java",
                    "/** no proxy, no .header( change */ class A { // .proxy(x)\n }")))
        .isEmpty();
  }
}
