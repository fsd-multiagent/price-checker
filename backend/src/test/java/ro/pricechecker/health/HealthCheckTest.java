package ro.pricechecker.health;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ro.pricechecker.adapter.AdapterOutcome;
import ro.pricechecker.adapter.Availability;
import ro.pricechecker.adapter.PageFacts;
import ro.pricechecker.adapter.RetailerAdapter;
import ro.pricechecker.adapter.RetailerHttp;
import ro.pricechecker.adapter.UnavailableReason;
import ro.pricechecker.ean.Ean;
import ro.pricechecker.fetch.FetchClient;
import ro.pricechecker.fetch.FetchFailure;
import ro.pricechecker.fetch.PageStripper;
import ro.pricechecker.fetch.Transport;
import ro.pricechecker.fetch.UserAgent;
import ro.pricechecker.lookup.AdapterCatalog;
import ro.pricechecker.lookup.KnownEanLoad;
import ro.pricechecker.lookup.LookupLog;
import ro.pricechecker.registry.BlockStore;
import ro.pricechecker.registry.MutableClock;
import ro.pricechecker.registry.Registry;
import ro.pricechecker.registry.RuntimeSwitch;
import ro.pricechecker.registry.TestRegistries;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * AC 3.5.2, AC 3.5.3: the Health check over stub retailers a.example and b.example (both Approved
 * and enabled, two Known EANs each). Stub transport, injected clock and sleeper only (T-3, T-5).
 */
class HealthCheckTest {

  private static final Instant NOW = Instant.parse("2026-11-18T10:00:00Z");
  private static final String EAN_A = TestRegistries.EAN_A;
  private static final String GOOD = "good";
  private static final String NO_PRICE = "<html><p>" + "x".repeat(300) + "</p>noprice</html>";
  private static final String ALLOW_ALL = "User-agent: *\nAllow: /\n";

  @TempDir Path dir;

  private final MutableClock clock = new MutableClock(NOW);
  private final Map<String, Transport.Response> pages = new HashMap<>();
  private final Map<String, String> robots = new HashMap<>();
  private final List<URI> sent = new ArrayList<>();
  private Path reportDir;
  private Path blocksFile;

  /** Pages: "good" is FOUND with every fact; one containing "noprice" lacks a price. */
  private static RetailerAdapter adapter(String id) {
    return new RetailerAdapter() {
      @Override
      public String retailerId() {
        return id;
      }

      @Override
      public AdapterOutcome fetchAndExtract(Ean ean, URI productUrl, RetailerHttp http) {
        String body;
        try {
          body = http.get(productUrl);
        } catch (FetchFailure f) {
          return f.outcome();
        } catch (IOException e) {
          return new AdapterOutcome.Unavailable(UnavailableReason.HTTP_ERROR);
        }
        if (body.equals(GOOD)) {
          return new AdapterOutcome.Found(
              new PageFacts(1999L, "RON", Availability.OUT_OF_STOCK, productUrl, ean.value()));
        }
        if (body.contains("noprice")) {
          return new AdapterOutcome.Found(
              new PageFacts(null, null, Availability.IN_STOCK, productUrl, null));
        }
        return new AdapterOutcome.Unavailable(UnavailableReason.UNPARSEABLE);
      }
    };
  }

  private HealthCheck healthCheck(String runtimeYaml) throws IOException {
    Path runtimeFile = dir.resolve("runtime.yaml");
    if (runtimeYaml != null) {
      Files.writeString(runtimeFile, runtimeYaml);
    }
    reportDir = dir.resolve("report");
    blocksFile = dir.resolve("blocks.json");
    Registry registry = TestRegistries.twoEnabled(4000);
    RuntimeSwitch runtime = new RuntimeSwitch(runtimeFile, clock);
    LookupLog log = new LookupLog(clock);
    BlockStore blocks = new BlockStore(blocksFile, runtime, clock, log::blocked);
    Transport transport =
        (url, userAgent, timeout) -> {
          sent.add(url);
          if (url.getPath().equals("/robots.txt")) {
            String body = robots.getOrDefault(url.getHost(), ALLOW_ALL);
            return new Transport.Response(200, Optional.empty(), body);
          }
          return pages.getOrDefault(
              url.toString(), new Transport.Response(200, Optional.empty(), GOOD));
        };
    FetchClient fetch =
        new FetchClient(
            registry,
            runtime,
            blocks,
            transport,
            clock,
            clock::advance,
            UserAgent.build("1.0", "https://contact.example/about"),
            new FetchClient.Pace(2, 1));
    AdapterCatalog catalog =
        new AdapterCatalog(
            List.of(adapter("a.example"), adapter("b.example"), adapter("c.example")), registry);
    return new HealthCheck(
        registry,
        runtime,
        blocks,
        fetch,
        new KnownEanLoad(catalog, clock, new LookupLog(clock)),
        log,
        clock,
        reportDir);
  }

  private static String allowing(String... domains) {
    StringBuilder yaml = new StringBuilder("retailers:\n");
    for (String domain : domains) {
      yaml.append("  ").append(domain).append(": {fetching: allowed}\n");
    }
    return yaml.toString();
  }

  private JsonNode report() throws IOException {
    return JsonMapper.builder()
        .build()
        .readTree(Files.readString(reportDir.resolve("report.json")));
  }

  private long pageSends(String host) {
    return sent.stream()
        .filter(u -> u.getHost().equals(host) && !u.getPath().equals("/robots.txt"))
        .count();
  }

  @Test
  @Tag("AC-3.5.2")
  void ac_3_5_2_everyKnownEanPassesAndExitsZero() throws IOException {
    HealthCheck check = healthCheck(allowing("a.example", "b.example"));

    check.run();

    assertThat(check.getExitCode()).isZero();
    assertThat(check.report().checks())
        .hasSize(4)
        .allSatisfy(c -> {
          assertThat(c.outcome()).isEqualTo(CheckResult.Outcome.PASSED);
          assertThat(c.missing()).isEmpty();
          assertThat(c.blocked()).isFalse();
        });
    assertThat(check.report().checks())
        .extracting(CheckResult::retailer)
        .containsExactly("a.example", "a.example", "b.example", "b.example");
    JsonNode report = report();
    assertThat(report.get("passed").asBoolean()).isTrue();
    assertThat(report.get("runtimeConfig").isNull()).isTrue();
    assertThat(report.get("checks")).hasSize(4);
    assertThat(Files.exists(blocksFile)).isFalse();
  }

  @Test
  @Tag("AC-3.5.2")
  void ac_3_5_2_sameEanCheckedTwiceSendsTwice() throws IOException {
    HealthCheck check = healthCheck(allowing("a.example", "b.example"));

    check.run();
    check.run();

    assertThat(pageSends("a.example")).isEqualTo(4);
    assertThat(pageSends("b.example")).isEqualTo(4);
  }

  @Test
  @Tag("AC-3.5.3")
  void ac_3_5_3_missingPriceFailsAndSavesTheStrippedPage() throws IOException {
    pages.put("https://a.example/p/1", new Transport.Response(200, Optional.empty(), NO_PRICE));
    HealthCheck check = healthCheck(allowing("a.example", "b.example"));

    check.run();

    assertThat(check.getExitCode()).isEqualTo(1);
    JsonNode report = report();
    assertThat(report.get("passed").asBoolean()).isFalse();
    JsonNode failed = report.get("checks").get(0);
    assertThat(failed.get("retailer").asString()).isEqualTo("a.example");
    assertThat(failed.get("ean").asString()).isEqualTo(EAN_A);
    assertThat(failed.get("outcome").asString()).isEqualTo("FAILED");
    assertThat(failed.get("status").asString()).isEqualTo("FOUND");
    assertThat(failed.get("missing")).hasSize(1);
    assertThat(failed.get("missing").get(0).asString()).isEqualTo("price");
    assertThat(failed.get("blocked").asBoolean()).isFalse();
    String page = failed.get("page").asString();
    assertThat(page).isEqualTo("pages/a.example/" + EAN_A + ".html");
    assertThat(Files.readString(reportDir.resolve(page))).isEqualTo(PageStripper.strip(NO_PRICE));
    assertThat(report.get("checks").get(1).get("outcome").asString()).isEqualTo("PASSED");
    assertThat(report.get("checks").get(1).has("page")).isFalse();
  }

  @Test
  @Tag("AC-3.5.3")
  void ac_3_5_3_notFoundPageFailsWithEveryFactMissing() throws IOException {
    pages.put("https://a.example/p/1", new Transport.Response(404, Optional.empty(), ""));
    HealthCheck check = healthCheck(allowing("a.example", "b.example"));

    check.run();

    assertThat(check.getExitCode()).isEqualTo(1);
    CheckResult failed = check.report().checks().get(0);
    assertThat(failed.outcome()).isEqualTo(CheckResult.Outcome.FAILED);
    assertThat(failed.status()).isEqualTo("NOT_FOUND");
    assertThat(failed.missing()).containsExactly("price", "availability", "url");
    assertThat(failed.page()).isNull();
  }

  @Test
  @Tag("AC-3.5.3")
  void ac_3_5_3_robotsDisallowBlocksTheRetailerAndLaterEansSendNothing() throws IOException {
    robots.put("a.example", "User-agent: *\nDisallow: /p/\n");
    HealthCheck check = healthCheck(allowing("a.example", "b.example"));

    check.run();

    assertThat(check.getExitCode()).isEqualTo(1);
    List<CheckResult> checks = check.report().checks();
    assertThat(checks.subList(0, 2))
        .allSatisfy(c -> {
          assertThat(c.outcome()).isEqualTo(CheckResult.Outcome.FAILED);
          assertThat(c.blocked()).isTrue();
        });
    assertThat(checks.subList(2, 4))
        .allSatisfy(c -> assertThat(c.outcome()).isEqualTo(CheckResult.Outcome.PASSED));
    assertThat(pageSends("a.example")).isZero();
    assertThat(sent.stream().filter(u -> u.getHost().equals("a.example"))).hasSize(1);
    assertThat(Files.readString(blocksFile)).contains("ROBOTS");
    assertThat(report().get("checks").get(0).get("blocked").asBoolean()).isTrue();
  }

  @Test
  @Tag("AC-3.5.3")
  void ac_3_5_3_stoppedRetailerIsSkippedAndSendsNothing() throws IOException {
    HealthCheck check = healthCheck(allowing("a.example"));

    check.run();

    assertThat(check.getExitCode()).isZero();
    assertThat(check.report().checks().subList(2, 4))
        .allSatisfy(c -> {
          assertThat(c.outcome()).isEqualTo(CheckResult.Outcome.SKIPPED);
          assertThat(c.reason()).isEqualTo("disabled");
        });
    assertThat(sent).noneMatch(u -> u.getHost().equals("b.example"));
  }

  @Test
  @Tag("AC-3.5.3")
  void ac_3_5_3_futureNotBeforeIsSkippedAndSendsNothing() throws IOException {
    String yaml =
        allowing("a.example")
            + "  b.example: {fetching: allowed, notBefore: \"2026-11-18T11:00:00Z\"}\n";
    HealthCheck check = healthCheck(yaml);

    check.run();

    assertThat(check.getExitCode()).isZero();
    assertThat(check.report().checks().subList(2, 4))
        .allSatisfy(c -> {
          assertThat(c.outcome()).isEqualTo(CheckResult.Outcome.SKIPPED);
          assertThat(c.reason()).isEqualTo("notBefore");
        });
    assertThat(sent).noneMatch(u -> u.getHost().equals("b.example"));
  }

  @Test
  @Tag("AC-3.5.3")
  void ac_3_5_3_missingRuntimeConfigFailsTheRun() throws IOException {
    HealthCheck check = healthCheck(null);

    check.run();

    assertThat(check.getExitCode()).isEqualTo(1);
    JsonNode report = report();
    assertThat(report.get("passed").asBoolean()).isFalse();
    assertThat(report.get("runtimeConfig").asString()).isEqualTo("MISSING");
    assertThat(sent).isEmpty();
  }

  @Test
  @Tag("AC-3.5.2")
  void ac_3_5_2_everyCheckSkippedFailsTheRun() throws IOException {
    HealthCheck check = healthCheck("retailers:\n  a.example: {fetching: stopped}\n");

    check.run();

    assertThat(check.report().checks())
        .allSatisfy(c -> assertThat(c.outcome()).isEqualTo(CheckResult.Outcome.SKIPPED));
    assertThat(check.getExitCode()).isEqualTo(1);
    assertThat(report().get("passed").asBoolean()).isFalse();
    assertThat(sent).isEmpty();
  }

  @Test
  @Tag("AC-3.5.3")
  void ac_3_5_3_pageThatCannotBeSavedStillWritesTheReport() throws IOException {
    pages.put("https://a.example/p/1", new Transport.Response(200, Optional.empty(), NO_PRICE));
    HealthCheck check = healthCheck(allowing("a.example", "b.example"));
    Files.createDirectories(reportDir);
    Files.writeString(reportDir.resolve(HealthCheck.PAGES), "not a directory");

    check.run();

    assertThat(check.getExitCode()).isEqualTo(1);
    JsonNode failed = report().get("checks").get(0);
    assertThat(failed.get("outcome").asString()).isEqualTo("FAILED");
    assertThat(failed.get("missing").get(0).asString()).isEqualTo("price");
    assertThat(failed.has("page")).isFalse();
  }

  @Test
  @Tag("AC-3.5.3")
  void ac_3_5_3_exitCodeIsOneBeforeTheRunFinished() throws IOException {
    assertThat(healthCheck(allowing("a.example")).getExitCode()).isEqualTo(1);
  }
}
