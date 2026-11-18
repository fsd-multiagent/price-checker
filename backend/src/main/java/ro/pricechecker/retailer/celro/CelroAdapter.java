package ro.pricechecker.retailer.celro;

import java.io.IOException;
import java.net.URI;
import java.text.Normalizer;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.springframework.stereotype.Component;
import ro.pricechecker.adapter.AdapterOutcome;
import ro.pricechecker.adapter.Availability;
import ro.pricechecker.adapter.PageFacts;
import ro.pricechecker.adapter.RetailerAdapter;
import ro.pricechecker.adapter.RetailerHttp;
import ro.pricechecker.adapter.UnavailableReason;
import ro.pricechecker.ean.Ean;
import ro.pricechecker.extract.ExtractedFacts;
import ro.pricechecker.extract.MetaTags;
import ro.pricechecker.extract.Prices;
import ro.pricechecker.extract.StructuredData;

/**
 * Reads price, currency, availability and EAN from a curated cel.ro product page (story 2.10).
 * Steps run in C-6 order (JSON-LD, meta tags, cel.ro selectors); each field comes from the first
 * step that supplies it. No deadlines, cache, logging or config here (AD-7).
 */
@Component
public class CelroAdapter implements RetailerAdapter {

  static final String RETAILER_ID = "cel.ro";

  // Checked against recorded cel.ro pages (story 2.12): the price element holds only the number
  // and its sibling ".moneda" holds the currency word; the stock element is empty in the served
  // HTML (filled by script), so it rarely gives a fact.
  private static final String PRICE_SELECTOR = "#product-price";
  private static final String CURRENCY_SELECTOR = "#product-price ~ .moneda";
  private static final String STOCK_SELECTOR = "#info_stoc";
  // [ASSUMPTION] Recorded pages show no EAN element; kept for pages that might label one.
  private static final String EAN_SELECTOR = ".product-ean";

  private static final Pattern CURRENCY_WORD =
      Pattern.compile("(?<![a-z])(lei|ron)(?![a-z])", Pattern.CASE_INSENSITIVE);
  private static final Pattern NON_DIGIT = Pattern.compile("[^0-9]");
  private static final Pattern NEGATION = Pattern.compile("(?<![a-z])nu(?![a-z])");
  private static final Pattern WHITESPACE = Pattern.compile("[\\s\\u00A0]+");

  public CelroAdapter() {}

  @Override
  public String retailerId() {
    return RETAILER_ID;
  }

  @Override
  public AdapterOutcome fetchAndExtract(Ean ean, URI productUrl, RetailerHttp http) {
    String html;
    try {
      html = http.get(productUrl);
    } catch (IOException e) {
      return new AdapterOutcome.Unavailable(UnavailableReason.HTTP_ERROR);
    }
    return extract(html, productUrl);
  }

  /** Runs the three C-6 steps on {@code html} and merges them field by field. */
  static AdapterOutcome extract(String html, URI productUrl) {
    List<Optional<ExtractedFacts>> steps =
        List.of(StructuredData.fromJsonLd(html), MetaTags.fromMetaTags(html), fromSelectors(html));
    Long price = null;
    String currency = null;
    ExtractedFacts.Availability availability = ExtractedFacts.Availability.UNKNOWN;
    String gtin = null;
    for (Optional<ExtractedFacts> step : steps) {
      if (step.isEmpty()) {
        continue;
      }
      ExtractedFacts facts = step.get();
      price = price != null ? price : facts.priceMinor();
      currency = currency != null ? currency : facts.currency();
      gtin = gtin != null ? gtin : facts.gtin();
      if (availability == ExtractedFacts.Availability.UNKNOWN) {
        availability = facts.availability();
      }
    }
    if (price == null
        && currency == null
        && gtin == null
        && availability == ExtractedFacts.Availability.UNKNOWN) {
      return new AdapterOutcome.Unavailable(UnavailableReason.UNPARSEABLE);
    }
    return new AdapterOutcome.Found(
        new PageFacts(
            price, currency, Availability.valueOf(availability.name()), productUrl, gtin));
  }

  /** Step 3 of C-6: cel.ro selectors, or empty when none of them is on the page. */
  static Optional<ExtractedFacts> fromSelectors(String html) {
    if (html == null) {
      return Optional.empty();
    }
    Document page = Jsoup.parse(html);
    String priceText = textOf(page.selectFirst(PRICE_SELECTOR));
    String stockText = textOf(page.selectFirst(STOCK_SELECTOR));
    String eanText = textOf(page.selectFirst(EAN_SELECTOR));
    String eanDigits = eanText == null ? null : NON_DIGIT.matcher(eanText).replaceAll("");
    if (priceText == null && stockText == null && eanText == null) {
      return Optional.empty();
    }
    String currencyText = textOf(page.selectFirst(CURRENCY_SELECTOR));
    boolean ron =
        (priceText != null && CURRENCY_WORD.matcher(priceText).find())
            || (priceText != null
                && currencyText != null
                && CURRENCY_WORD.matcher(currencyText).find());
    String currency = ron ? "RON" : null;
    return Optional.of(
        new ExtractedFacts(Prices.toMinor(priceText), currency, stockOf(stockText), eanDigits));
  }

  /**
   * "în stoc" (or "in stoc") is in stock; "stoc epuizat", "indisponibil" or a negated "nu ... stoc"
   * is out of stock; else unknown.
   */
  static ExtractedFacts.Availability stockOf(String text) {
    if (text == null) {
      return ExtractedFacts.Availability.UNKNOWN;
    }
    String t = Normalizer.normalize(text, Normalizer.Form.NFC).toLowerCase(Locale.ROOT);
    if (t.contains("stoc epuizat")
        || t.contains("indisponibil")
        || (t.contains("stoc") && NEGATION.matcher(t).find())) {
      return ExtractedFacts.Availability.OUT_OF_STOCK;
    }
    if (t.contains("în stoc") || t.contains("in stoc")) {
      return ExtractedFacts.Availability.IN_STOCK;
    }
    return ExtractedFacts.Availability.UNKNOWN;
  }

  private static String textOf(Element element) {
    if (element == null) {
      return null;
    }
    String text = WHITESPACE.matcher(element.text()).replaceAll(" ").strip();
    return text.isEmpty() ? null : text;
  }
}
