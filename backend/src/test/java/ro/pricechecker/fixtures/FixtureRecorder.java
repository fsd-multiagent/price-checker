package ro.pricechecker.fixtures;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import ro.pricechecker.fetch.FetchClient;
import ro.pricechecker.fetch.FetchedPage;
import ro.pricechecker.fetch.JdkTransport;
import ro.pricechecker.fetch.PageStripper;
import ro.pricechecker.fetch.Sleeper;
import ro.pricechecker.fetch.UserAgent;
import ro.pricechecker.registry.BlockStore;
import ro.pricechecker.registry.KnownEan;
import ro.pricechecker.registry.Registry;
import ro.pricechecker.registry.RuntimeSwitch;

/**
 * Records a retailer's fixtures from its live product pages (story 2.13, AC 2.13.1). Off unless
 * {@code PRICE_CHECKER_RECORD_FIXTURES} names a {@code retailers.yaml} id, so CI never runs it
 * (T-3). Run it by hand, for an Approved retailer only: {@code
 * PRICE_CHECKER_RECORD_FIXTURES=<retailer id> backend/gradlew -p backend test --tests
 * '*FixtureRecorder'}.
 *
 * <p>Every request goes through the shared {@link FetchClient} (Approved check, robots.txt, rate
 * limit, user agent, Blocks; AD-6) at the {@code retailers.yaml} rate, and only to that retailer's
 * Known-EAN product URLs (never a search page, AC 2.13.2). A Block fails the run. Each page is
 * saved only after {@link PageStripper#strip} and {@link #factsOnly} (S-7, T-2) as {@code
 * fixtures/<retailer id>/real-<ean>.html}.
 */
@EnabledIfEnvironmentVariable(named = FixtureRecorder.ENV, matches = ".+")
class FixtureRecorder {

  static final String ENV = "PRICE_CHECKER_RECORD_FIXTURES";

  private static final String CONTACT_URL = "https://github.com/fsd-multiagent/price-checker";

  @Test
  void recordKnownEanPages(@TempDir Path state) throws Exception {
    String retailer = System.getenv(ENV);
    Path dir = Path.of("..", "fixtures", retailer);
    assertThat(dir).as("fixture dir").isDirectory();
    Path runtimeFile = state.resolve("runtime.yaml");
    Files.writeString(runtimeFile, "retailers:\n  " + retailer + ": { fetching: allowed }\n");
    Clock clock = Clock.systemUTC();
    RuntimeSwitch runtime = new RuntimeSwitch(runtimeFile, clock);
    assertThat(runtime.refresh()).isEmpty();
    BlockStore blocks =
        new BlockStore(
            state.resolve("blocks.json"),
            runtime,
            clock,
            (id, reason) -> {
              throw new AssertionError("Blocked by " + id + ": " + reason);
            });
    Registry registry = new Registry();
    FetchClient client =
        new FetchClient(
            registry,
            runtime,
            blocks,
            new JdkTransport(),
            clock,
            Sleeper.SYSTEM,
            UserAgent.build("dev", CONTACT_URL),
            FetchClient.Pace.NORMAL);
    FetchClient.BoundHttp http = client.bind(retailer);

    List<KnownEan> eans =
        registry.knownEans().stream().filter(k -> k.domain().equals(retailer)).toList();
    assertThat(eans).hasSizeGreaterThanOrEqualTo(2);
    Map<Path, String> pages = new LinkedHashMap<>();
    for (KnownEan known : eans) {
      URI url = URI.create(known.url());
      assertThat(url.getQuery()).as("no search request").isNull();
      FetchedPage page = http.fetch(url);
      // A redirect (for example to a category page when the product is gone) is not the product.
      assertThat(page.url()).as(known.ean() + " redirected").isEqualTo(url);
      String facts = factsOnly(PageStripper.strip(page.body()));
      assertThat(facts).as(known.ean() + " has a Product").containsIgnoringCase("product");
      pages.put(dir.resolve("real-" + known.ean() + ".html"), facts);
    }
    // Write only after every page was fetched, so a failed run changes no fixture.
    for (Map.Entry<Path, String> page : pages.entrySet()) {
      Files.writeString(page.getKey(), page.getValue());
    }
  }

  /**
   * Keeps only the facts of a stripped page (S-7, T-2): Product JSON-LD, product meta tags, and
   * each top-level schema.org Product microdata scope reduced to its fact properties (the Offer
   * scope, price, currency, availability, GTIN, SKU, MPN, condition), with the attributes the
   * extractors read. Names, descriptions, images, ratings and reviews (with reviewer names) are
   * dropped from microdata; each kept fact keeps its itemprop and value attributes, in a rebuilt
   * element. JSON-LD is copied as {@link PageStripper} left it, which can still hold fields such as
   * {@code review} (story 2.2 deferral): check it before committing a page that has Product
   * JSON-LD.
   */
  static String factsOnly(String stripped) {
    Document page = Jsoup.parse(stripped);
    Document out = Document.createShell("");
    for (Element meta : page.select("meta[property]")) {
      String property = meta.attr("property");
      if (property.startsWith("product:") || FACT_META.contains(property)) {
        out.head().appendChild(meta.clone());
      }
    }
    for (Element script : page.select("script[type$=ld+json]")) {
      out.head().appendChild(script.clone());
    }
    for (Element scope : page.select("[itemscope][itemtype]")) {
      if (scope.attr("itemtype").endsWith("/Product") && !scope.hasAttr("itemprop")) {
        Element copy = new Element(scope.tagName());
        copy.attr("itemscope", true).attr("itemtype", scope.attr("itemtype"));
        collectFacts(scope, copy);
        out.body().appendChild(copy);
      }
    }
    return out.outerHtml() + "\n";
  }

  private static final Set<String> FACT_META =
      Set.of("og:type", "og:url", "og:price:amount", "og:price:currency", "og:availability");

  private static final Set<String> FACT_PROPS =
      Set.of(
          "offers",
          "price",
          "priceCurrency",
          "availability",
          "gtin",
          "gtin8",
          "gtin12",
          "gtin13",
          "gtin14",
          "sku",
          "mpn",
          "itemCondition");

  private static void collectFacts(Element parent, Element into) {
    for (Element child : parent.children()) {
      String prop = child.attr("itemprop");
      if (FACT_PROPS.contains(prop)) {
        Element copy = new Element(child.tagName()).attr("itemprop", prop);
        if (child.hasAttr("itemscope")) {
          copy.attr("itemscope", true).attr("itemtype", child.attr("itemtype"));
          collectFacts(child, copy);
        } else {
          for (String attribute : List.of("content", "href")) {
            if (child.hasAttr(attribute)) {
              copy.attr(attribute, child.attr(attribute));
            }
          }
          if (!child.hasAttr("content") && !child.hasAttr("href")) {
            copy.text(child.text());
          }
        }
        into.appendChild(copy);
      } else if (prop.isEmpty() && !child.hasAttr("itemscope")) {
        // A plain wrapper: look inside it, flattening the layout away.
        collectFacts(child, into);
      }
    }
  }
}
