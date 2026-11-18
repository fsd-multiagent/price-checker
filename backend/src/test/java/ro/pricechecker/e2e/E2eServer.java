package ro.pricechecker.e2e;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.SpringApplication;
import ro.pricechecker.PriceCheckerApplication;
import ro.pricechecker.registry.RetailerConfig;

/**
 * End-to-end backend (story 2.14, T-4): the real app with the e2e register and the {@link
 * FixtureHttp} stub as the only retailer HTTP. Run with {@code backend/gradlew -p backend
 * bootTestRun}; port from {@code E2E_BACKEND_PORT} (default 18080). The Kill switch and Block files
 * live in a fresh temporary directory with {@code fetching: allowed} for every e2e retailer.
 */
public final class E2eServer {

  private E2eServer() {}

  public static void main(String[] args) throws IOException {
    Path dir = Files.createTempDirectory("price-checker-e2e");
    StringBuilder runtime = new StringBuilder("retailers:\n");
    for (RetailerConfig retailer : E2eBeans.registry().retailers()) {
      runtime.append("  ").append(retailer.domain()).append(": {fetching: allowed}\n");
    }
    Path runtimeFile = Files.writeString(dir.resolve("runtime.yaml"), runtime.toString());
    String port = System.getenv().getOrDefault("E2E_BACKEND_PORT", "18080");

    List<String> all = new ArrayList<>(List.of(args));
    all.add("--server.port=" + port);
    all.add("--price-checker.runtime-file=" + runtimeFile);
    all.add("--price-checker.blocks-file=" + dir.resolve("blocks.json"));
    SpringApplication.from(PriceCheckerApplication::main)
        .with(E2eBeans.class)
        .run(all.toArray(String[]::new));
  }
}
