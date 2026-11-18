package ro.pricechecker.fetch;

import java.time.Duration;

/** Injected wait (T-5), so tests record waits instead of sleeping. */
@FunctionalInterface
public interface Sleeper {

  /** Waits by {@link Thread#sleep(Duration)}. */
  Sleeper SYSTEM = Thread::sleep;

  void sleep(Duration duration) throws InterruptedException;
}
