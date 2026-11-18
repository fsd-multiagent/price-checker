package ro.pricechecker;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.ConfigurableApplicationContext;

/** Entry point. Root-package wiring only; no business logic (C-1). */
@SpringBootApplication
public class PriceCheckerApplication {

  public static void main(String[] args) {
    ConfigurableApplicationContext context =
        SpringApplication.run(PriceCheckerApplication.class, args);
    // The health profile is a one-shot run: its ExitCodeGenerator decides the exit code (AC 3.5.3).
    if (context.getEnvironment().matchesProfiles(HealthConfig.PROFILE)) {
      System.exit(SpringApplication.exit(context));
    }
  }
}
