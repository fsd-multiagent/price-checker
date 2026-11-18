package ro.pricechecker.e2e;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import ro.pricechecker.adapter.RetailerHttp;

/**
 * End-to-end retailer HTTP stub (story 2.14, T-3): never touches the network. It answers only the
 * URLs it was given (the e2e known-EANs file) and throws for any other. A known URL with a page
 * returns that page; one without a page hangs until {@code hang} passes, then throws, so the Lookup
 * deadline is what ends the row. Page requests are counted per EAN (the URL's last path segment).
 */
public final class FixtureHttp implements RetailerHttp {

  private final Set<URI> known;
  private final Function<URI, Optional<String>> pages;
  private final Duration hang;
  private final CountDownLatch never = new CountDownLatch(1);
  private final ConcurrentHashMap<String, AtomicInteger> requests = new ConcurrentHashMap<>();

  public FixtureHttp(Set<URI> known, Function<URI, Optional<String>> pages, Duration hang) {
    this.known = Set.copyOf(known);
    this.pages = pages;
    this.hang = hang;
  }

  /** Pages from the classpath at {@code e2e/pages/<host>/<last path segment>.html}. */
  public static Optional<String> classpathPage(URI url) {
    String resource = "e2e/pages/" + url.getHost() + "/" + lastSegment(url) + ".html";
    try (InputStream in = FixtureHttp.class.getClassLoader().getResourceAsStream(resource)) {
      return in == null
          ? Optional.empty()
          : Optional.of(new String(in.readAllBytes(), StandardCharsets.UTF_8));
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  @Override
  public String get(URI url) throws IOException {
    if (!known.contains(url)) {
      throw new IOException("not an e2e known-EAN URL");
    }
    requests.computeIfAbsent(lastSegment(url), k -> new AtomicInteger()).incrementAndGet();
    Optional<String> page = pages.apply(url);
    if (page.isPresent()) {
      return page.get();
    }
    try {
      // Bounded wait: the thread is released after `hang` even though nobody counts the latch down.
      boolean released = never.await(hang.toMillis(), TimeUnit.MILLISECONDS);
      throw new IOException("e2e stub hang ended, released=" + released);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IOException("e2e stub hang interrupted", e);
    }
  }

  /** Page requests seen so far for {@code ean}. */
  public int requests(String ean) {
    AtomicInteger count = requests.get(ean);
    return count == null ? 0 : count.get();
  }

  private static String lastSegment(URI url) {
    String path = url.getPath();
    return path.substring(path.lastIndexOf('/') + 1);
  }
}
