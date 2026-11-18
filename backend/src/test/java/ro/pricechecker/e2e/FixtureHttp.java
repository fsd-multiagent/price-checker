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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import ro.pricechecker.adapter.RetailerHttp;

/**
 * End-to-end retailer HTTP stub (story 2.14, T-3): never touches the network. It answers only the
 * URLs it was given (the e2e known-EANs file) and throws for any other. A known URL with a page
 * returns that page; one without a page hangs until {@code hang} passes, then throws, so the Lookup
 * deadline is what ends the row. {@link #release()} ends the hangs in progress at once, and {@link
 * #close()} (called by Spring at context shutdown) ends them and every later one, so no thread
 * stays blocked for the whole hang. Page requests are counted per EAN (the URL's last path
 * segment).
 */
public final class FixtureHttp implements RetailerHttp, AutoCloseable {

  private final Set<URI> known;
  private final Function<URI, Optional<String>> pages;
  private final Duration hang;
  private final AtomicReference<CountDownLatch> hangs =
      new AtomicReference<>(new CountDownLatch(1));
  private final AtomicBoolean closed = new AtomicBoolean();
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
    // Take the latch before checking `closed`: a close() after this point counts this latch down.
    CountDownLatch latch = hangs.get();
    if (closed.get()) {
      throw new IOException("e2e stub closed");
    }
    try {
      // Bounded wait: ends after `hang`, or earlier on release() or close().
      boolean released = latch.await(hang.toMillis(), TimeUnit.MILLISECONDS);
      throw new IOException("e2e stub hang ended, released=" + released);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IOException("e2e stub hang interrupted", e);
    }
  }

  /** Ends every hang in progress now; later requests without a page hang again. */
  public void release() {
    hangs.getAndSet(new CountDownLatch(1)).countDown();
  }

  /** Ends every hang in progress and makes later ones fail at once, so shutdown never waits. */
  @Override
  public void close() {
    closed.set(true);
    release();
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
