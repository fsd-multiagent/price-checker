package ro.pricechecker.lookup;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import org.springframework.stereotype.Component;
import ro.pricechecker.adapter.RetailerAdapter;
import ro.pricechecker.registry.Registry;
import ro.pricechecker.registry.RetailerConfig;

/**
 * Adapter beans by retailer id. The application fails to start unless the adapter ids and the
 * {@code retailers.yaml} ids match one-to-one (AD-7).
 */
@Component
public class AdapterCatalog {

  private final Map<String, RetailerAdapter> byId = new LinkedHashMap<>();

  public AdapterCatalog(List<RetailerAdapter> adapters, Registry registry) {
    Set<String> duplicates = new TreeSet<>();
    for (RetailerAdapter adapter : adapters) {
      if (byId.putIfAbsent(adapter.retailerId(), adapter) != null) {
        duplicates.add(adapter.retailerId());
      }
    }
    Set<String> configured = new HashSet<>();
    registry.retailers().forEach((RetailerConfig r) -> configured.add(r.domain()));
    Set<String> missing = new TreeSet<>(configured);
    missing.removeAll(byId.keySet());
    Set<String> extra = new TreeSet<>(byId.keySet());
    extra.removeAll(configured);
    if (!duplicates.isEmpty() || !missing.isEmpty() || !extra.isEmpty()) {
      throw new IllegalStateException(
          "adapter beans and retailers.yaml ids differ: duplicate adapters "
              + duplicates
              + ", configured without adapter "
              + missing
              + ", adapter without config "
              + extra);
    }
  }

  Optional<RetailerAdapter> find(String retailerId) {
    return Optional.ofNullable(byId.get(retailerId));
  }
}
