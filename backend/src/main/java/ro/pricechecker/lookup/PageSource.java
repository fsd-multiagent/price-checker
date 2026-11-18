package ro.pricechecker.lookup;

import java.io.IOException;
import java.net.URI;

/**
 * Where a {@link KnownEanLoad} gets its pages: the {@code health} runner passes a {@code
 * FetchClient} binding, so it needs no {@code adapter} type (ADR 0006, story 3.5).
 */
@FunctionalInterface
public interface PageSource {

  /** Returns the body of a GET to {@code url}; failures as the fetch client throws them. */
  String get(URI url) throws IOException;
}
