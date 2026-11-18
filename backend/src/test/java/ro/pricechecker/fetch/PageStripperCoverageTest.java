package ro.pricechecker.fetch;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.TextNode;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Edge cases of story 2.2 that {@link PageStripperTest} leaves open (T-1): idempotence, JSON-LD
 * shapes, description variants, whitespace and microdata price facts. Offline only (T-3).
 */
class PageStripperCoverageTest {

  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final String LD_JSON = "application/ld" + '+' + "json";
  private static final String LONG = "x".repeat(1000);

  private static String fullPage() throws IOException {
    try (InputStream in =
        PageStripperCoverageTest.class.getResourceAsStream("/fetch/full-page.html")) {
      assertThat(in).as("fixture fetch/full-page.html").isNotNull();
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }
  }

  private static String script(String type, String json) {
    return "<script type=\"" + type + "\">" + json + "</script>";
  }

  private static List<JsonNode> ldJson(String html) {
    List<JsonNode> found = new ArrayList<>();
    for (Element script : Jsoup.parse(html).getElementsByTag("script")) {
      found.add(JSON.readTree(script.data()));
    }
    return found;
  }

  @Test
  @Tag("AC-2.2.1")
  void ac_2_2_1_strippingTwiceGivesTheSameOutput() throws IOException {
    String once = PageStripper.strip(fullPage());

    assertThat(PageStripper.strip(once)).isEqualTo(once);
  }

  @Test
  @Tag("AC-2.2.1")
  void ac_2_2_1_mimeTypeIsMatchedCaseInsensitively() {
    String html =
        "<html><head>"
            + script("Application/LD" + '+' + "JSON", "{\"@type\":\"Product\",\"sku\":\"1\"}")
            + "</head></html>";

    List<JsonNode> kept = ldJson(PageStripper.strip(html));

    assertThat(kept).hasSize(1);
    assertThat(kept.get(0).path("sku").asString()).isEqualTo("1");
  }

  @Test
  @Tag("AC-2.2.1")
  void ac_2_2_1_productJsonInOtherScriptTypesIsRemoved() {
    String product = "{\"@type\":\"Product\",\"sku\":\"1\"}";
    String html =
        "<html><head>"
            + script("application/json", product)
            + script("text/javascript", product)
            + "<script>"
            + product
            + "</script></head></html>";

    assertThat(Jsoup.parse(PageStripper.strip(html)).getElementsByTag("script")).isEmpty();
  }

  @Test
  @Tag("AC-2.2.1")
  void ac_2_2_1_emptyOrNonProductJsonLdIsRemoved() {
    String html =
        "<html><head>"
            + script(LD_JSON, "")
            + script(LD_JSON, "null")
            + script(LD_JSON, "[]")
            + script(LD_JSON, "{\"@type\":\"ProductGroupLike\",\"name\":\"Product\"}")
            + "</head></html>";

    assertThat(Jsoup.parse(PageStripper.strip(html)).getElementsByTag("script")).isEmpty();
  }

  @Test
  @Tag("AC-2.2.1")
  void ac_2_2_1_topLevelArrayWithProductIsKeptAndDescriptionsCut() {
    String json =
        "[{\"@type\":\"WebSite\"},{\"@type\":\"Product\",\"description\":\""
            + LONG
            + "\",\"offers\":{\"price\":\"5\",\"description\":\""
            + LONG
            + "\"}}]";
    String html = "<html><head>" + script(LD_JSON, json) + "</head></html>";

    List<JsonNode> kept = ldJson(PageStripper.strip(html));

    assertThat(kept).hasSize(1);
    JsonNode product = kept.get(0).path(1);
    assertThat(product.path("description").asString()).isEqualTo("x".repeat(200));
    assertThat(product.path("offers").path("description").asString()).isEqualTo("x".repeat(200));
    assertThat(product.path("offers").path("price").asString()).isEqualTo("5");
  }

  @Test
  @Tag("AC-2.2.1")
  void ac_2_2_1_onlyDescriptionValuesAreCutInJsonLd() {
    String longName = "n".repeat(300);
    String json =
        "{\"@type\":\"Product\",\"name\":\""
            + longName
            + "\",\"description\":{\"@value\":\"short\"},\"offers\":{\"price\":\"7.00\"}}";
    String html = "<html><head>" + script(LD_JSON, json) + "</head></html>";

    JsonNode kept = ldJson(PageStripper.strip(html)).get(0);

    assertThat(kept).isEqualTo(JSON.readTree(json));
  }

  @Test
  @Tag("AC-2.2.1")
  void ac_2_2_1_itempropAndTwitterDescriptionMetasAreCut() {
    String html =
        "<html><head><meta itemprop=\"description\" content=\""
            + LONG
            + "\"><meta name=\"twitter:description\" content=\""
            + LONG
            + "\"><meta name=\"keywords\" content=\""
            + LONG
            + "\"></head></html>";

    Document stripped = Jsoup.parse(PageStripper.strip(html));

    List<String> contents = new ArrayList<>();
    for (Element meta : stripped.getElementsByTag("meta")) {
      contents.add(meta.attr("content"));
    }
    assertThat(contents).containsExactly("x".repeat(200), "x".repeat(200), LONG);
  }

  @Test
  @Tag("AC-2.2.1")
  void ac_2_2_1_longTextIsCutAndKeepsSurroundingSpaces() {
    String html = "<html><body><p>a<b>  " + LONG + "\n</b>z</p></body></html>";

    Document stripped = Jsoup.parse(PageStripper.strip(html));

    List<TextNode> texts = stripped.getElementsByTag("b").first().textNodes();
    assertThat(texts).hasSize(1);
    assertThat(texts.get(0).getWholeText()).isEqualTo(" " + "x".repeat(200) + " ");
    assertThat(stripped.getElementsByTag("p").first().text())
        .isEqualTo("a " + "x".repeat(200) + " z");
  }

  @Test
  @Tag("AC-2.2.1")
  void ac_2_2_1_mediaElementsAreRemovedAndSrcsetDroppedEverywhere() {
    String html =
        "<html><head><link rel=\"STYLESHEET preload\" href=\"https://example.test/a.css\">"
            + "<link rel=\"icon\" href=\"https://example.test/f.ico\"></head><body>"
            + "<video><source src=\"https://example.test/v.mp4\" srcset=\"x 1x\" sizes=\"1px\">"
            + "</video><a href=\"https://example.test/\" srcset=\"y 2x\">link</a>"
            + "<!-- one --><div><!-- two --></div></body></html>";

    Document stripped = Jsoup.parse(PageStripper.strip(html));

    assertThat(stripped.getElementsByTag("link")).hasSize(1);
    assertThat(stripped.getElementsByTag("link").first().attr("rel")).isEqualTo("icon");
    for (Element element : stripped.getAllElements()) {
      assertThat(element.hasAttr("srcset")).as("srcset on %s", element.tagName()).isFalse();
      assertThat(element.hasAttr("sizes")).as("sizes on %s", element.tagName()).isFalse();
    }
    assertThat(stripped.outerHtml()).doesNotContain("<!--");
    assertThat(stripped.getElementsByTag("a").text()).isEqualTo("link");
  }

  @Test
  @Tag("AC-2.2.2")
  void ac_2_2_2_microdataPriceCurrencyAndAvailabilityAreUnchanged() {
    String html =
        "<html><body><div itemscope itemtype=\"https://schema.org/Product\">"
            + "<div itemprop=\"offers\" itemscope itemtype=\"https://schema.org/Offer\">"
            + "<span itemprop=\"price\" content=\"1299.99\">1.299,99 lei</span>"
            + "<meta itemprop=\"priceCurrency\" content=\"RON\">"
            + "<link itemprop=\"availability\" href=\"https://schema.org/InStock\">"
            + "</div></div></body></html>";

    Document original = Jsoup.parse(html);
    Document stripped = Jsoup.parse(PageStripper.strip(html));

    for (String prop : List.of("price", "priceCurrency", "availability")) {
      Element before = original.getElementsByAttributeValue("itemprop", prop).first();
      Element after = stripped.getElementsByAttributeValue("itemprop", prop).first();
      assertThat(after).as(prop).isNotNull();
      assertThat(after.attributes()).as(prop).isEqualTo(before.attributes());
      assertThat(after.text()).as(prop).isEqualTo(before.text());
    }
  }

  @Test
  @Tag("AC-2.2.2")
  void ac_2_2_2_visiblePriceTextInFullPageIsUnchanged() throws IOException {
    String original = fullPage();

    Document before = Jsoup.parse(original);
    Document after = Jsoup.parse(PageStripper.strip(original));

    assertThat(after.getElementsByClass("price").text())
        .isEqualTo(before.getElementsByClass("price").text())
        .isEqualTo("1.299,99 lei");
  }
}
