package bankservice.port.incoming.adapter.resources;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The request instrumentation Dropwizard's instrumented handler kept for the application
 * connector: a timer for all requests, for dispatches and for each method, a meter per status
 * class, the active request counters and the 4xx and 5xx ratio gauges, published in the admin
 * metric registry under {@code bankservice.http.}.
 */
public class RequestMetrics {

  private static final String PREFIX = "bankservice.http.";
  private static final Set<String> METHODS =
      Set.of("connect", "delete", "get", "head", "move", "options", "post", "put", "trace");

  private final EventTimer requests = new EventTimer();
  private final EventTimer dispatches = new EventTimer();
  private final Map<String, EventTimer> methodTimers = new TreeMap<>();
  private final EventMeter[] statusClasses = new EventMeter[5];
  private final EventMeter asyncDispatches = new EventMeter();
  private final EventMeter asyncTimeouts = new EventMeter();
  private final AtomicLong activeRequests = new AtomicLong();
  private final AtomicLong activeDispatches = new AtomicLong();
  private final AtomicLong activeSuspended = new AtomicLong();

  public RequestMetrics() {
    for (String method : METHODS) {
      methodTimers.put(method, new EventTimer());
    }
    methodTimers.put("other", new EventTimer());
    Arrays.setAll(statusClasses, i -> new EventMeter());
  }

  void started() {
    activeRequests.incrementAndGet();
    activeDispatches.incrementAndGet();
  }

  void completed(String method, int status, long nanos) {
    activeDispatches.decrementAndGet();
    activeRequests.decrementAndGet();
    requests.update(nanos);
    dispatches.update(nanos);
    String name = method.toLowerCase(Locale.ROOT);
    methodTimers.get(METHODS.contains(name) ? name : "other").update(nanos);
    int statusClass = status / 100;
    if (statusClass >= 1 && statusClass <= 5) {
      statusClasses[statusClass - 1].mark();
    }
  }

  /**
   * Adds the ratio gauges to the registry's gauges.
   */
  public void addGauges(Map<String, Object> gauges) {
    EventMeter.Reading total = requests.meter.read();
    EventMeter.Reading clientErrors = statusClasses[3].read();
    final EventMeter.Reading serverErrors = statusClasses[4].read();
    ratio(gauges, "percent-4xx-1m", clientErrors.oneMinuteRate(), total.oneMinuteRate());
    ratio(gauges, "percent-4xx-5m", clientErrors.fiveMinuteRate(), total.fiveMinuteRate());
    ratio(gauges, "percent-4xx-15m", clientErrors.fifteenMinuteRate(),
        total.fifteenMinuteRate());
    ratio(gauges, "percent-5xx-1m", serverErrors.oneMinuteRate(), total.oneMinuteRate());
    ratio(gauges, "percent-5xx-5m", serverErrors.fiveMinuteRate(), total.fiveMinuteRate());
    ratio(gauges, "percent-5xx-15m", serverErrors.fifteenMinuteRate(),
        total.fifteenMinuteRate());
  }

  public SortedMap<String, Object> counters() {
    SortedMap<String, Object> counters = new TreeMap<>();
    counters.put(PREFIX + "active-dispatches", Map.of("count", activeDispatches.get()));
    counters.put(PREFIX + "active-requests", Map.of("count", activeRequests.get()));
    counters.put(PREFIX + "active-suspended", Map.of("count", activeSuspended.get()));
    return counters;
  }

  /**
   * Adds the status-class and async meters to the registry's meters.
   */
  public void addMeters(Map<String, Object> meters) {
    for (int i = 0; i < statusClasses.length; i++) {
      meters.put(PREFIX + (i + 1) + "xx-responses", meterShape(statusClasses[i].read()));
    }
    meters.put(PREFIX + "async-dispatches", meterShape(asyncDispatches.read()));
    meters.put(PREFIX + "async-timeouts", meterShape(asyncTimeouts.read()));
  }

  public SortedMap<String, Object> timers() {
    SortedMap<String, Object> timers = new TreeMap<>();
    timers.put(PREFIX + "requests", requests.shape());
    timers.put(PREFIX + "dispatches", dispatches.shape());
    for (Map.Entry<String, EventTimer> timer : methodTimers.entrySet()) {
      timers.put(PREFIX + timer.getKey() + "-requests", timer.getValue().shape());
    }
    return timers;
  }

  private static void ratio(Map<String, Object> gauges, String name, double part, double whole) {
    double value = whole == 0 || Double.isNaN(whole) || Double.isInfinite(whole)
        ? Double.NaN : part / whole;
    gauges.put(PREFIX + name, Map.of("value", value));
  }

  static Map<String, Object> meterShape(EventMeter.Reading reading) {
    Map<String, Object> shape = new LinkedHashMap<>();
    shape.put("count", reading.count());
    shape.put("m15_rate", reading.fifteenMinuteRate());
    shape.put("m1_rate", reading.oneMinuteRate());
    shape.put("m5_rate", reading.fiveMinuteRate());
    shape.put("mean_rate", reading.meanRate());
    shape.put("units", "events/second");
    return shape;
  }

  /**
   * A Dropwizard timer: a meter of the calls plus the distribution of the most recent durations,
   * reported in seconds.
   */
  private static final class EventTimer {

    private static final int RESERVOIR_SIZE = 1028;
    private static final double NANOS_PER_SECOND = TimeUnit.SECONDS.toNanos(1);

    private final EventMeter meter = new EventMeter();
    private final long[] durations = new long[RESERVOIR_SIZE];
    private long recorded;

    void update(long nanos) {
      synchronized (this) {
        durations[(int) (recorded % RESERVOIR_SIZE)] = nanos;
        recorded++;
      }
      meter.mark();
    }

    Map<String, Object> shape() {
      long[] values;
      synchronized (this) {
        values = Arrays.copyOf(durations, (int) Math.min(recorded, RESERVOIR_SIZE));
      }
      Arrays.sort(values);
      EventMeter.Reading reading = meter.read();
      Map<String, Object> shape = new LinkedHashMap<>();
      shape.put("count", reading.count());
      shape.put("max", values.length == 0 ? 0.0 : seconds(values[values.length - 1]));
      shape.put("mean", seconds(mean(values)));
      shape.put("min", values.length == 0 ? 0.0 : seconds(values[0]));
      shape.put("p50", seconds(quantile(values, 0.5)));
      shape.put("p75", seconds(quantile(values, 0.75)));
      shape.put("p95", seconds(quantile(values, 0.95)));
      shape.put("p98", seconds(quantile(values, 0.98)));
      shape.put("p99", seconds(quantile(values, 0.99)));
      shape.put("p999", seconds(quantile(values, 0.999)));
      shape.put("stddev", seconds(stdDev(values)));
      shape.put("m15_rate", reading.fifteenMinuteRate());
      shape.put("m1_rate", reading.oneMinuteRate());
      shape.put("m5_rate", reading.fiveMinuteRate());
      shape.put("mean_rate", reading.meanRate());
      shape.put("duration_units", "seconds");
      shape.put("rate_units", "calls/second");
      return shape;
    }

    private static double seconds(double nanos) {
      return nanos / NANOS_PER_SECOND;
    }

    private static double mean(long[] values) {
      if (values.length == 0) {
        return 0;
      }
      double sum = 0;
      for (long value : values) {
        sum += value;
      }
      return sum / values.length;
    }

    /**
     * The quantile as Dropwizard's uniform snapshot interpolates it.
     */
    private static double quantile(long[] values, double quantile) {
      if (values.length == 0) {
        return 0;
      }
      double position = quantile * (values.length + 1);
      if (position < 1) {
        return values[0];
      }
      if (position >= values.length) {
        return values[values.length - 1];
      }
      int index = (int) position;
      double lower = values[index - 1];
      double upper = values[index];
      return lower + (position - Math.floor(position)) * (upper - lower);
    }

    private static double stdDev(long[] values) {
      if (values.length <= 1) {
        return 0;
      }
      double mean = mean(values);
      double sum = 0;
      for (long value : values) {
        sum += (value - mean) * (value - mean);
      }
      return Math.sqrt(sum / (values.length - 1));
    }
  }
}
