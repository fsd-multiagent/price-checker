package ro.pricechecker.fetch;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Parser cases for {@link RobotsRules} (RFC 9309 through crawler-commons). */
class RobotsRulesTest {

  private static final String TOKEN = "price-checker";

  private static RobotsRules parsed(String body) {
    return RobotsRules.parse(body, TOKEN).orElseThrow();
  }

  private static boolean allows(RobotsRules rules, String pathAndQuery) {
    return rules.allows(URI.create("https://a.example" + pathAndQuery));
  }

  @Test
  @Tag("AC-2.5.1")
  void ac_2_5_1_wildcardGroupDisallowsItsPrefixOnly() {
    RobotsRules rules = parsed("User-agent: *\nDisallow: /cauta\n");
    assertThat(allows(rules, "/cauta?q=1")).isFalse();
    assertThat(allows(rules, "/cauta")).isFalse();
    assertThat(allows(rules, "/p/1")).isTrue();
  }

  @Test
  @Tag("AC-2.5.1")
  void ac_2_5_1_longCrawlDelayDoesNotDisallowEverything() {
    RobotsRules rules = parsed("User-agent: *\nCrawl-delay: 3600\nDisallow: /cauta\n");
    assertThat(allows(rules, "/p/1")).isTrue();
    assertThat(allows(rules, "/cauta")).isFalse();
  }

  @ParameterizedTest
  @Tag("AC-2.5.1")
  @ValueSource(strings = {"Useragent: *\nDisallow: /cauta\n", "User agent: *\nDisallow: /cauta\n"})
  void ac_2_5_1_userAgentSpellingVariantsAreParseable(String body) {
    RobotsRules rules = parsed(body);
    assertThat(allows(rules, "/cauta")).isFalse();
    assertThat(allows(rules, "/p/1")).isTrue();
  }

  @Test
  @Tag("AC-2.5.1")
  void ac_2_5_1_ourGroupReplacesTheWildcardGroup() {
    RobotsRules rules =
        parsed("User-agent: *\nDisallow: /cauta\n\nUser-agent: Price-Checker\nDisallow: /p/\n");
    assertThat(allows(rules, "/p/1")).isFalse();
    assertThat(allows(rules, "/cauta")).isTrue();
  }

  @Test
  @Tag("AC-2.5.1")
  void ac_2_5_1_otherAgentsGroupDoesNotApply() {
    RobotsRules rules = parsed("User-agent: otheragent\nDisallow: /\n");
    assertThat(allows(rules, "/p/1")).isTrue();
  }

  @Test
  @Tag("AC-2.5.1")
  void ac_2_5_1_longestMatchWinsAndWildcardsMatch() {
    RobotsRules rules =
        parsed("User-agent: *\nDisallow: /p/\nAllow: /p/ok\nDisallow: /*.pdf$\n# comment\n");
    assertThat(allows(rules, "/p/1")).isFalse();
    assertThat(allows(rules, "/p/ok/1")).isTrue();
    assertThat(allows(rules, "/doc.pdf")).isFalse();
    assertThat(allows(rules, "/doc.pdf?x=1")).isTrue();
  }

  @Test
  @Tag("AC-2.5.3")
  void ac_2_5_3_byteOrderMarkIsIgnored() {
    assertThat(allows(parsed("﻿User-agent: *\nDisallow: /\n"), "/p/1")).isFalse();
  }

  @ParameterizedTest
  @Tag("AC-2.5.3")
  @ValueSource(strings = {"", "  \n\r\n", "# only a comment\n"})
  void ac_2_5_3_emptyBodyAllowsAll(String body) {
    assertThat(RobotsRules.parse(body, TOKEN)).contains(RobotsRules.ALLOW_ALL);
  }

  @ParameterizedTest
  @Tag("AC-2.5.3")
  @ValueSource(
      strings = {
        "<!DOCTYPE html><html><body>Not found</body></html>",
        "hello world",
        "User-agent: *\nDisallow: /x\0\n"
      })
  void ac_2_5_3_unparseableBodyIsEmpty(String body) {
    assertThat(RobotsRules.parse(body, TOKEN)).isEmpty();
  }

  @Test
  @Tag("AC-2.5.3")
  void ac_2_5_3_allowAllAndDisallowAll() {
    assertThat(allows(RobotsRules.ALLOW_ALL, "/anything")).isTrue();
    assertThat(allows(RobotsRules.DISALLOW_ALL, "/anything")).isFalse();
    assertThat(allows(RobotsRules.DISALLOW_ALL, "/")).isFalse();
  }
}
