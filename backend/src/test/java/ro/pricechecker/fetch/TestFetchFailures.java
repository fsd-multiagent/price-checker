package ro.pricechecker.fetch;

import java.time.Instant;
import ro.pricechecker.adapter.UnavailableReason;

/** Builds {@link FetchFailure}s for adapter tests outside {@code fetch} (T-3). */
public final class TestFetchFailures {

  private static final Instant AT = Instant.parse("2026-01-01T00:00:00Z");

  private TestFetchFailures() {}

  public static FetchFailure notFound() {
    return FetchFailure.notFound(AT);
  }

  public static FetchFailure unavailable(UnavailableReason reason) {
    return FetchFailure.unavailable(reason, AT);
  }
}
