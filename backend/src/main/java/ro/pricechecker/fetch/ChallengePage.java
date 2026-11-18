package ro.pricechecker.fetch;

import java.util.List;

/**
 * Recognizes a bot-challenge page by generic markers of the common challenge services (AD-6 outcome
 * table, FR-11). No retailer-specific markers live here. A recognized page is a Block, never
 * retried and never worked around (S-6, AC-11.3).
 */
final class ChallengePage {

  /** Markers that appear in challenge pages and not in product pages. */
  static final List<String> MARKERS =
      List.of("_cf_chl_opt", "cf-browser-verification", "_Incapsula_Resource", "px-captcha");

  private ChallengePage() {}

  /** True when {@code body} contains any marker. */
  static boolean detect(String body) {
    if (body == null) {
      return false;
    }
    for (String marker : MARKERS) {
      if (body.contains(marker)) {
        return true;
      }
    }
    return false;
  }
}
