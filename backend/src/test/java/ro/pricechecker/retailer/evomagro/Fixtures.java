package ro.pricechecker.retailer.evomagro;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import ro.pricechecker.adapter.RetailerHttp;

/**
 * Reads only {@code fixtures/evomag.ro/} (T-2, T-3); tests run with working dir {@code backend/}.
 */
final class Fixtures {

  static final Path DIR = Path.of("..", "fixtures", EvomagAdapter.RETAILER_ID);

  private Fixtures() {}

  static String read(String name) {
    try {
      return Files.readString(DIR.resolve(name));
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /** A fake {@link RetailerHttp} that serves one fixture file and records every requested URI. */
  static final class FixtureHttp implements RetailerHttp {

    private final String fixture;
    final List<URI> requested = new ArrayList<>();

    FixtureHttp(String fixture) {
      this.fixture = fixture;
    }

    @Override
    public String get(URI url) {
      requested.add(url);
      return read(fixture);
    }
  }
}
