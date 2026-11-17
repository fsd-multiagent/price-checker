package ro.pricechecker;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** Entry point. Root-package wiring only; no business logic (C-1). */
@SpringBootApplication
public class PriceCheckerApplication {

  public static void main(String[] args) {
    SpringApplication.run(PriceCheckerApplication.class, args);
  }
}
