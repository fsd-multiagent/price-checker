package ro.pricechecker.registry;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.error.YAMLException;

/**
 * One parsed {@code runtime.yaml} (AD-9, FR-7, S-8): which retailers may be fetched. Immutable. A
 * retailer missing from the file is stopped (fail closed). Only {@code fetching} is read; other
 * per-retailer keys ({@code clearGeneration}, {@code runnerBlock}, {@code notBefore}) are ignored.
 */
public final class RuntimeSettings {

  /** No good read yet: every retailer is stopped. */
  static final RuntimeSettings NONE = new RuntimeSettings(Set.of());

  private final Set<String> allowed;

  private RuntimeSettings(Set<String> allowed) {
    this.allowed = Set.copyOf(allowed);
  }

  /** True only when the file lists {@code domain} with {@code fetching: allowed}. */
  public boolean isAllowed(String domain) {
    return allowed.contains(domain);
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
    }
    return new RuntimeSettings(allowed);
  }
}
