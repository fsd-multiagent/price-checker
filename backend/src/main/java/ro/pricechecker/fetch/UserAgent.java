package ro.pricechecker.fetch;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.List;
import java.util.Locale;

/**
 * The one fixed user agent (S-6, AD-6, AC 2.4.5): {@code price-checker/<version> (+<contactUrl>)}.
 * Built once at startup; an invalid contact URL or a forbidden word stops the application.
 */
public final class UserAgent {

  /** cel.ro's robots.txt blocks agents containing these words, in any case (S-6). */
  static final List<String> FORBIDDEN_WORDS = List.of("bot", "robot", "crawl", "spider");

  private static final String PRODUCT_TOKEN = "price-checker";

  private final String value;

  private UserAgent(String value) {
    this.value = value;
  }

  /**
   * Builds and checks the user agent.
   *
   * @throws IllegalStateException when the version is blank, the contact URL is missing, blank or
   *     not an absolute http(s) URL, or the result contains a forbidden word or a non-printable
   *     character
   */
  public static UserAgent build(String version, String contactUrl) {
    if (version == null || version.isBlank()) {
      throw new IllegalStateException("user agent version is missing");
    }
    if (contactUrl == null || contactUrl.isBlank()) {
      throw new IllegalStateException(
          "price-checker.contact-url (PRICE_CHECKER_CONTACT_URL) is missing; S-6 needs a contact"
              + " URL in the user agent");
    }
    String contact = contactUrl.strip();
    URI uri;
    try {
      uri = new URI(contact);
    } catch (URISyntaxException e) {
      throw new IllegalStateException("price-checker.contact-url is not a valid URL", e);
    }
    String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
    if (!uri.isAbsolute()
        || uri.isOpaque()
        || uri.getHost() == null
        || !(scheme.equals("http") || scheme.equals("https"))) {
      throw new IllegalStateException("price-checker.contact-url must be an absolute http(s) URL");
    }
    String value = PRODUCT_TOKEN + "/" + version.strip() + " (+" + contact + ")";
    if (!value.chars().allMatch(c -> c >= 0x20 && c <= 0x7e)) {
      throw new IllegalStateException("user agent must be printable ASCII");
    }
    String lower = value.toLowerCase(Locale.ROOT);
    for (String word : FORBIDDEN_WORDS) {
      if (lower.contains(word)) {
        throw new IllegalStateException("user agent must not contain '" + word + "' (S-6)");
      }
    }
    return new UserAgent(value);
  }

  /** The product token robots.txt groups are matched against (RFC 9309 §2.2.1). */
  public String productToken() {
    return PRODUCT_TOKEN;
  }

  public String value() {
    return value;
  }

  @Override
  public String toString() {
    return value;
  }
}
