package ro.pricechecker.archfixtures.extracturi.extract;

import java.net.URI;
import org.jsoup.Jsoup;

/** Parses a string and resolves a URI without opening it; the extract rule allows this. */
public class UriExtract {
  String host(String html, String base) {
    return Jsoup.parse(html).title() + URI.create(base).getHost();
  }
}
