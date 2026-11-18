package ro.pricechecker.fetch;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * {@link Transport} over {@code java.net.http} (AD-1: the only HTTP client, in {@code fetch}).
 * Redirects are never followed by the JDK client, no cookie handler is set, no proxy is used (S-6);
 * the only header the application adds is {@code User-Agent}.
 */
public final class JdkTransport implements Transport {

  private final HttpClient client;

  public JdkTransport() {
    this.client =
        HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER)
            .proxy(HttpClient.Builder.NO_PROXY)
            .version(HttpClient.Version.HTTP_1_1)
            .build();
  }

  @Override
  public Response get(URI url, String userAgent, Duration timeout)
      throws IOException, InterruptedException {
    HttpRequest request =
        HttpRequest.newBuilder(url).GET().timeout(timeout).header("User-Agent", userAgent).build();
    // HttpRequest.timeout bounds only the wait for headers; the whole exchange, body included,
    // must end inside the deadline (AC-N3.4).
    CompletableFuture<HttpResponse<String>> pending =
        client.sendAsync(request, HttpResponse.BodyHandlers.ofString());
    HttpResponse<String> response;
    try {
      response = pending.get(timeout.toNanos(), TimeUnit.NANOSECONDS);
    } catch (TimeoutException e) {
      pending.cancel(true);
      throw new HttpTimeoutException("response not complete within " + timeout);
    } catch (InterruptedException e) {
      pending.cancel(true);
      throw e;
    } catch (ExecutionException e) {
      if (e.getCause() instanceof IOException io) {
        throw io;
      }
      throw new IOException(e.getCause());
    }
    return new Response(
        response.statusCode(), response.headers().firstValue("Location"), response.body());
  }
}
