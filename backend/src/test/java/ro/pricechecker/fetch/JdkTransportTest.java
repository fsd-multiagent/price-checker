package ro.pricechecker.fetch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** The JDK transport against a loopback {@link HttpServer}; never a retailer (T-3). */
@Tag("AC-2.4.5")
class JdkTransportTest {

  private static final String UA = "price-checker/1.0 (+https://contact.example/about)";

  private final CopyOnWriteArrayList<Headers> seen = new CopyOnWriteArrayList<>();
  private final CountDownLatch release = new CountDownLatch(1);
  private HttpServer server;
  private final JdkTransport transport = new JdkTransport();

  @BeforeEach
  void start() throws IOException {
    server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
    server.createContext(
        "/page",
        exchange -> {
          seen.add(exchange.getRequestHeaders());
          byte[] body = "<html>ok</html>".getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().add("Set-Cookie", "session=1");
          exchange.getResponseHeaders().add("Content-Type", "text/html; charset=utf-8");
          exchange.sendResponseHeaders(200, body.length);
          try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
          }
        });
    server.createContext(
        "/moved",
        exchange -> {
          seen.add(exchange.getRequestHeaders());
          exchange.getResponseHeaders().add("Location", "/page");
          exchange.sendResponseHeaders(301, -1);
          exchange.close();
        });
    server.createContext(
        "/slow",
        exchange -> {
          try {
            release.await();
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
          }
          exchange.sendResponseHeaders(204, -1);
          exchange.close();
        });
    server.start();
  }

  @AfterEach
  void stop() {
    release.countDown();
    server.stop(0);
  }

  private URI url(String path) {
    return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + path);
  }

  @Test
  void ac_2_4_5_sendsTheUserAgentAndNoCookie() throws Exception {
    Transport.Response first = transport.get(url("/page"), UA, Duration.ofSeconds(5));
    transport.get(url("/page"), UA, Duration.ofSeconds(5));

    assertThat(first.status()).isEqualTo(200);
    assertThat(first.body()).isEqualTo("<html>ok</html>");
    assertThat(seen).hasSize(2);
    for (Headers headers : seen) {
      assertThat(headers.get("User-Agent")).containsExactly(UA);
      // The server set a cookie on the first response; it must never come back.
      assertThat(headers.containsKey("Cookie")).isFalse();
      assertThat(headers.containsKey("Authorization")).isFalse();
    }
  }

  @Test
  void ac_2_4_5_doesNotFollowRedirects() throws Exception {
    Transport.Response response = transport.get(url("/moved"), UA, Duration.ofSeconds(5));

    assertThat(response.status()).isEqualTo(301);
    assertThat(response.location()).contains("/page");
    assertThat(seen).hasSize(1);
  }

  @Test
  void ac_2_4_5_timeoutIsAnHttpTimeoutException() {
    assertThatThrownBy(() -> transport.get(url("/slow"), UA, Duration.ofMillis(200)))
        .isInstanceOf(HttpTimeoutException.class);
  }
}
