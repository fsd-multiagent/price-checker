package ro.pricechecker.retailer.evomagro;

import java.net.URI;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;
import ro.pricechecker.adapter.AdapterOutcome;
import ro.pricechecker.adapter.Availability;
import ro.pricechecker.adapter.PageFacts;
import ro.pricechecker.adapter.UnavailableReason;
import ro.pricechecker.extract.ExtractedFacts;
import ro.pricechecker.extract.MetaTags;
import ro.pricechecker.extract.Prices;
import ro.pricechecker.extract.StructuredData;

/**
 * Turns an evoMAG product page into an outcome. Extraction runs in the C-6 order: JSON-LD, then
 * meta tags, then evoMAG's own selectors (schema.org microdata). Parses the string only.
 */
final class EvomagPage {

  private static final String PRICE = "[itemprop=price]";
  private static final String CURRENCY = "[itemprop=priceCurrency]";
  private static final String AVAILABILITY = "[itemprop=availability]";
  private static final String GTIN = "[itemprop=gtin13]";

  private EvomagPage() {}

  /** Step 1: the first JSON-LD {@code Product}. */
  static Optional<ExtractedFacts> structuredData(String html) {
    return StructuredData.fromJsonLd(html);
  }

  /** Step 2: Open Graph product meta tags. */
  static Optional<ExtractedFacts> metaTags(String html) {
    return MetaTags.fromMetaTags(html);
  }

  /** Step 3: microdata inside the first element whose {@code itemtype} is a schema.org Product. */
  static Optional<ExtractedFacts> ownSelectors(String html) {
    if (html == null) {
      return Optional.empty();
    }
    Optional<Element> product =
        Jsoup.parse(html).getElementsByAttribute("itemtype").stream()
            .filter(e -> termOf(e.attr("itemtype")).equals("Product"))
            .findFirst();
    if (product.isEmpty()) {
      return Optional.empty();
    }
    Element scope = product.get();
    String price = valueOf(scope.selectFirst(PRICE), "content");
    String currency = valueOf(scope.selectFirst(CURRENCY), "content");
    Element stock = scope.selectFirst(AVAILABILITY);
    String availability =
        stock == null ? null : firstNonBlank(stock.attr("href"), stock.attr("content"));
    String gtin = valueOf(scope.selectFirst(GTIN), "content");
    return Optional.of(
        new ExtractedFacts(Prices.toMinor(price), currency, availabilityOf(availability), gtin));
  }

  /**
   * Picks the first step with a price; otherwise the first step that recognized any fact (with a
   * {@code null} price, C-7); otherwise {@code Unavailable(UNPARSEABLE)}.
   */
  static AdapterOutcome extract(String html, URI url) {
    List<Optional<ExtractedFacts>> steps =
        List.of(structuredData(html), metaTags(html), ownSelectors(html));
    Optional<ExtractedFacts> chosen =
        steps.stream()
            .flatMap(Optional::stream)
            .filter(f -> f.priceMinor() != null)
            .findFirst()
            .or(() ->
                steps.stream()
                    .flatMap(Optional::stream)
                    .filter(EvomagPage::recognized)
                    .findFirst());
    return chosen
        .<AdapterOutcome>map(f -> new AdapterOutcome.Found(toPageFacts(f, url)))
        .orElseGet(() -> new AdapterOutcome.Unavailable(UnavailableReason.UNPARSEABLE));
  }

  private static boolean recognized(ExtractedFacts f) {
    return f.priceMinor() != null
        || f.currency() != null
        || f.gtin() != null
        || f.availability() != ExtractedFacts.Availability.UNKNOWN;
  }

  private static PageFacts toPageFacts(ExtractedFacts f, URI url) {
    return new PageFacts(
        f.priceMinor(), f.currency(), Availability.valueOf(f.availability().name()), url, f.gtin());
  }

  /** Maps a schema.org availability URL or term by the text after the last {@code /}. */
  static ExtractedFacts.Availability availabilityOf(String text) {
    if (text == null) {
      return ExtractedFacts.Availability.UNKNOWN;
    }
    String term = termOf(text);
    term = term.substring(term.lastIndexOf(':') + 1).toLowerCase(Locale.ROOT);
    return switch (term.replaceAll("[^a-z]", "")) {
      case "instock", "limitedavailability", "onlineonly" -> ExtractedFacts.Availability.IN_STOCK;
      case "outofstock", "soldout", "discontinued", "oos" ->
          ExtractedFacts.Availability.OUT_OF_STOCK;
      default -> ExtractedFacts.Availability.UNKNOWN;
    };
  }

  private static String termOf(String text) {
    String t = text.strip();
    return t.substring(t.lastIndexOf('/') + 1);
  }

  /** The attribute when present and not blank, else the element's text. */
  private static String valueOf(Element element, String attribute) {
    if (element == null) {
      return null;
    }
    return firstNonBlank(element.attr(attribute), element.text());
  }

  private static String firstNonBlank(String first, String second) {
    if (!first.isBlank()) {
      return first.strip();
    }
    return second.isBlank() ? null : second.strip();
  }
}
