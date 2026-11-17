package ro.pricechecker.registry;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Pure consistency check of the register against {@code retailers.yaml} and {@code known-eans.yaml}
 * (ADR 0009, AD-12, S-1). An empty result means the config is consistent.
 */
public final class ConfigCheck {

  private static final Set<String> ADAPTER_VALUES = Set.of("enabled", "disabled");
  private static final Set<String> RESOLUTION_VALUES = Set.of("search", "catalog", "curated");
  private static final Pattern EAN_DIGITS = Pattern.compile("\\d{8}|\\d{13}");
  private static final int MIN_KNOWN_EANS = 2;

  private ConfigCheck() {}

  public static List<String> violations(
      SourceRegister register, List<RetailerConfig> retailers, List<KnownEan> knownEans) {
    List<String> violations = new ArrayList<>();
    register.errors().forEach(e -> violations.add("source register: " + e));

    Set<String> approved = register.approvedDomains();
    Set<String> configured = new HashSet<>();
    for (RetailerConfig r : retailers) {
      configured.add(r.domain());
      checkRetailer(register, approved, r, violations);
    }
    for (String domain : approved) {
      if (!configured.contains(domain)) {
        violations.add(domain + ": Approved in the register but has no retailers.yaml entry");
      }
    }

    Map<String, Set<String>> distinctEans = new HashMap<>();
    for (KnownEan k : knownEans) {
      distinctEans.computeIfAbsent(k.domain(), x -> new HashSet<>()).add(k.ean());
      checkKnownEan(configured, k, violations);
    }
    for (String domain : approved) {
      int count = distinctEans.getOrDefault(domain, Set.of()).size();
      if (count < MIN_KNOWN_EANS) {
        violations.add(
            domain
                + ": Approved but has "
                + count
                + " distinct known EANs, needs at least "
                + MIN_KNOWN_EANS);
      }
    }
    return List.copyOf(violations);
  }

  private static void checkRetailer(
      SourceRegister register, Set<String> approved, RetailerConfig r, List<String> violations) {
    String d = r.domain();
    if (!register.rows().containsKey(d)) {
      violations.add(d + ": not a row in the source register");
    }
    if (r.adapter() == null || !ADAPTER_VALUES.contains(r.adapter())) {
      violations.add(d + ": adapter must be enabled or disabled, was " + r.adapter());
    }
    if (r.enabled() && !approved.contains(d)) {
      violations.add(d + ": adapter is enabled but the register does not approve it");
    }
    if (r.resolution() == null || !RESOLUTION_VALUES.contains(r.resolution())) {
      violations.add(
          d + ": resolution must be one of " + RESOLUTION_VALUES + ", was " + r.resolution());
    }
    if (r.deadlineMs() == null || r.deadlineMs() <= 0) {
      violations.add(d + ": deadlineMs must be a positive integer");
    }
    Optional<RegisterLimits> limits = register.limits(d);
    if (limits.isEmpty()) {
      violations.add(d + ": register Limits cannot be parsed");
      return;
    }
    RegisterLimits l = limits.get();
    if (r.minIntervalMs() == null || r.minIntervalMs() < l.intervalMs()) {
      violations.add(
          d
              + ": minIntervalMs "
              + r.minIntervalMs()
              + " is looser than the register's "
              + l.intervalMs());
    }
    if (r.concurrency() == null || r.concurrency() < 1 || r.concurrency() > l.concurrency()) {
      violations.add(
          d
              + ": concurrency "
              + r.concurrency()
              + " is looser than the register's "
              + l.concurrency());
    }
  }

  private static void checkKnownEan(Set<String> configured, KnownEan k, List<String> violations) {
    String d = k.domain();
    if (!configured.contains(d)) {
      violations.add(d + ": known EANs for a retailer with no retailers.yaml entry");
    }
    if (!isValidEan(k.ean())) {
      violations.add(d + ": known EAN " + k.ean() + " is not a valid EAN-8 or EAN-13");
    }
    if (!isRetailerHttpsUrl(d, k.url())) {
      violations.add(d + ": known EAN " + k.ean() + " URL must be https on the retailer's host");
    }
  }

  /** GS1 check digit for EAN-8 and EAN-13 (private: registry may not import ean, spine arrows). */
  static boolean isValidEan(String ean) {
    if (ean == null || !EAN_DIGITS.matcher(ean).matches()) {
      return false;
    }
    int sum = 0;
    int last = ean.length() - 1;
    for (int i = 0; i < last; i++) {
      int digit = ean.charAt(i) - '0';
      boolean timesThree = (last - i) % 2 == 1;
      sum += timesThree ? digit * 3 : digit;
    }
    int check = (10 - sum % 10) % 10;
    return check == ean.charAt(last) - '0';
  }

  private static boolean isRetailerHttpsUrl(String domain, String url) {
    if (url == null || domain == null) {
      return false;
    }
    try {
      URI uri = new URI(url);
      String host = uri.getHost();
      return "https".equals(uri.getScheme())
          && uri.getUserInfo() == null
          && host != null
          && (host.equals(domain) || host.equals("www." + domain));
    } catch (URISyntaxException e) {
      return false;
    }
  }
}
