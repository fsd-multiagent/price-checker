package ro.pricechecker.archfixtures.extractjsoup.extract;

import java.io.IOException;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;

/** Fetches a page with jsoup's own connection; the extract rule must catch this (AD-1). */
public class JsoupConnectExtract {
  Document fetch(String url) throws IOException {
    return Jsoup.connect(url).get();
  }
}
