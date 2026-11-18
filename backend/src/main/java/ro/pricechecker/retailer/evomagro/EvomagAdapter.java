package ro.pricechecker.retailer.evomagro;

import java.io.IOException;
import java.net.URI;
import org.springframework.stereotype.Component;
import ro.pricechecker.adapter.AdapterOutcome;
import ro.pricechecker.adapter.RetailerAdapter;
import ro.pricechecker.adapter.RetailerHttp;
import ro.pricechecker.adapter.UnavailableReason;
import ro.pricechecker.ean.Ean;

/**
 * evoMAG adapter (AD-7). GETs only the curated product URL it is given, never a search page (S-4,
 * AC-10.2).
 */
@Component
public class EvomagAdapter implements RetailerAdapter {

  static final String RETAILER_ID = "evomag.ro";

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
    return EvomagPage.extract(html, productUrl);
  }
}
