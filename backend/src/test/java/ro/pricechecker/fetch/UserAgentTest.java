package ro.pricechecker.fetch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

@Tag("AC-2.4.5")
class UserAgentTest {

  @Test
  void ac_2_4_5_userAgentNamesTheAppVersionAndContactUrl() {
    assertThat(UserAgent.build("0.0.1", "https://contact.example/about").value())
        .isEqualTo("price-checker/0.0.1 (+https://contact.example/about)");
    assertThat(UserAgent.build("0.0.1", "http://contact.example").value())
        .isEqualTo("price-checker/0.0.1 (+http://contact.example)");
  }

  @ParameterizedTest
  @NullAndEmptySource
  @ValueSource(
      strings = {
        "   ",
        "contact.example/about",
        "/about",
        "ftp://contact.example/about",
        "mailto:owner@contact.example",
        "https://",
        "https://contact.example/a b",
        "https://contact.example/é"
      })
  void ac_2_4_5_missingOrInvalidContactUrlRefusesToStart(String contactUrl) {
    assertThatThrownBy(() -> UserAgent.build("0.0.1", contactUrl))
        .isInstanceOf(IllegalStateException.class);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "https://contact.example/bot",
        "https://contact.example/RoBoT",
        "https://crawler.example/",
        "https://contact.example/SPIDER",
        "https://contact.example/?x=Crawl"
      })
  void ac_2_4_5_forbiddenWordInAnyCaseRefusesToStart(String contactUrl) {
    assertThatThrownBy(() -> UserAgent.build("0.0.1", contactUrl))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("S-6");
  }

  @Test
  void ac_2_4_5_forbiddenWordInTheVersionRefusesToStart() {
    assertThatThrownBy(() -> UserAgent.build("1-BOT", "https://contact.example/"))
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(() -> UserAgent.build(" ", "https://contact.example/"))
        .isInstanceOf(IllegalStateException.class);
  }
}
