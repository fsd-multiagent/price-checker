package ro.pricechecker.registry;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.error.YAMLException;

/**
 * Parses {@code retailers.yaml} and {@code known-eans.yaml}. Throws {@link
 * IllegalArgumentException} when the shape is wrong; field values are checked by {@link
 * ConfigCheck}.
 */
public final class RegistryYaml {

  private RegistryYaml() {}

  /**
   * {@code retailers: { <domain>: { adapter, resolution, minIntervalMs, concurrency, deadlineMs }
   * }}.
   */
  public static List<RetailerConfig> parseRetailers(String yaml) {
    Map<?, ?> retailers = topLevelMap(yaml, "retailers");
    List<RetailerConfig> result = new ArrayList<>();
    for (Map.Entry<?, ?> e : retailers.entrySet()) {
      String domain = scalar(e.getKey());
      if (!(e.getValue() instanceof Map<?, ?> fields)) {
        throw new IllegalArgumentException("retailer " + domain + " is not a mapping");
      }
      result.add(
          new RetailerConfig(
              domain,
              scalar(fields.get("adapter")),
              scalar(fields.get("resolution")),
              integer(fields.get("minIntervalMs")),
              integer(fields.get("concurrency")),
              integer(fields.get("deadlineMs"))));
    }
    return List.copyOf(result);
  }

  /** {@code knownEans: { <domain>: [ { ean, url }, ... ] }}. */
  public static List<KnownEan> parseKnownEans(String yaml) {
    Map<?, ?> byDomain = topLevelMap(yaml, "knownEans");
    List<KnownEan> result = new ArrayList<>();
    for (Map.Entry<?, ?> e : byDomain.entrySet()) {
      String domain = scalar(e.getKey());
      if (e.getValue() == null) {
        continue;
      }
      if (!(e.getValue() instanceof List<?> entries)) {
        throw new IllegalArgumentException("knownEans for " + domain + " is not a list");
      }
      for (Object entry : entries) {
        if (!(entry instanceof Map<?, ?> fields)) {
          throw new IllegalArgumentException("knownEans entry for " + domain + " is not a mapping");
        }
        result.add(new KnownEan(domain, scalar(fields.get("ean")), scalar(fields.get("url"))));
      }
    }
    return List.copyOf(result);
  }

  private static Map<?, ?> topLevelMap(String yaml, String key) {
    LoaderOptions options = new LoaderOptions();
    options.setAllowDuplicateKeys(false);
    Object root;
    try {
      root = new Yaml(new SafeConstructor(options)).load(yaml);
    } catch (YAMLException e) {
      throw new IllegalArgumentException("invalid YAML: " + e.getMessage(), e);
    }
    if (!(root instanceof Map<?, ?> map) || !map.containsKey(key)) {
      throw new IllegalArgumentException("missing top-level key '" + key + "'");
    }
    Object value = map.get(key);
    if (value == null) {
      return Map.of();
    }
    if (!(value instanceof Map<?, ?> inner)) {
      throw new IllegalArgumentException("'" + key + "' is not a mapping");
    }
    return inner;
  }

  private static String scalar(Object value) {
    return value == null || value instanceof Map<?, ?> || value instanceof List<?>
        ? null
        : String.valueOf(value);
  }

  private static Integer integer(Object value) {
    return value instanceof Integer i ? i : null;
  }
}
