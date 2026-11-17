package ro.pricechecker.extract;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Step 1 of C-6: reads the first JSON-LD {@code Product} on a page. Parses the string only. */
public final class StructuredData {

  private static final JsonMapper JSON =
      JsonMapper.builder().enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS).build();

  private static final List<String> GTIN_KEYS =
      List.of("gtin13", "gtin", "gtin14", "gtin12", "gtin8");

  private StructuredData() {}

  /**
   * Returns the facts of the first {@code Product} in the page's JSON-LD blocks, or empty when no
   * block holds one. Malformed blocks are skipped.
   */
  public static Optional<ExtractedFacts> fromJsonLd(String html) {
    if (html == null) {
      return Optional.empty();
    }
    for (Element script : Jsoup.parse(html).getElementsByTag("script")) {
      if (!isJsonLd(script.attr("type"))) {
        continue;
      }
      JsonNode root;
      try {
        root = JSON.readTree(script.data());
      } catch (JacksonException e) {
        continue;
      }
      Optional<JsonNode> product = findProduct(root);
      if (product.isPresent()) {
        return Optional.of(toFacts(product.get()));
      }
    }
    return Optional.empty();
  }

  /** {@code application/ld+json}, compared in two parts so no selector-like literal is needed. */
  private static boolean isJsonLd(String type) {
    String t = type.split(";", 2)[0].strip().toLowerCase(Locale.ROOT);
    return t.equals(String.join("+", "application/ld", "json"));
  }

  private static Optional<JsonNode> findProduct(JsonNode node) {
    if (node == null) {
      return Optional.empty();
    }
    if (node.isArray()) {
      for (JsonNode item : node) {
        Optional<JsonNode> found = findProduct(item);
        if (found.isPresent()) {
          return found;
        }
      }
      return Optional.empty();
    }
    if (!node.isObject()) {
      return Optional.empty();
    }
    if (hasType(node, "Product")) {
      return Optional.of(node);
    }
    return findProduct(node.get("@graph"));
  }

  private static boolean hasType(JsonNode node, String wanted) {
    JsonNode type = node.get("@type");
    if (type == null) {
      return false;
    }
    if (type.isArray()) {
      for (JsonNode t : type) {
        if (termIs(t, wanted)) {
          return true;
        }
      }
      return false;
    }
    return termIs(type, wanted);
  }

  /** True when the text after the last {@code /} or {@code :} equals {@code wanted}. */
  private static boolean termIs(JsonNode node, String wanted) {
    if (!node.isString()) {
      return false;
    }
    String text = node.stringValue().strip();
    return text.substring(Math.max(text.lastIndexOf('/'), text.lastIndexOf(':')) + 1)
        .equals(wanted);
  }

  private static ExtractedFacts toFacts(JsonNode product) {
    JsonNode offer = firstOffer(product.get("offers"));
    Long price = null;
    String currency = null;
    ExtractedFacts.Availability availability = ExtractedFacts.Availability.UNKNOWN;
    if (offer != null) {
      JsonNode amount = offer.get("price");
      if (amount == null || amount.isNull()) {
        amount = offer.get("lowPrice");
      }
      price = priceOf(amount);
      currency = textOf(offer.get("priceCurrency"));
      availability = SchemaAvailability.of(textOf(offer.get("availability")));
    }
    return new ExtractedFacts(price, currency, availability, gtinOf(product));
  }

  private static JsonNode firstOffer(JsonNode offers) {
    if (offers == null) {
      return null;
    }
    if (offers.isArray()) {
      return offers.isEmpty() ? null : firstOffer(offers.get(0));
    }
    return offers.isObject() ? offers : null;
  }

  private static Long priceOf(JsonNode amount) {
    if (amount == null) {
      return null;
    }
    if (amount.isNumber()) {
      return Prices.toMinor(amount.decimalValue());
    }
    return amount.isString() ? Prices.toMinor(amount.stringValue()) : null;
  }

  private static String gtinOf(JsonNode product) {
    for (String key : GTIN_KEYS) {
      String gtin = new ExtractedFacts(null, null, null, textOf(product.get(key))).gtin();
      if (gtin != null) {
        return gtin;
      }
    }
    return null;
  }

  private static String textOf(JsonNode node) {
    if (node == null) {
      return null;
    }
    if (node.isString()) {
      return node.stringValue();
    }
    return node.isIntegralNumber() ? node.bigIntegerValue().toString() : null;
  }
}
