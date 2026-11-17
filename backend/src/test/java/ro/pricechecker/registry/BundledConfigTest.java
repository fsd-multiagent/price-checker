package ro.pricechecker.registry;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Reads the copies bundled into the artifact by {@code processResources}. */
@Tag("AC-1.5.1")
@Tag("AC-1.5.2")
@Tag("AC-1.5.3")
class BundledConfigTest {

  private static final Path REPO_ROOT = Path.of("..");

  @Test
  void ac_1_5_1_bundledRegisterEqualsRepoFile() throws IOException {
    String bundled = Registry.readResource(Registry.REGISTER_RESOURCE);
    assertThat(bundled).isEqualTo(Files.readString(REPO_ROOT.resolve("docs/source-register.md")));
    SourceRegister register = SourceRegister.parse(bundled);
    assertThat(register.errors()).isEmpty();
    assertThat(register.rows()).isNotEmpty();
  }

  @Test
  void ac_1_5_1_bundledConfigEqualsRepoFiles() throws IOException {
    assertThat(Registry.readResource(Registry.RETAILERS_RESOURCE))
        .isEqualTo(Files.readString(REPO_ROOT.resolve("config/retailers.yaml")));
    assertThat(Registry.readResource(Registry.KNOWN_EANS_RESOURCE))
        .isEqualTo(Files.readString(REPO_ROOT.resolve("config/known-eans.yaml")));
  }

  @Test
  void ac_1_5_1_onlyApprovedWithDateRowsAreApproved() {
    SourceRegister register =
        SourceRegister.parse(Registry.readResource(Registry.REGISTER_RESOURCE));
    register
        .rows()
        .values()
        .forEach(row ->
            assertThat(register.isApproved(row.domain()))
                .as(row.domain() + " " + row.status())
                .isEqualTo(row.status().matches("Approved \\d{4}-\\d{2}-\\d{2}")));
  }

  @Test
  void ac_1_5_2_repoConfigHasNoViolations() {
    SourceRegister register =
        SourceRegister.parse(Registry.readResource(Registry.REGISTER_RESOURCE));
    assertThat(
            ConfigCheck.violations(
                register,
                RegistryYaml.parseRetailers(Registry.readResource(Registry.RETAILERS_RESOURCE)),
                RegistryYaml.parseKnownEans(Registry.readResource(Registry.KNOWN_EANS_RESOURCE))))
        .isEmpty();
  }

  @Test
  void ac_1_5_2_registryLoadsBundledFiles() {
    Registry registry = new Registry();
    assertThat(registry.retailers())
        .allSatisfy(r -> assertThat(r.resolution()).isEqualTo("curated"));
    assertThat(registry.approvedDomains())
        .isEqualTo(
            SourceRegister.parse(Registry.readResource(Registry.REGISTER_RESOURCE))
                .approvedDomains());
  }
}
