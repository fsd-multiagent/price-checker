package ro.pricechecker.fetch;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import ro.pricechecker.registry.MutableClock;

/** A {@link Sleeper} that records each wait and moves the test clock instead of sleeping (T-5). */
final class RecordingSleeper implements Sleeper {

  final List<Duration> sleeps = new ArrayList<>();
  private final MutableClock clock;

  RecordingSleeper(MutableClock clock) {
    this.clock = clock;
  }

  @Override
  public void sleep(Duration duration) {
    sleeps.add(duration);
    clock.advance(duration);
  }
}
