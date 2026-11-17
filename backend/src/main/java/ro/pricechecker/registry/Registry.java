package ro.pricechecker.registry;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * The approved-retailer authority (AD-8, ADR 0009, S-1). Loads the register and retailer config
 * bundled into the artifact from fixed classpath locations; no property or environment variable can
 * change them (AC 1.5.4). Refuses to start when {@link ConfigCheck} reports a violation.
 */
@Component
public final class Registry {

  static final String REGISTER_RESOURCE = "registry/source-register.md";
  static final String RETAILERS_RESOURCE = "registry/retailers.yaml";
  static final String KNOWN_EANS_RESOURCE = "registry/known-eans.yaml";

  private final SourceRegister register;
  private final List<RetailerConfig> retailers;
  private final List<KnownEan> knownEans;

  public Registry() {
    this(
        readResource(REGISTER_RESOURCE),
        readResource(RETAILERS_RESOURCE),
        readResource(KNOWN_EANS_RESOURCE));
  }

  Registry(String registerMarkdown, String retailersYaml, String knownEansYaml) {
    this.register = SourceRegister.parse(registerMarkdown);
    try {
      this.retailers = RegistryYaml.parseRetailers(retailersYaml);
      this.knownEans = RegistryYaml.parseKnownEans(knownEansYaml);
    } catch (IllegalArgumentException e) {
      throw new IllegalStateException("retailer config is invalid: " + e.getMessage(), e);
    }
    List<String> violations = ConfigCheck.violations(register, retailers, knownEans);
    if (!violations.isEmpty()) {
      throw new IllegalStateException(
          "retailer config violates the source register: " + String.join("; ", violations));
    }
  }

  static String readResource(String name) {
    try (InputStream in = Registry.class.getClassLoader().getResourceAsStream(name)) {
      if (in == null) {
        throw new IllegalStateException("missing bundled resource " + name);
      }
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  public boolean isApproved(String domain) {
    return register.isApproved(domain);
  }

  public Set<String> approvedDomains() {
    return register.approvedDomains();
  }

  public List<RetailerConfig> retailers() {
    return retailers;
  }

  public List<KnownEan> knownEans() {
    return knownEans;
  }
}
