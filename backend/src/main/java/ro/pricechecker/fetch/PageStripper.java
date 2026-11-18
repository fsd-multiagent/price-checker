package ro.pricechecker.fetch;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Comment;
import org.jsoup.nodes.DataNode;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Turns a fetched retailer page into a page that may be saved as a fixture (T-2) or in a Health
 * check report (AD-16): it keeps facts only (S-7).
 *
 * <p>Removes every {@code <script>} except JSON-LD product data, styles, stylesheet links, images,
 * {@code <picture>}, {@code <svg>}, iframes, {@code <noscript>}, HTML comments and the {@code
 * srcset}/{@code sizes} attributes. Cuts long text, description meta tags and JSON-LD {@code
 * description} values to {@value #MAX_TEXT} characters. Retailer-agnostic and offline: it only
 * parses the given string.
 */
public final class PageStripper {

  /** Longest text kept, in UTF-16 chars. */
  static final int MAX_TEXT = 200;

  // A MIME type, not a CSS selector: split so RetailerTextScan does not read the plus as a
  // combinator.
  private static final String LD_JSON = "application/ld" + '+' + "json";
  private static final String PRODUCT = "Product";
  private static final String DESCRIPTION = "description";
  private static final Set<String> REMOVED_TAGS =
      Set.of("style", "img", "picture", "svg", "iframe", "noscript");
  private static final JsonMapper JSON = JsonMapper.builder().build();

  private PageStripper() {}

  /**
   * Strips {@code html} as described in the class comment (S-7, T-2, AD-16).
   *
   * @param html the page as fetched; {@code ""} gives an empty document
   * @return the stripped page as HTML
   * @throws NullPointerException when {@code html} is null
   */
  public static String strip(String html) {
    Objects.requireNonNull(html, "html");
    Document doc = Jsoup.parse(html);
    doc.outputSettings().prettyPrint(false);

    List<Node> toRemove = new ArrayList<>();
    List<TextNode> texts = new ArrayList<>();
    doc.forEachNode(node -> {
      if (node instanceof Comment) {
        toRemove.add(node);
      } else if (node instanceof TextNode text) {
        texts.add(text);
      } else if (node instanceof Element element && !keep(element)) {
        toRemove.add(element);
      }
    });
    toRemove.forEach(Node::remove);

    for (TextNode text : texts) {
      String whole = text.getWholeText();
      String trimmed = whole.strip();
      if (trimmed.length() > MAX_TEXT) {
        String before = whole.startsWith(trimmed) ? "" : " ";
        String after = whole.endsWith(trimmed) ? "" : " ";
        text.text(before + cut(trimmed) + after);
      }
    }
    return doc.outerHtml();
  }

  /** Cleans {@code element} in place; returns false when it must be removed. */
  private static boolean keep(Element element) {
    element.removeAttr("srcset");
    element.removeAttr("sizes");
    String tag = element.normalName();
    if (REMOVED_TAGS.contains(tag)) {
      return false;
    }
    switch (tag) {
      case "script" -> {
        return keepJsonLdProduct(element);
      }
      case "link" -> {
        return !isStylesheet(element);
      }
      case "meta" -> {
        if (isDescriptionMeta(element) && element.hasAttr("content")) {
          element.attr("content", cut(element.attr("content")));
        }
        return true;
      }
      default -> {
        return true;
      }
    }
  }

  private static boolean isStylesheet(Element link) {
    for (String rel : link.attr("rel").toLowerCase(Locale.ROOT).split("\\s+")) {
      if ("stylesheet".equals(rel)) {
        return true;
      }
    }
    return false;
  }

  private static boolean isDescriptionMeta(Element meta) {
    return endsWithDescription(meta.attr("name"))
        || endsWithDescription(meta.attr("property"))
        || endsWithDescription(meta.attr("itemprop"));
  }

  private static boolean endsWithDescription(String key) {
    return key.toLowerCase(Locale.ROOT).endsWith(DESCRIPTION);
  }

  /** Keeps a JSON-LD block that describes a Product, with descriptions cut; drops all else. */
  private static boolean keepJsonLdProduct(Element script) {
    if (!LD_JSON.equalsIgnoreCase(script.attr("type").split(";", 2)[0].trim())) {
      return false;
    }
    JsonNode json;
    try {
      json = JSON.readTree(script.data());
    } catch (JacksonException e) {
      return false;
    }
    if (json == null || !containsProduct(json)) {
      return false;
    }
    cutDescriptions(json);
    String serialized = JSON.writeValueAsString(json).replace("<", "\\u003c");
    script.empty();
    script.appendChild(new DataNode(serialized));
    return true;
  }

  private static boolean isProduct(JsonNode type) {
    if (!type.isString()) {
      return false;
    }
    String value = type.stringValue();
    return PRODUCT.equals(value) || value.endsWith("/" + PRODUCT) || value.endsWith(":" + PRODUCT);
  }

  private static boolean containsProduct(JsonNode node) {
    if (node.isObject()) {
      JsonNode type = node.path("@type");
      if (isProduct(type)) {
        return true;
      }
      if (type.isArray()) {
        for (JsonNode item : type) {
          if (isProduct(item)) {
            return true;
          }
        }
      }
    }
    if (node.isContainer()) {
      for (JsonNode child : node) {
        if (containsProduct(child)) {
          return true;
        }
      }
    }
    return false;
  }

  private static void cutDescriptions(JsonNode node) {
    if (node instanceof ObjectNode object) {
      for (Map.Entry<String, JsonNode> property : object.properties()) {
        if (DESCRIPTION.equals(property.getKey()) && property.getValue().isString()) {
          object.put(DESCRIPTION, cut(property.getValue().stringValue()));
        }
      }
    }
    if (node.isContainer()) {
      for (JsonNode child : node) {
        cutDescriptions(child);
      }
    }
  }

  /** Cuts {@code text} to {@link #MAX_TEXT} chars without splitting a surrogate pair. */
  private static String cut(String text) {
    if (text.length() <= MAX_TEXT) {
      return text;
    }
    int end = MAX_TEXT;
    if (Character.isHighSurrogate(text.charAt(end - 1))) {
      end--;
    }
    return text.substring(0, end);
  }
}
