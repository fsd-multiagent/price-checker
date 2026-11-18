package ro.pricechecker.retailer.altexro;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import ro.pricechecker.adapter.AdapterOutcome;
import ro.pricechecker.adapter.RetailerAdapter;
import ro.pricechecker.adapter.RetailerHttp;
import ro.pricechecker.ean.Ean;
import ro.pricechecker.lookup.AdapterCatalog;
import ro.pricechecker.lookup.LookupLog;
import ro.pricechecker.lookup.LookupResult;
import ro.pricechecker.lookup.LookupRow;
import ro.pricechecker.lookup.LookupService;
import ro.pricechecker.registry.ConfigCheck;
import ro.pricechecker.registry.Registry;
import ro.pricechecker.registry.RegistryYaml;
import ro.pricechecker.registry.RetailerConfig;
import ro.pricechecker.registry.SourceRegister;
import ro.pricechecker.registry.TestRegistries;

/** The compliance gate holds the Altex Adapter back while the register does not approve it. */
class AltexroGateTest {

  private static final Path REPO_ROOT = Path.of("..");
  private static final Clock CLOCK =
      Clock.fixed(Instant.parse("2026-11-18T10:15:30Z"), ZoneOffset.UTC);
  private static final String ALTEX_DISABLED = "  altex.ro:\n    adapter: disabled\n";
  private static final String ALTEX_ENABLED = "  altex.ro:\n    adapter: enabled\n";

  private static String repoFile(String path) throws IOException {
    return Files.readString(REPO_ROOT.resolve(path));
  }

  private static RetailerAdapter stub(String id) {
    return new RetailerAdapter() {
      @Override
      public String retailerId() {
        return id;
      }

      @Override
      public AdapterOutcome fetchAndExtract(Ean ean, URI productUrl, RetailerHttp http) {
        return new AdapterOutcome.NotFound();
      }
    };
  }

  @Test
  @Tag("AC-4.1.2")
  void ac_4_1_2_bundledConfigShipsAltexDisabledAndNotApproved() {
    Registry registry = new Registry();
    assertThat(registry.isApproved("altex.ro")).isFalse();
    assertThat(registry.retailers())
        .filteredOn(r -> r.domain().equals("altex.ro"))
        .singleElement()
        .satisfies(r -> assertThat(r.enabled()).isFalse());
  }

  @Test
  @Tag("AC-4.1.2")
  void ac_4_1_2_lookupWithBundledConfigHasNoAltexRow() {
    Registry registry = new Registry();
    List<RetailerAdapter> adapters = new ArrayList<>();
    adapters.add(new AltexroAdapter());
    registry.retailers().stream()
        .map(RetailerConfig::domain)
        .filter(d -> !d.equals("altex.ro"))
        .forEach(d -> adapters.add(stub(d)));
    List<URI> requested = new ArrayList<>();
    RetailerHttp http =
        url -> {
          requested.add(url);
          return "";
        };
    LookupService service =
        new LookupService(
            registry,
            new AdapterCatalog(adapters, registry),
            Optional.of(http),
            CLOCK,
            // The runtime switch allows every domain, Altex too: only the disabled flag holds it.
            TestRegistries.runtimeAllowing(
                CLOCK,
                registry.retailers().stream().map(RetailerConfig::domain).toArray(String[]::new)),
            new LookupLog(CLOCK));

    LookupResult result = service.lookup(Ean.parse("4006381333931"));

    assertThat(result.rows()).extracting(LookupRow::retailerId).doesNotContain("altex.ro");
    assertThat(requested).noneMatch(u -> u.getHost().endsWith("altex.ro"));
  }

  @Test
  @Tag("AC-4.1.3")
  void ac_4_1_3_enablingAltexWithoutApprovalFailsTheRegisterCheck() throws IOException {
    String register = repoFile("docs/source-register.md");
    String retailers = repoFile("config/retailers.yaml");
    String knownEans = repoFile("config/known-eans.yaml");
    assertThat(retailers).contains(ALTEX_DISABLED);
    String enabled = retailers.replace(ALTEX_DISABLED, ALTEX_ENABLED);

    List<String> violations =
        ConfigCheck.violations(
            SourceRegister.parse(register),
            RegistryYaml.parseRetailers(enabled),
            RegistryYaml.parseKnownEans(knownEans));

    assertThat(violations)
        .anySatisfy(v -> assertThat(v).contains("altex.ro", "enabled", "does not approve"));
    assertThatThrownBy(() -> TestRegistries.of(register, enabled, knownEans))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("altex.ro");
  }

  @Test
  @Tag("AC-4.1.3")
  void ac_4_1_3_bundledConfigPassesTheRegisterCheck() throws IOException {
    assertThat(
            ConfigCheck.violations(
                SourceRegister.parse(repoFile("docs/source-register.md")),
                RegistryYaml.parseRetailers(repoFile("config/retailers.yaml")),
                RegistryYaml.parseKnownEans(repoFile("config/known-eans.yaml"))))
        .isEmpty();
  }
}
