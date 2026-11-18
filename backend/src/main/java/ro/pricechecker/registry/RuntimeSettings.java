package ro.pricechecker.registry;

import java.math.BigInteger;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.error.YAMLException;

/**
 * One parsed {@code runtime.yaml} (AD-9, FR-7, FR-11, S-8): which retailers may be fetched, each
 * retailer's {@code clearGeneration} (a non-negative integer, default 0) and whether it has a
 * {@code runnerBlock} (present = blocked, AD-16). Immutable. A retailer missing from the file is
 * stopped (fail closed). {@code notBefore} is ignored until story 2.7.
 */
public final class RuntimeSettings {

  /** No good read yet: every retailer is stopped. */
  static final RuntimeSettings NONE = new RuntimeSettings(Set.of(), Map.of(), Set.of());

  private final Set<String> allowed;
  private final Map<String, Long> clearGenerations;
  private final Set<String> runnerBlocked;

  private RuntimeSettings(
      Set<String> allowed, Map<String, Long> clearGenerations, Set<String> runnerBlocked) {
    this.allowed = Set.copyOf(allowed);
    this.clearGenerations = Map.copyOf(clearGenerations);
    this.runnerBlocked = Set.copyOf(runnerBlocked);
  }

  /** True only when the file lists {@code domain} with {@code fetching: allowed}. */
  public boolean isAllowed(String domain) {
    return allowed.contains(domain);
  }

  /** The retailer's {@code clearGeneration}; 0 when absent. */
  public long clearGeneration(String domain) {
    return clearGenerations.getOrDefault(domain, 0L);
  }

  /** True when the file lists a {@code runnerBlock} for {@code domain}. */
  public boolean hasRunnerBlock(String domain) {
    return runnerBlocked.contains(domain);
  }

  /**
   * {@code retailers: { <domain>: { fetching: allowed|stopped, ... } }}. Throws {@link
   * IllegalArgumentException} when the whole file must be rejected.
   */
  public static RuntimeSettings parse(String yaml) {
    LoaderOptions options = new LoaderOptions();
    options.setAllowDuplicateKeys(false);
    Object root;
    try {
      root = new Yaml(new SafeConstructor(options)).load(yaml);
    } catch (YAMLException e) {
      throw new IllegalArgumentException("invalid YAML", e);
    }
    if (!(root instanceof Map<?, ?> map) || !(map.get("retailers") instanceof Map<?, ?> entries)) {
      throw new IllegalArgumentException("no 'retailers' mapping");
    }
    Set<String> allowed = new HashSet<>();
    Map<String, Long> clearGenerations = new HashMap<>();
    Set<String> runnerBlocked = new HashSet<>();
    for (Map.Entry<?, ?> e : entries.entrySet()) {
      if (!(e.getKey() instanceof String domain)) {
        throw new IllegalArgumentException("retailer key is not a string");
      }
      if (!(e.getValue() instanceof Map<?, ?> fields)) {
        throw new IllegalArgumentException("retailer entry is not a mapping");
      }
      Object fetching = fields.get("fetching");
      if ("allowed".equals(fetching)) {
        allowed.add(domain);
      } else if (!"stopped".equals(fetching)) {
        throw new IllegalArgumentException("fetching is not 'allowed' or 'stopped'");
      }
      if (fields.containsKey("clearGeneration")) {
        clearGenerations.put(domain, clearGeneration(fields.get("clearGeneration")));
      }
      if (fields.containsKey("runnerBlock")) {
        runnerBlocked.add(domain);
      }
    }
    return new RuntimeSettings(allowed, clearGenerations, runnerBlocked);
  }

  /** A non-negative YAML integer that fits a {@code long}; anything else rejects the file. */
  private static long clearGeneration(Object value) {
    long generation;
    if (value instanceof Integer i) {
      generation = i;
    } else if (value instanceof Long l) {
      generation = l;
    } else if (value instanceof BigInteger b && b.bitLength() < Long.SIZE) {
      generation = b.longValue();
    } else {
      throw new IllegalArgumentException("clearGeneration is not an integer");
    }
    if (generation < 0) {
      throw new IllegalArgumentException("clearGeneration is negative");
    }
    return generation;
  }
}
