package ro.pricechecker.fetch;

import crawlercommons.robots.SimpleRobotRules;
import crawlercommons.robots.SimpleRobotRules.RobotRulesMode;
import crawlercommons.robots.SimpleRobotRulesParser;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * The robots.txt rules of one host for our product token (S-4, AD-6 check 4, RFC 9309). Parsing and
 * matching are crawler-commons'; this class only decides what counts as unparseable: a NUL
 * character, or a non-empty body without a single robots line (for example an HTML page).
 */
final class RobotsRules {

  /** An empty robots.txt, or a 4xx other than 429 (RFC 9309 §2.3.1.3). */
  static final RobotsRules ALLOW_ALL =
      new RobotsRules(new SimpleRobotRules(RobotRulesMode.ALLOW_ALL));

  /** An unparseable robots.txt (fail closed). */
  static final RobotsRules DISALLOW_ALL =
      new RobotsRules(new SimpleRobotRules(RobotRulesMode.ALLOW_NONE));

  /** Only used by crawler-commons to resolve sitemap links, which we ignore. */
  private static final String BASE = "https://robots.invalid/robots.txt";

  private static final Pattern ROBOTS_LINE =
      Pattern.compile(
          "^(?:user[- ]?agent|allow|disallow|sitemap|crawl-delay)\\s*:.*",
          Pattern.CASE_INSENSITIVE);

  private final SimpleRobotRules rules;

  private RobotsRules(SimpleRobotRules rules) {
    this.rules = rules;
  }

  /**
   * Parses a robots.txt body for the product token.
   *
   * @return the rules, {@link #ALLOW_ALL} for a body with nothing but blanks and comments, or empty
   *     when the body is unparseable
   */
  static Optional<RobotsRules> parse(String body, String productToken) {
    String text = body.startsWith("\uFEFF") ? body.substring(1) : body;
    if (text.indexOf('\0') >= 0) {
      return Optional.empty();
    }
    boolean anyLine = false;
    boolean robotsLine = false;
    for (String raw : text.split("\\R", -1)) {
      int hash = raw.indexOf('#');
      String line = (hash >= 0 ? raw.substring(0, hash) : raw).strip();
      if (line.isEmpty()) {
        continue;
      }
      anyLine = true;
      if (ROBOTS_LINE.matcher(line).matches()) {
        robotsLine = true;
        break;
      }
    }
    if (!anyLine) {
      return Optional.of(ALLOW_ALL);
    }
    if (!robotsLine) {
      return Optional.empty();
    }
    SimpleRobotRulesParser parser = new SimpleRobotRulesParser();
    // Crawl-delay is not in RFC 9309 and the register sets our rate; a long one must not turn the
    // whole host into "disallow all".
    parser.setMaxCrawlDelay(Long.MAX_VALUE);
    SimpleRobotRules parsed =
        parser.parseContent(
            BASE, text.getBytes(StandardCharsets.UTF_8), "text/plain", List.of(productToken));
    return Optional.of(new RobotsRules(parsed));
  }

  /** Whether {@code url}'s path and query may be fetched. */
  boolean allows(URI url) {
    return rules.isAllowed(url.toString());
  }
}
