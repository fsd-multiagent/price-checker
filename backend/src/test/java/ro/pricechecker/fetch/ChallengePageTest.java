package ro.pricechecker.fetch;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Story 2.6: generic challenge markers only (AD-6 outcome table). */
@Tag("AC-2.6.1")
class ChallengePageTest {

  @ParameterizedTest
  @ValueSource(
      strings = {
        "<script>window._cf_chl_opt = {cvId: '3'};</script>",
        "<div id=\"cf-browser-verification\">Checking your browser</div>",
        "<script src=\"/_Incapsula_Resource?SWJIYLWA=1\"></script>",
        "<div id=\"px-captcha\"></div>"
      })
  void ac_2_6_1_eachMarkerIsRecognized(String body) {
    assertThat(ChallengePage.detect("<html><body>" + body + "</body></html>")).isTrue();
  }

  @Test
  void ac_2_6_1_ordinaryPagesAreNotChallenges() {
    assertThat(ChallengePage.detect("<html><body><h1>Laptop</h1>1.299,99 lei</body></html>"))
        .isFalse();
    assertThat(ChallengePage.detect("")).isFalse();
    assertThat(ChallengePage.detect(null)).isFalse();
  }
}
