package ro.pricechecker.retailer.altexro;

import java.io.IOException;
import java.net.URI;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Function;
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
import ro.pricechecker.fetch.FetchFailure;

/**
 * Altex Adapter (AD-7). Ships disabled until the owner approves the domain in the register (S-1);
 * tested only against synthetic fixtures in {@code fixtures/altex.ro/} (T-2).
 *
 * <p>Extraction order (C-6): JSON-LD, then meta tags, then this adapter's own selectors. The first
 * step that finds a price wins; otherwise the first step that finds any fact; otherwise the page is
 * {@link UnavailableReason#UNPARSEABLE}. A step read before the winner, or the winner itself, that
 * states another product's GTIN makes the page {@code NotFound} (C-7).
 */
@Component
public class AltexroAdapter implements RetailerAdapter {

  static final String ID = "altex.ro";

  static final String PRICE_SELECTOR = ".Price-current";
  static final String STOCK_SELECTOR = ".Status-stock";
  static final String EAN_SELECTOR = ".Product-ean";

  private static final Pattern NON_DIGIT = Pattern.compile("\\D");

  private static final List<Function<String, Optional<ExtractedFacts>>> STEPS =
      List.of(
          AltexroAdapter::fromStructuredData,
          AltexroAdapter::fromMetaTags,
          AltexroAdapter::fromSelectors);

  @Override
  public String retailerId() {
    return ID;
  }

  @Override
  public AdapterOutcome fetchAndExtract(Ean ean, URI productUrl, RetailerHttp http) {
    String html;
    try {
      html = http.get(productUrl);
    } catch (FetchFailure f) {
      return f.outcome();
    } catch (IOException e) {
      return new AdapterOutcome.Unavailable(UnavailableReason.HTTP_ERROR);
    }
    ExtractedFacts chosen = null;
    for (Function<String, Optional<ExtractedFacts>> step : STEPS) {
      Optional<ExtractedFacts> found = step.apply(html).filter(AltexroAdapter::hasAnyFact);
      if (found.isEmpty()) {
        continue;
      }
      if (isOtherProduct(found.get().gtin(), ean)) {
        return new AdapterOutcome.NotFound();
      }
      if (found.get().priceMinor() != null) {
        chosen = found.get();
        break;
      }
      if (chosen == null) {
        chosen = found.get();
      }
    }
    if (chosen == null) {
      return new AdapterOutcome.Unavailable(UnavailableReason.UNPARSEABLE);
    }
    return found(chosen, productUrl);
  }

  /**
   * True when the page states a GTIN that is not the requested EAN, so its price belongs to another
   * product and is never reported for this one (C-7). Leading zeros are ignored (GTIN-14 vs
   * EAN-13).
   */
  static boolean isOtherProduct(String gtin, Ean ean) {
    if (gtin == null) {
      return false;
    }
    String digits = NON_DIGIT.matcher(gtin).replaceAll("");
    if (digits.isEmpty()) {
      return false;
    }
    return !stripLeadingZeros(digits).equals(stripLeadingZeros(ean.value()));
  }

  private static String stripLeadingZeros(String digits) {
    int i = 0;
    while (i < digits.length() - 1 && digits.charAt(i) == '0') {
      i++;
    }
    return digits.substring(i);
  }

  /** Step 1: the page's JSON-LD {@code Product}. */
  static Optional<ExtractedFacts> fromStructuredData(String html) {
    return StructuredData.fromJsonLd(html);
  }

  /** Step 2: the page's Open Graph product meta tags. */
  static Optional<ExtractedFacts> fromMetaTags(String html) {
    return MetaTags.fromMetaTags(html);
  }

  /** Step 3: Altex's own markup. A price shown in {@code lei} is RON. */
  static Optional<ExtractedFacts> fromSelectors(String html) {
    if (html == null) {
      return Optional.empty();
    }
    Document doc = Jsoup.parse(html);
    String price = textOf(doc.selectFirst(PRICE_SELECTOR));
    String stock = textOf(doc.selectFirst(STOCK_SELECTOR));
    String gtin = textOf(doc.selectFirst(EAN_SELECTOR));
    if (price == null && stock == null && gtin == null) {
      return Optional.empty();
    }
    String currency =
        price != null && price.toLowerCase(Locale.ROOT).contains("lei") ? "RON" : null;
    return Optional.of(new ExtractedFacts(Prices.toMinor(price), currency, stockOf(stock), gtin));
  }

  private static String textOf(Element element) {
    if (element == null) {
      return null;
    }
    String text = element.text().strip();
    return text.isEmpty() ? null : text;
  }

  private static ExtractedFacts.Availability stockOf(String text) {
    if (text == null) {
      return ExtractedFacts.Availability.UNKNOWN;
    }
    String t = text.toLowerCase(Locale.ROOT);
    if (t.contains("epuizat") || t.contains("indisponibil") || t.contains("nu este")) {
      return ExtractedFacts.Availability.OUT_OF_STOCK;
    }
    if (t.contains("în stoc") || t.contains("in stoc")) {
      return ExtractedFacts.Availability.IN_STOCK;
    }
    return ExtractedFacts.Availability.UNKNOWN;
  }

  private static boolean hasAnyFact(ExtractedFacts f) {
    return f.priceMinor() != null
        || f.currency() != null
        || f.gtin() != null
        || f.availability() != ExtractedFacts.Availability.UNKNOWN;
  }

  private static AdapterOutcome found(ExtractedFacts f, URI productUrl) {
    return new AdapterOutcome.Found(
        new PageFacts(
            f.priceMinor(),
            f.currency(),
            Availability.valueOf(f.availability().name()),
            productUrl,
            f.gtin()));
  }
}
