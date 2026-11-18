package ro.pricechecker.fetch;

import java.net.URI;
import java.time.Instant;

/** A page read by the fetch client: the final URL (after redirects), the body and when (T-5). */
public record FetchedPage(URI url, String body, Instant fetchedAt) {}
