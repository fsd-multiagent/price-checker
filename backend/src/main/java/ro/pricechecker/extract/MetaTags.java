package ro.pricechecker.extract;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;

/**
 * Step 2 of C-6: reads product facts from Open Graph product meta tags. Parses the string only;
 * tags are matched by comparing attribute values, never with a selector.
 */
public final class MetaTags {

  private static final List<String> PRICE = List.of("product:price:amount", "og:price:amount");
  private static final List<String> CURRENCY =
      List.of("product:price:currency", "og:price:currency");
  private static final List<String> AVAILABILITY =
      List.of("product:availability", "og:availability");
  private static final List<String> GTIN = List.of("product:ean");

  private MetaTags() {}

  /** Returns the facts in the page's product meta tags, or empty when it has none of them. */
  public static Optional<ExtractedFacts> fromMetaTags(String html) {
    if (html == null) {
      return Optional.empty();
    }
    Map<String, String> tags = new HashMap<>();
    for (Element meta : Jsoup.parse(html).getElementsByTag("meta")) {
      String key = meta.hasAttr("property") ? meta.attr("property") : meta.attr("name");
      String content = meta.attr("content").strip();
      if (!content.isEmpty()) {
        tags.putIfAbsent(key.strip().toLowerCase(Locale.ROOT), content);
      }
    }
    String price = first(tags, PRICE);
    String currency = first(tags, CURRENCY);
    String availability = first(tags, AVAILABILITY);
    String gtin = first(tags, GTIN);
    if (price == null && currency == null && availability == null && gtin == null) {
      return Optional.empty();
    }
    return Optional.of(
        new ExtractedFacts(
            Prices.toMinor(price), currency, SchemaAvailability.of(availability), gtin));
  }

  private static String first(Map<String, String> tags, List<String> keys) {
    for (String key : keys) {
      String value = tags.get(key);
      if (value != null) {
        return value;
      }
    }
    return null;
  }
}
