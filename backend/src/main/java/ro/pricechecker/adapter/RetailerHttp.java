package ro.pricechecker.adapter;

import java.io.IOException;
import java.net.URI;

/** Minimal GET port handed to adapters; the shared fetch client implements it (AD-6, AD-7). */
public interface RetailerHttp {

  /** Returns the body of a GET to {@code url}. */
  String get(URI url) throws IOException;
}
