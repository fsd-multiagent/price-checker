package ro.pricechecker.lookup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.groups.Tuple.tuple;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ro.pricechecker.adapter.AdapterOutcome;
import ro.pricechecker.adapter.Availability;
import ro.pricechecker.adapter.PageFacts;
import ro.pricechecker.adapter.RetailerAdapter;
import ro.pricechecker.adapter.RetailerHttp;
import ro.pricechecker.adapter.RetailerHttpBinder;
import ro.pricechecker.cache.ResultCache;
import ro.pricechecker.ean.Ean;
import ro.pricechecker.fetch.FetchClient;
import ro.pricechecker.fetch.Transport;
import ro.pricechecker.fetch.UserAgent;
import ro.pricechecker.registry.BlockStore;
import ro.pricechecker.registry.Registry;
import ro.pricechecker.registry.RuntimeSwitch;
import ro.pricechecker.registry.TestRegistries;

/**
 * Issue #102: a Lookup loads each row through the shared {@link FetchClient} bound to that row's
 * retailer (AD-6), after every AD-10 check. Pages come from a fake {@link Transport} on {@code
 * *.example} hosts only (T-2, T-3); time is a test clock the sleeper advances (T-5).
 */
class LookupFetchWiringTest {

  private static final UserAgent UA = UserAgent.build("1.0", "https://contact.example/about");
  private static final String PAGE = "<html>price 123.45 RON</html>";
  private static final long PRICE = 12345L;

  @TempDir Path dir;

  private final TestClock clock = new TestClock(Instant.parse("2026-11-18T10:00:00Z"));
  private final Registry registry = TestRegistries.twoEnabled(4000);
  private final FakeTransport transport = new FakeTransport();
  private final List<String> bindCalls = new CopyOnWriteArrayList<>();
  private final Map<String, RetailerHttp> boundById = new ConcurrentHashMap<>();
  private final Map<String, RetailerHttp> receivedById = new ConcurrentHashMap<>();

  @Test
  @Tag("AC-0.102.1")
  void ac_0_102_1_rowIsLoadedThroughTheFetchClient() throws IOException {
    useRuntime("a.example: {fetching: allowed}");
    LookupService service = service(recordingBinder());

    List<LookupRow> rows = service.lookup(Ean.parse(TestRegistries.EAN_A)).rows();

    LookupRow a = row(rows, "a.example");
    assertThat(a.status()).isEqualTo(RowStatus.FOUND);
    assertThat(a.priceMinor()).isEqualTo(PRICE);
    assertThat(row(rows, "b.example").status()).isEqualTo(RowStatus.DISABLED);
    assertThat(transport.urls).contains(URI.create("https://a.example/p/1"));
    assertThat(transport.urls).allMatch(u -> u.getHost().equals("a.example"));
  }

  @Test
  @Tag("AC-0.102.2")
  void ac_0_102_2_eachRetailerGetsTheClientBoundToItsOwnId() throws IOException {
    useRuntime("a.example: {fetching: allowed}", "b.example: {fetching: allowed}");
    LookupService service = service(recordingBinder());

    List<LookupRow> rows = service.lookup(Ean.parse(TestRegistries.EAN_A)).rows();

    assertThat(rows)
        .extracting(LookupRow::retailerId, LookupRow::status)
        .containsExactlyInAnyOrder(
            tuple("a.example", RowStatus.FOUND), tuple("b.example", RowStatus.FOUND));
    assertThat(bindCalls).containsExactly("a.example", "b.example");
    for (String id : List.of("a.example", "b.example")) {
      assertThat(receivedById.get(id)).isSameAs(boundById.get(id));
      assertThat(((FetchClient.BoundHttp) receivedById.get(id)).retailerId()).isEqualTo(id);
    }
    assertThat(transport.urls)
        .contains(URI.create("https://a.example/p/1"), URI.create("https://b.example/p/1"));
  }

  @Test
  @Tag("AC-0.102.3")
  void ac_0_102_3_disabledAndBlockedRowsNeverBind() throws IOException {
    useRuntime(
        "a.example: {fetching: allowed, runnerBlock: true}", "b.example: {fetching: stopped}");
    LookupService service = service(recordingBinder());

    List<LookupRow> rows = service.lookup(Ean.parse(TestRegistries.EAN_A)).rows();

    assertThat(row(rows, "a.example").status()).isEqualTo(RowStatus.UNAVAILABLE);
    assertThat(row(rows, "b.example").status()).isEqualTo(RowStatus.DISABLED);
    assertThat(bindCalls).isEmpty();
    assertThat(transport.urls).isEmpty();
  }

  @Test
  @Tag("AC-0.102.3")
  void ac_0_102_3_unknownEanNeverBinds() throws IOException {
    useRuntime("a.example: {fetching: allowed}", "b.example: {fetching: allowed}");
    LookupService service = service(recordingBinder());

    List<LookupRow> rows = service.lookup(Ean.parse("5901234123457")).rows();

    assertThat(rows).extracting(LookupRow::status).containsOnly(RowStatus.NOT_FOUND).hasSize(2);
    assertThat(bindCalls).isEmpty();
    assertThat(transport.urls).isEmpty();
  }

  @Test
  @Tag("AC-0.102.4")
  void ac_0_102_4_noBinderMakesLoadableRowsUnavailable() throws IOException {
    useRuntime("a.example: {fetching: allowed}", "b.example: {fetching: allowed}");
    LookupService service = service(Optional.empty());

    List<LookupRow> rows = service.lookup(Ean.parse(TestRegistries.EAN_A)).rows();

    assertThat(rows).extracting(LookupRow::status).containsOnly(RowStatus.UNAVAILABLE).hasSize(2);
    assertThat(receivedById).isEmpty();
    assertThat(transport.urls).isEmpty();
  }

  @Test
  @Tag("AC-0.102.2")
  void ac_0_102_2_bindFailureStaysInItsRow() throws IOException {
    useRuntime("a.example: {fetching: allowed}", "b.example: {fetching: allowed}");
    RetailerHttpBinder failsForA =
        id -> {
          if (id.equals("a.example")) {
            throw new IllegalArgumentException("no client");
          }
          return fetchClient().bind(id);
        };
    LookupService service = service(Optional.of(failsForA));

    List<LookupRow> rows = service.lookup(Ean.parse(TestRegistries.EAN_A)).rows();

    assertThat(row(rows, "a.example").status()).isEqualTo(RowStatus.UNAVAILABLE);
    assertThat(row(rows, "b.example").status()).isEqualTo(RowStatus.FOUND);
  }

  private Optional<RetailerHttpBinder> recordingBinder() {
    FetchClient client = fetchClient();
    return Optional.of(id -> {
      bindCalls.add(id);
      RetailerHttp bound = client.bind(id);
      boundById.put(id, bound);
      return bound;
    });
  }

  /** The client shares the Lookup's runtime switch and Block store, as in the application. */
  private FetchClient fetchClient() {
    return new FetchClient(registry, runtime, blocks, transport, clock, clock::advance, UA);
  }

  private LookupService service(Optional<RetailerHttpBinder> binder) {
    List<RetailerAdapter> adapters =
        List.of(adapter("a.example"), adapter("b.example"), adapter("c.example"));
    return new LookupService(
        registry,
        new AdapterCatalog(adapters, registry),
        binder,
        clock,
        runtime,
        blocks,
        new ResultCache<>(Duration.ofMinutes(15), clock),
        new LookupLog(clock));
  }

  private RuntimeSwitch runtime;
  private BlockStore blocks;

  /** Writes {@code runtime.yaml} with the given entries; the Block store reads it too. */
  private void useRuntime(String... entries) throws IOException {
    StringBuilder yaml = new StringBuilder("retailers:\n");
    for (String entry : entries) {
      yaml.append("  ").append(entry).append('\n');
    }
    Path file = dir.resolve("runtime.yaml");
    Files.writeString(file, yaml.toString());
    runtime = new RuntimeSwitch(file, clock);
    blocks = new BlockStore(dir.resolve("blocks.json"), runtime, clock, (retailer, reason) -> {});
  }

  /** Reads the product page through the client it was given; FOUND when the page is served. */
  private RetailerAdapter adapter(String id) {
    return new RetailerAdapter() {
      @Override
      public String retailerId() {
        return id;
      }

      @Override
      public AdapterOutcome fetchAndExtract(Ean ean, URI productUrl, RetailerHttp http) {
        receivedById.put(id, http);
        try {
          String body = http.get(productUrl);
          return PAGE.equals(body)
              ? new AdapterOutcome.Found(
                  new PageFacts(PRICE, "RON", Availability.IN_STOCK, null, null))
              : new AdapterOutcome.NotFound();
        } catch (IOException e) {
          return new AdapterOutcome.NotFound();
        }
      }
    };
  }

  private static LookupRow row(List<LookupRow> rows, String id) {
    return rows.stream().filter(r -> r.retailerId().equals(id)).findFirst().orElseThrow();
  }

  /** robots.txt is 404 (allow all); every other path serves {@link #PAGE}. Never the network. */
  private static final class FakeTransport implements Transport {

    final List<URI> urls = new CopyOnWriteArrayList<>();

    @Override
    public Response get(URI url, String userAgent, Duration timeout) {
      urls.add(url);
      if (url.getPath().equals("/robots.txt")) {
        return new Response(404, Optional.empty(), "");
      }
      return new Response(200, Optional.empty(), PAGE);
    }
  }

  /** A test clock that is safe to advance from the load threads (T-5). */
  private static final class TestClock extends Clock {

    private final AtomicReference<Instant> now;

    TestClock(Instant start) {
      now = new AtomicReference<>(start);
    }

    void advance(Duration by) {
      now.updateAndGet(t -> t.plus(by));
    }

    @Override
    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
      throw new UnsupportedOperationException();
    }

    @Override
    public Instant instant() {
      return now.get();
    }
  }
}
