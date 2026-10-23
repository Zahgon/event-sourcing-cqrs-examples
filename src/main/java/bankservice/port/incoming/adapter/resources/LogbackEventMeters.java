package bankservice.port.incoming.adapter.resources;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.UnsynchronizedAppenderBase;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;
import org.slf4j.LoggerFactory;

/**
 * The instrumented appender Dropwizard attached to the root logger: one meter for every logging
 * event that reaches the root logger's appenders and one per level, published in the admin metric
 * registry as {@code ch.qos.logback.core.Appender.<all|trace|debug|info|warn|error>}.
 */
public class LogbackEventMeters extends UnsynchronizedAppenderBase<ILoggingEvent>
    implements AutoCloseable {

  private static final String PREFIX = "ch.qos.logback.core.Appender.";

  private final EventMeter all = new EventMeter();
  private final EventMeter trace = new EventMeter();
  private final EventMeter debug = new EventMeter();
  private final EventMeter info = new EventMeter();
  private final EventMeter warn = new EventMeter();
  private final EventMeter error = new EventMeter();

  /**
   * Starts the appender and attaches it to the root logger of the running Logback context.
   */
  public static LogbackEventMeters attachToRootLogger() {
    LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
    LogbackEventMeters meters = new LogbackEventMeters();
    meters.setName(PREFIX.substring(0, PREFIX.length() - 1));
    meters.setContext(context);
    meters.start();
    context.getLogger(Logger.ROOT_LOGGER_NAME).addAppender(meters);
    return meters;
  }

  /**
   * Detaches the appender from the root logger and stops it.
   */
  @Override
  public void close() {
    ((LoggerContext) getContext()).getLogger(Logger.ROOT_LOGGER_NAME).detachAppender(this);
    stop();
  }

  @Override
  protected void append(ILoggingEvent event) {
    all.mark();
    switch (event.getLevel().toInt()) {
      case Level.TRACE_INT -> trace.mark();
      case Level.DEBUG_INT -> debug.mark();
      case Level.INFO_INT -> info.mark();
      case Level.WARN_INT -> warn.mark();
      case Level.ERROR_INT -> error.mark();
      default -> {
        // OFF and ALL are thresholds, never the level of an event.
      }
    }
  }

  /**
   * The meters in the registry's JSON shape, keyed by metric name in ascending order.
   */
  public SortedMap<String, Map<String, Object>> snapshot() {
    SortedMap<String, Map<String, Object>> meters = new TreeMap<>();
    meters.put(PREFIX + "all", shape(all));
    meters.put(PREFIX + "trace", shape(trace));
    meters.put(PREFIX + "debug", shape(debug));
    meters.put(PREFIX + "info", shape(info));
    meters.put(PREFIX + "warn", shape(warn));
    meters.put(PREFIX + "error", shape(error));
    return meters;
  }

  private static Map<String, Object> shape(EventMeter meter) {
    EventMeter.Reading reading = meter.read();
    Map<String, Object> shape = new LinkedHashMap<>();
    shape.put("count", reading.count());
    shape.put("m15_rate", reading.fifteenMinuteRate());
    shape.put("m1_rate", reading.oneMinuteRate());
    shape.put("m5_rate", reading.fiveMinuteRate());
    shape.put("mean_rate", reading.meanRate());
    shape.put("units", "events/second");
    return shape;
  }
}
