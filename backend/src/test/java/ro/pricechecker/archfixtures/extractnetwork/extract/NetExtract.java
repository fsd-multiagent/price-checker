package ro.pricechecker.archfixtures.extractnetwork.extract;

import java.io.File;
import java.io.IOException;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;

/** Reads a page from disk instead of a string; only the new extract rule catches this. */
public class NetExtract {
  Document read(File page) throws IOException {
    return Jsoup.parse(page, "UTF-8");
  }
}
