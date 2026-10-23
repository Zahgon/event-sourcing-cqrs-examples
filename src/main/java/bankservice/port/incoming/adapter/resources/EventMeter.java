package bankservice.port.incoming.adapter.resources;

import java.util.concurrent.TimeUnit;

/**
 * A meter with the semantics of Dropwizard Metrics' {@code Meter}: a count, the mean rate since
 * creation and exponentially weighted one, five and fifteen minute rates that advance in five
 * second ticks. Rates are events per second.
 */
final class EventMeter {

  private static final long TICK_NANOS = TimeUnit.SECONDS.toNanos(5);
  private static final double TICK_SECONDS = 5.0;

  private final long start = System.nanoTime();
  private final MovingAverage oneMinute = new MovingAverage(1);
  private final MovingAverage fiveMinutes = new MovingAverage(5);
  private final MovingAverage fifteenMinutes = new MovingAverage(15);
  private long count;
  private long uncounted;
  private long lastTick = start;

  synchronized void mark() {
    tickIfNecessary();
    count++;
    uncounted++;
  }

  synchronized Reading read() {
    tickIfNecessary();
    long elapsed = System.nanoTime() - start;
    double mean = count == 0 ? 0.0 : count / (elapsed / (double) TimeUnit.SECONDS.toNanos(1));
    return new Reading(count, mean, oneMinute.rate, fiveMinutes.rate, fifteenMinutes.rate);
  }

  private void tickIfNecessary() {
    long now = System.nanoTime();
    long ticks = (now - lastTick) / TICK_NANOS;
    if (ticks <= 0) {
      return;
    }
    lastTick += ticks * TICK_NANOS;
    for (long i = 0; i < ticks; i++) {
      oneMinute.tick(uncounted);
      fiveMinutes.tick(uncounted);
      fifteenMinutes.tick(uncounted);
      uncounted = 0;
    }
  }

  record Reading(long count, double meanRate, double oneMinuteRate, double fiveMinuteRate,
      double fifteenMinuteRate) {
  }

  private static final class MovingAverage {

    private final double alpha;
    private boolean initialized;
    private double rate;

    private MovingAverage(int minutes) {
      this.alpha = 1 - Math.exp(-TICK_SECONDS / 60.0 / minutes);
    }

    private void tick(long events) {
      double instantRate = events / TICK_SECONDS;
      if (initialized) {
        rate += alpha * (instantRate - rate);
      } else {
        rate = instantRate;
        initialized = true;
      }
    }
  }
}
