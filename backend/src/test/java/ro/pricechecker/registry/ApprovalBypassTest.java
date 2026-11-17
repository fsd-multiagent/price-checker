package ro.pricechecker.registry;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/** Properties cannot approve a retailer or move the register (AC 1.5.4, AC-9.3). */
@SpringBootTest(
    properties = {
      "registry.approved=a.example,b.example",
      "registry.register=classpath:nothing.md",
      "pricechecker.registry.approved=a.example",
      "pricechecker.registry.retailers=file:/tmp/retailers.yaml",
      "pricechecker.registry.register-resource=nothing.md"
    })
@Tag("AC-1.5.4")
class ApprovalBypassTest {

  private final Registry registry;

  @Autowired
  ApprovalBypassTest(Registry registry) {
    this.registry = registry;
  }

  @Test
  void ac_1_5_4_propertiesDoNotChangeApproval() {
    SourceRegister bundled =
        SourceRegister.parse(Registry.readResource(Registry.REGISTER_RESOURCE));
    assertThat(registry.approvedDomains()).isEqualTo(bundled.approvedDomains());
    assertThat(registry.isApproved("a.example")).isFalse();
    assertThat(registry.isApproved("b.example")).isFalse();
    bundled
        .rows()
        .keySet()
        .forEach(d -> assertThat(registry.isApproved(d)).isEqualTo(bundled.isApproved(d)));
  }
}
