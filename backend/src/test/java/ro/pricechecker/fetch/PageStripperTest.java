package ro.pricechecker.fetch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Comment;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import ro.pricechecker.extract.ExtractedFacts;
import ro.pricechecker.extract.MetaTags;
import ro.pricechecker.extract.StructuredData;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class PageStripperTest {

  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final String LD_JSON = "application/ld" + '+' + "json";
  private static final String LONG = "x".repeat(1000);

  private static String fullPage() throws IOException {
    try (InputStream in = PageStripperTest.class.getResourceAsStream("/fetch/full-page.html")) {
      assertThat(in).as("fixture fetch/full-page.html").isNotNull();
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }
  }

  private static List<Element> ldJsonScripts(Document doc) {
    List<Element> found = new ArrayList<>();
    for (Element script : doc.getElementsByTag("script")) {
      if (LD_JSON.equalsIgnoreCase(script.attr("type"))) {
        found.add(script);
      }
    }
    return found;
  }

  private static List<Node> comments(Node root) {
    List<Node> found = new ArrayList<>();
    root.forEachNode(node -> {
      if (node instanceof Comment) {
        found.add(node);
      }
    });
    return found;
  }

  private static String metaContent(Document doc, String key) {
    for (Element meta : doc.getElementsByTag("meta")) {
      if (key.equals(meta.attr("name")) || key.equals(meta.attr("property"))) {
        return meta.attr("content");
      }
    }
    return null;
  }

  @Test
  @Tag("AC-2.2.1")
  void ac_2_2_1_fullPageKeepsOnlyJsonLdProductAndCutsDescriptions() throws IOException {
    Document stripped = Jsoup.parse(PageStripper.strip(fullPage()));

    assertThat(stripped.getElementsByTag("script")).hasSize(1);
    Element kept = stripped.getElementsByTag("script").first();
    assertThat(kept.attr("type")).isEqualTo(LD_JSON);
    assertThat(JSON.readTree(kept.data()).path("@type").asString()).isEqualTo("Product");

    for (String tag : List.of("style", "img", "picture", "source", "svg", "iframe", "noscript")) {
      assertThat(stripped.getElementsByTag(tag)).as(tag).isEmpty();
    }
    for (Element link : stripped.getElementsByTag("link")) {
      assertThat(link.attr("rel")).isNotEqualToIgnoringCase("stylesheet");
    }
    assertThat(stripped.getElementsByTag("link")).as("canonical link kept").hasSize(1);
    assertThat(comments(stripped)).isEmpty();
    for (Element element : stripped.getAllElements()) {
      assertThat(element.hasAttr("srcset")).as("srcset on %s", element.tagName()).isFalse();
      assertThat(element.hasAttr("sizes")).as("sizes on %s", element.tagName()).isFalse();
    }

    Element description = stripped.getElementsByTag("p").first();
    assertThat(description.text()).hasSize(200);
    assertThat(metaContent(stripped, "description")).hasSize(200);
    assertThat(metaContent(stripped, "og:description")).hasSize(200);
    assertThat(JSON.readTree(kept.data()).path("description").asString()).hasSize(200);
    assertThat(stripped.head()).isNotNull();
    assertThat(stripped.body().getElementsByTag("h1").text()).isEqualTo("Synthetic product");
  }

  @Test
  @Tag("AC-2.2.2")
  void ac_2_2_2_strippedPageYieldsTheSameProductFacts() throws IOException {
    String original = fullPage();
    Optional<ExtractedFacts> jsonLdBefore = StructuredData.fromJsonLd(original);
    Optional<ExtractedFacts> metaBefore = MetaTags.fromMetaTags(original);

    String stripped = PageStripper.strip(original);

    assertThat(jsonLdBefore)
        .contains(
            new ExtractedFacts(
                129_999L, "RON", ExtractedFacts.Availability.IN_STOCK, "5901234123457"));
    assertThat(metaBefore).isPresent();
    assertThat(metaBefore.get().priceMinor()).isEqualTo(129_999L);
    assertThat(metaBefore.get().currency()).isEqualTo("RON");
    assertThat(metaBefore.get().availability()).isEqualTo(ExtractedFacts.Availability.IN_STOCK);
    assertThat(StructuredData.fromJsonLd(stripped)).isEqualTo(jsonLdBefore);
    assertThat(MetaTags.fromMetaTags(stripped)).isEqualTo(metaBefore);
  }

  @Test
  @Tag("AC-2.2.1")
  void ac_2_2_1_jsonLdDescriptionWithEscapedQuotesStaysValidJson() {
    String description = "say \\\"hi\\\" ".repeat(100);
    String html =
        "<html><head><script type=\""
            + LD_JSON
            + "\">{\"@type\":\"Product\",\"description\":\""
            + description
            + "\",\"offers\":{\"price\":\"10.50\",\"priceCurrency\":\"RON\","
            + "\"availability\":\"https://schema.org/InStock\"}}</script></head></html>";

    Document stripped = Jsoup.parse(PageStripper.strip(html));

    List<Element> scripts = ldJsonScripts(stripped);
    assertThat(scripts).hasSize(1);
    JsonNode node = JSON.readTree(scripts.get(0).data());
    assertThat(node.path("description").asString()).hasSizeLessThanOrEqualTo(200);
    assertThat(node.path("offers"))
        .isEqualTo(JSON.readTree(Jsoup.parse(html).selectFirst("script").data()).path("offers"));
  }

  @Test
  @Tag("AC-2.2.1")
  void ac_2_2_1_jsonLdScriptCloseSequenceStaysInsideScript() {
    String html =
        "<html><head><script type=\""
            + LD_JSON
            + "\">{\"@type\":\"Product\",\"name\":\"a<\\/script><script>alert(1)<\\/script>\"}"
            + "</script></head><body></body></html>";

    Document stripped = Jsoup.parse(PageStripper.strip(html));

    assertThat(stripped.getElementsByTag("script")).hasSize(1);
    JsonNode node = JSON.readTree(ldJsonScripts(stripped).get(0).data());
    assertThat(node.path("name").asString()).isEqualTo("a</script><script>alert(1)</script>");
  }

  @Test
  @Tag("AC-2.2.1")
  void ac_2_2_1_jsonLdWithMimeParametersAndSchemaUrlTypeIsKept() {
    String html =
        "<html><head><script type=\""
            + LD_JSON
            + "; charset=utf-8\">{\"@type\":\"https://schema.org/Product\",\"sku\":\"1\"}"
            + "</script></head><body></body></html>";

    assertThat(Jsoup.parse(PageStripper.strip(html)).getElementsByTag("script")).hasSize(1);
  }

  @Test
  @Tag("AC-2.2.1")
  void ac_2_2_1_productInTypeListOrGraphIsKept() {
    String list = "{\"@type\":[\"Product\",\"Thing\"],\"offers\":{\"price\":\"1\"}}";
    String graph =
        "{\"@graph\":[{\"@type\":\"WebPage\"},{\"@type\":\"Product\",\"description\":\""
            + LONG
            + "\"}]}";
    String other = "{\"@type\":\"Organization\"}";
    String html =
        "<html><head>"
            + script(list)
            + script(graph)
            + script(other)
            + script("{not json")
            + "</head></html>";

    List<Element> scripts = ldJsonScripts(Jsoup.parse(PageStripper.strip(html)));

    assertThat(scripts).hasSize(2);
    assertThat(JSON.readTree(scripts.get(0).data())).isEqualTo(JSON.readTree(list));
    JsonNode graphNode = JSON.readTree(scripts.get(1).data());
    assertThat(graphNode.path("@graph").path(1).path("description").asString()).hasSize(200);
  }

  private static String script(String json) {
    return "<script type=\"" + LD_JSON + "\">" + json + "</script>";
  }

  @Test
  @Tag("AC-2.2.1")
  void ac_2_2_1_metaDescriptionIsCutAndPriceMetasAreUnchanged() {
    String html =
        "<html><head><meta name=\"description\" content=\""
            + LONG
            + "\"><meta property=\"og:price:amount\" content=\""
            + "9".repeat(250)
            + "\"><meta property=\"product:price:currency\" content=\"RON\"></head></html>";

    Document stripped = Jsoup.parse(PageStripper.strip(html));

    assertThat(metaContent(stripped, "description")).isEqualTo("x".repeat(200));
    assertThat(metaContent(stripped, "og:price:amount")).isEqualTo("9".repeat(250));
    assertThat(metaContent(stripped, "product:price:currency")).isEqualTo("RON");
  }

  @Test
  @Tag("AC-2.2.1")
  void ac_2_2_1_shortTextIsUnchanged() {
    String text = "y".repeat(200);
    String html = "<html><body><p>" + text + "</p><p>  short  </p></body></html>";

    Document stripped = Jsoup.parse(PageStripper.strip(html));

    assertThat(stripped.getElementsByTag("p").get(0).text()).isEqualTo(text);
    List<String> raw = new ArrayList<>();
    for (TextNode node : stripped.getElementsByTag("p").get(1).textNodes()) {
      raw.add(node.getWholeText());
    }
    assertThat(raw).containsExactly("  short  ");
  }

  @Test
  @Tag("AC-2.2.1")
  void ac_2_2_1_cutDoesNotSplitASurrogatePair() {
    String emoji = "😀";
    String text = "a".repeat(199) + emoji + "b".repeat(50);

    Document stripped = Jsoup.parse(PageStripper.strip("<p>" + text + "</p>"));

    String cut = stripped.getElementsByTag("p").text();
    assertThat(cut).isEqualTo("a".repeat(199));
  }

  @Test
  @Tag("AC-2.2.1")
  void ac_2_2_1_emptyInputGivesEmptyDocumentAndNullFailsFast() {
    Document stripped = Jsoup.parse(PageStripper.strip(""));

    assertThat(stripped.head().childNodeSize()).isZero();
    assertThat(stripped.body().childNodeSize()).isZero();
    assertThatNullPointerException().isThrownBy(() -> PageStripper.strip(null));
  }
}
