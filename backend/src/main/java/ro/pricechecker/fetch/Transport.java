package ro.pricechecker.fetch;

import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.util.Optional;

/**
 * One GET, nothing more: no redirect following, no cookies, no retries. {@link FetchClient} decides
 * everything else; this seam lets tests use a stub instead of the network (T-3).
 */
public interface Transport {

  /** Status, {@code Location} header (if any) and body of one response. */
  record Response(int status, Optional<String> location, String body) {}

  /**
   * Sends one GET with the given user agent and timeout.
   *
   * @throws java.net.http.HttpTimeoutException when the timeout passes
   * @throws IOException on any other transport failure
   */
  Response get(URI url, String userAgent, Duration timeout)
      throws IOException, InterruptedException;
}
