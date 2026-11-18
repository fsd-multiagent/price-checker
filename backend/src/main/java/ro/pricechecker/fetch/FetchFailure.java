package ro.pricechecker.fetch;

import java.io.IOException;
import java.time.Instant;
import ro.pricechecker.adapter.AdapterOutcome;
import ro.pricechecker.adapter.UnavailableReason;

/**
 * Why a fetch produced no page, as the {@link AdapterOutcome} the adapter returns (AD-6 outcome
 * table): {@code catch (FetchFailure f) { return f.outcome(); }}. The message holds only the
 * outcome, never page content or a URL (AD-14).
 */
public final class FetchFailure extends IOException {

  private static final long serialVersionUID = 1L;

  private final transient AdapterOutcome outcome;
  private final Instant fetchedAt;

  private FetchFailure(AdapterOutcome outcome, Instant fetchedAt) {
    super(outcome.toString());
    this.outcome = outcome;
    this.fetchedAt = fetchedAt;
  }

  static FetchFailure notFound(Instant fetchedAt) {
    return new FetchFailure(new AdapterOutcome.NotFound(), fetchedAt);
  }

  static FetchFailure unavailable(UnavailableReason reason, Instant at) {
    return new FetchFailure(new AdapterOutcome.Unavailable(reason), at);
  }

  public AdapterOutcome outcome() {
    return outcome;
  }

  /** When the outcome was decided, from the injected clock (T-5). */
  public Instant fetchedAt() {
    return fetchedAt;
  }
}
