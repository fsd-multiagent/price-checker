package ro.pricechecker;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;

@SpringBootTest
@Tag("AC-1.1.4")
class AppConfigTest {

  private final Environment environment;
  private final Clock clock;

  @Autowired
  AppConfigTest(Environment environment, Clock clock) {
    this.environment = environment;
    this.clock = clock;
  }

  @Test
  void ac_1_1_4_tomcatAccessLogIsOff() {
    assertThat(environment.getProperty("server.tomcat.accesslog.enabled", Boolean.class)).isFalse();
  }

  @Test
  void ac_1_1_4_clockBeanExists() {
    assertThat(clock.getZone()).isEqualTo(ZoneOffset.UTC);
  }
}
