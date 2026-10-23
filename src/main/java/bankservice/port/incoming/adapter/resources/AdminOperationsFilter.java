package bankservice.port.incoming.adapter.resources;

import static com.google.common.base.Preconditions.checkNotNull;
import static java.lang.String.format;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.management.BufferPoolMXBean;
import java.lang.management.ClassLoadingMXBean;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.LockInfo;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryPoolMXBean;
import java.lang.management.MemoryUsage;
import java.lang.management.MonitorInfo;
import java.lang.management.OperatingSystemMXBean;
import java.lang.management.RuntimeMXBean;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.SortedMap;
import java.util.Timer;
import java.util.TimerTask;
import java.util.TreeMap;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Serves the operational endpoints of the Dropwizard admin servlet on the management port: the
 * menu page, {@code /ping}, {@code /healthcheck}, {@code /threads}, {@code /metrics},
 * {@code /pprof} and the {@code gc} and {@code log-level} tasks, with the content types and cache
 * headers the original sent. Spring Boot's actuator offers none of these in the original shape.
 */
public class AdminOperationsFilter extends OncePerRequestFilter {

  /**
   * Runs inside the admin {@link JettyGzipFilter}, which decides compression and {@code Vary}.
   */
  public static final int ORDER = JettyGzipFilter.ORDER + 1000;

  private static final String NO_CACHE = JettyErrorPages.CACHE_CONTROL;

  private static final org.slf4j.Logger LOG = LoggerFactory.getLogger(AdminOperationsFilter.class);

  private static final String TASKS = "/tasks";

  /**
   * The name the error pages show for the admin servlet.
   */
  private static final String SERVLET_NAME = "admin";

  private static final String MENU =
      "<!DOCTYPE HTML PUBLIC \"-//W3C//DTD HTML 4.01 Transitional//EN\"\n"
      + "        \"http://www.w3.org/TR/html4/loose.dtd\">\n"
      + "<html>\n"
      + "<head>\n"
      + "  <title>Metrics</title>\n"
      + "</head>\n"
      + "<body>\n"
      + "  <h1>Operational Menu</h1>\n"
      + "  <ul>\n"
      + "    <li><a href=\"/metrics?pretty=true\">Metrics</a></li>\n"
      + "    <li><a href=\"/ping\">Ping</a></li>\n"
      + "    <li><a href=\"/threads\">Threads</a></li>\n"
      + "    <li><a href=\"/healthcheck?pretty=true\">Healthcheck</a></li>\n"
      + "    <li><a href=\"/pprof\">CPU Profile</a></li>\n"
      + "    <li><a href=\"/pprof?state=blocked\">CPU Contention</a></li>\n"
      + "  </ul>\n"
      + "</body>\n"
      + "</html>\n";

  private static final DateTimeFormatter TIMESTAMP =
      DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSXXX");

  private static final int DEFAULT_PROFILE_SECONDS = 10;
  private static final int DEFAULT_PROFILE_FREQUENCY = 100;

  private final ObjectMapper compact = new ObjectMapper();
  private final ObjectMapper pretty = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
  private final LogbackEventMeters logbackEventMeters;
  private final RequestMetrics requestMetrics;
  /** Dropwizard's CPU profile servlet records one profile at a time. */
  private final Lock profiling = new ReentrantLock();
  /**
   * Resets the loggers the {@code log-level} task configured for a duration.
   */
  private final Timer levelResets = new Timer("log-level-resets", true);

  public AdminOperationsFilter(LogbackEventMeters logbackEventMeters,
      RequestMetrics requestMetrics) {
    this.logbackEventMeters = checkNotNull(logbackEventMeters);
    this.requestMetrics = checkNotNull(requestMetrics);
  }

  /**
   * Stops the log-level reset timer, dropping pending resets, when the admin server stops.
   */
  @Override
  public void destroy() {
    levelResets.cancel();
  }

  @Override
  protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
      FilterChain filterChain) throws ServletException, IOException {
    String path = request.getServletPath() + Objects.toString(request.getPathInfo(), "");
    if (TASKS.equals(path) || path.startsWith(TASKS + "/")) {
      tasks(request, response, path.substring(TASKS.length()));
      return;
    }
    if (!isEndpoint(path)) {
      JettyErrorPages.send(request, response, HttpStatus.NOT_FOUND.value(),
          HttpStatus.NOT_FOUND.getReasonPhrase(), SERVLET_NAME);
      return;
    }
    switch (request.getMethod()) {
      case "GET", "HEAD" -> serve(request, response, path);
      case "OPTIONS" -> allow(response, "GET, HEAD, TRACE, OPTIONS");
      default -> refuse(request, response);
    }
  }

  /**
   * Dropwizard's admin servlet: the menu at the root, the metrics servlet for every path that
   * starts with {@code /metrics}, the other endpoints at their exact paths.
   */
  private static boolean isEndpoint(String path) {
    return "/".equals(path) || "/healthcheck".equals(path) || path.startsWith("/metrics")
        || "/ping".equals(path) || "/threads".equals(path) || "/pprof".equals(path);
  }

  private void serve(HttpServletRequest request, HttpServletResponse response, String path)
      throws IOException {
    boolean head = "HEAD".equals(request.getMethod());
    if ("/".equals(path)) {
      send(request, response, head ? "text/html" : "text/html;charset=utf-8", Cache.BEFORE_TYPE,
          MENU, StandardCharsets.UTF_8);
    } else if ("/healthcheck".equals(path)) {
      send(request, response, "application/json", Cache.AFTER_TYPE, json(request, healthChecks()),
          StandardCharsets.UTF_8);
    } else if (path.startsWith("/metrics")) {
      send(request, response, "application/json", Cache.AFTER_TYPE, json(request, metrics()),
          StandardCharsets.UTF_8);
    } else if ("/ping".equals(path)) {
      send(request, response, head ? "text/plain" : "text/plain;charset=iso-8859-1",
          Cache.BEFORE_TYPE, "pong\n", StandardCharsets.ISO_8859_1);
    } else if ("/threads".equals(path)) {
      send(request, response, "text/plain", Cache.AFTER_TYPE,
          threadDump(flag(request, "monitors"), flag(request, "synchronizers")),
          StandardCharsets.UTF_8);
    } else {
      profile(request, response);
    }
  }

  /**
   * Dropwizard's task servlet, mounted at {@code /tasks/*}: GET lists the tasks, POST runs one.
   */
  private void tasks(HttpServletRequest request, HttpServletResponse response, String task)
      throws IOException {
    switch (request.getMethod()) {
      case "GET", "HEAD" -> {
        if (task.isEmpty()) {
          boolean head = "HEAD".equals(request.getMethod());
          send(request, response,
              head ? "text/plain;charset=utf-8" : "text/plain;charset=iso-8859-1", Cache.NONE,
              "gc\nlog-level\n", StandardCharsets.ISO_8859_1);
        } else {
          status(response, isTask(task) ? HttpStatus.METHOD_NOT_ALLOWED : HttpStatus.NOT_FOUND);
        }
      }
      case "POST" -> {
        if (isTask(task)) {
          runTask(request, response, task.substring(1));
        } else {
          status(response, HttpStatus.NOT_FOUND);
        }
      }
      case "OPTIONS" -> allow(response, "GET, HEAD, POST, TRACE, OPTIONS");
      default -> refuse(request, response);
    }
  }

  private static boolean isTask(String task) {
    return "/gc".equals(task) || "/log-level".equals(task);
  }

  /**
   * Runs a task; a task that fails answers its message as Dropwizard's task servlet did.
   */
  private void runTask(HttpServletRequest request, HttpServletResponse response, String task)
      throws IOException {
    String output;
    try {
      output = "gc".equals(task) ? runGarbageCollection(request) : configureLogLevels(request);
    } catch (RuntimeException failure) {
      LOG.error(format("Error running %s", task), failure);
      byte[] body = ("\n" + failure.getMessage() + "\n").getBytes(StandardCharsets.UTF_8);
      response.setStatus(HttpStatus.INTERNAL_SERVER_ERROR.value());
      response.setContentType("text/plain;charset=utf-8");
      response.setContentLength(body.length);
      response.getOutputStream().write(body);
      return;
    }
    send(request, response, "text/plain;charset=utf-8", Cache.NONE, output, StandardCharsets.UTF_8);
  }

  /**
   * {@code HttpServlet}'s own answers for the methods an admin servlet does not implement: POST,
   * PUT and DELETE are 405 and any other method (PATCH) 501, through {@code sendError}, which
   * renders a page for POST only.
   */
  private static void refuse(HttpServletRequest request, HttpServletResponse response)
      throws IOException {
    String method = request.getMethod();
    if ("POST".equals(method) || "PUT".equals(method) || "DELETE".equals(method)) {
      JettyErrorPages.send(request, response, HttpStatus.METHOD_NOT_ALLOWED.value(),
          format("HTTP method %s is not supported by this URL", method), SERVLET_NAME);
    } else {
      JettyErrorPages.send(request, response, HttpStatus.NOT_IMPLEMENTED.value(),
          "Method Not Implemented", SERVLET_NAME);
    }
  }

  /**
   * {@code HttpServlet}'s OPTIONS answer: the methods the servlet implements, no entity.
   */
  private static void allow(HttpServletResponse response, String methods) {
    response.setStatus(HttpStatus.OK.value());
    response.setHeader(HttpHeaders.ALLOW, methods);
    response.setContentLength(0);
  }

  private static void status(HttpServletResponse response, HttpStatus status) {
    response.setStatus(status.value());
    response.setContentLength(0);
  }

  /**
   * A thread dump option: on unless the parameter is present and not {@code true}.
   */
  private static boolean flag(HttpServletRequest request, String name) {
    String value = request.getParameter(name);
    return value == null || Boolean.parseBoolean(value);
  }

  /**
   * Dropwizard's {@code /pprof}: samples for {@code duration} seconds at {@code frequency} samples
   * per second the threads that are runnable, or blocked with {@code state=blocked}. A request that
   * arrives while another profile is being recorded gets an empty profile at once.
   */
  private void profile(HttpServletRequest request, HttpServletResponse response)
      throws IOException {
    int duration = integer(request.getParameter("duration"), DEFAULT_PROFILE_SECONDS);
    int frequency = Math.min(Math.max(
        integer(request.getParameter("frequency"), DEFAULT_PROFILE_FREQUENCY), 1), 1000);
    Thread.State state = "blocked".equalsIgnoreCase(request.getParameter("state"))
        ? Thread.State.BLOCKED : Thread.State.RUNNABLE;
    response.setStatus(HttpStatus.OK.value());
    response.setHeader(HttpHeaders.CACHE_CONTROL, NO_CACHE);
    response.setContentType("pprof/raw");
    if (!profiling.tryLock()) {
      response.setContentLength(0);
      return;
    }
    byte[] profile;
    try {
      profile = CpuProfiler.record(Duration.ofSeconds(duration), frequency, state);
    } finally {
      profiling.unlock();
    }
    if (profile == null) {
      throw new IllegalStateException("could not create a CPU profile");
    }
    response.setContentLength(profile.length);
    if (!"HEAD".equals(request.getMethod())) {
      response.getOutputStream().write(profile);
    }
  }

  private static int integer(String value, int fallback) {
    if (value == null) {
      return fallback;
    }
    try {
      return Integer.parseInt(value);
    } catch (NumberFormatException notANumber) {
      return fallback;
    }
  }

  /**
   * Whether an answer carries {@code Cache-Control}, and where: the original's servlets set it
   * before (menu, ping) or after (health checks, metrics, thread dump) the content type, and the
   * fields go out in the order they were set.
   */
  private enum Cache {
    NONE, BEFORE_TYPE, AFTER_TYPE
  }

  private static void send(HttpServletRequest request, HttpServletResponse response,
      String contentType, Cache cache, String body, Charset charset) throws IOException {
    final byte[] bytes = body.getBytes(charset);
    response.setStatus(HttpStatus.OK.value());
    if (cache == Cache.BEFORE_TYPE) {
      response.setHeader(HttpHeaders.CACHE_CONTROL, NO_CACHE);
    }
    response.setContentType(contentType);
    if (cache == Cache.AFTER_TYPE) {
      response.setHeader(HttpHeaders.CACHE_CONTROL, NO_CACHE);
    }
    response.setContentLength(bytes.length);
    if (!"HEAD".equals(request.getMethod())) {
      response.getOutputStream().write(bytes);
    }
  }

  private String json(HttpServletRequest request, Object document) throws JsonProcessingException {
    boolean indent = Boolean.parseBoolean(request.getParameter("pretty"));
    return (indent ? pretty : compact).writeValueAsString(document);
  }

  private static Map<String, Object> healthChecks() {
    long start = System.currentTimeMillis();
    long[] deadlocked = ManagementFactory.getThreadMXBean().findDeadlockedThreads();
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("healthy", deadlocked == null || deadlocked.length == 0);
    result.put("duration", System.currentTimeMillis() - start);
    result.put("timestamp", ZonedDateTime.now(ZoneId.systemDefault()).format(TIMESTAMP));
    Map<String, Object> registry = new LinkedHashMap<>();
    registry.put("deadlocks", result);
    return registry;
  }

  /**
   * Dropwizard's {@code gc} task: as many collections as the first {@code runs} parameter says, one
   * when it is absent or not an integer.
   */
  private static String runGarbageCollection(HttpServletRequest request) {
    int runs = integer(request.getParameter("runs"), 1);
    StringBuilder output = new StringBuilder();
    for (int run = 0; run < runs; run++) {
      output.append("Running GC...\n");
      Runtime.getRuntime().gc();
    }
    return output.append("Done!\n").toString();
  }

  /**
   * Dropwizard's {@code log-level} task: every {@code logger} parameter gets the first
   * {@code level} parameter's level (an unknown name means DEBUG, as Logback parses it; no level
   * resets the logger to its inherited level). With a level and a {@code duration}, the logger
   * goes back to its inherited level once the duration has passed.
   */
  private String configureLogLevels(HttpServletRequest request) {
    String[] loggers = request.getParameterValues("logger");
    String levelName = request.getParameter("level");
    Level level = levelName == null ? null : Level.toLevel(levelName);
    String durationText = request.getParameter("duration");
    Duration duration = durationText == null ? null : Duration.parse(durationText);
    LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
    StringBuilder output = new StringBuilder();
    for (String name : loggers == null ? new String[0] : loggers) {
      Logger logger = context.getLogger(name);
      output.append(format("Configured logging level for %s to %s", name, level));
      if (level != null && duration != null) {
        long millis = duration.toMillis();
        levelResets.schedule(new TimerTask() {
          @Override
          public void run() {
            logger.setLevel(null);
          }
        }, millis);
        output.append(format(" for %d milliseconds", millis));
      }
      output.append('\n');
      logger.setLevel(level);
    }
    return output.toString();
  }

  /**
   * The thread dump of Dropwizard's {@code /threads}: every live thread in the order the JVM
   * reports them, each with its state, the lock it waits on, its stack and, when asked for, the
   * monitors and synchronizers it holds, followed by an empty line.
   */
  private static String threadDump(boolean lockedMonitors, boolean lockedSynchronizers) {
    ThreadInfo[] threads = ManagementFactory.getThreadMXBean()
        .dumpAllThreads(lockedMonitors, lockedSynchronizers);
    StringWriter text = new StringWriter();
    PrintWriter writer = new PrintWriter(text);
    for (ThreadInfo thread : threads) {
      writer.printf("\"%s\" id=%d state=%s", thread.getThreadName(), thread.getThreadId(),
          thread.getThreadState());
      LockInfo lock = thread.getLockInfo();
      if (lock != null && thread.getThreadState() != Thread.State.BLOCKED) {
        writer.printf("%n    - waiting on <0x%08x> (a %s)", lock.getIdentityHashCode(),
            lock.getClassName());
        writer.printf("%n    - locked <0x%08x> (a %s)", lock.getIdentityHashCode(),
            lock.getClassName());
      } else if (lock != null) {
        writer.printf("%n    - waiting to lock <0x%08x> (a %s)", lock.getIdentityHashCode(),
            lock.getClassName());
      }
      if (thread.isSuspended()) {
        writer.print(" (suspended)");
      }
      if (thread.isInNative()) {
        writer.print(" (running in native)");
      }
      writer.println();
      if (thread.getLockOwnerName() != null) {
        writer.printf("     owned by %s id=%d%n", thread.getLockOwnerName(),
            thread.getLockOwnerId());
      }
      StackTraceElement[] elements = thread.getStackTrace();
      MonitorInfo[] monitors = thread.getLockedMonitors();
      for (int depth = 0; depth < elements.length; depth++) {
        writer.printf("    at %s%n", elements[depth]);
        for (MonitorInfo monitor : monitors) {
          if (monitor.getLockedStackDepth() == depth) {
            writer.printf("      - locked %s%n", monitor);
          }
        }
      }
      writer.println();
      LockInfo[] synchronizers = thread.getLockedSynchronizers();
      if (synchronizers.length > 0) {
        writer.printf("    Locked synchronizers: count = %d%n", synchronizers.length);
        for (LockInfo synchronizer : synchronizers) {
          writer.printf("      - %s%n", synchronizer);
        }
        writer.println();
      }
    }
    writer.println();
    writer.flush();
    return text.toString();
  }

  /**
   * Dropwizard's metric registry in its JSON shape: the JVM gauges, the Logback meters and the
   * application port's request metrics.
   */
  private Map<String, Object> metrics() {
    SortedMap<String, Object> gauges = new TreeMap<>();
    RuntimeMXBean runtime = ManagementFactory.getRuntimeMXBean();
    gauge(gauges, "jvm.attribute.name", runtime.getName());
    gauge(gauges, "jvm.attribute.uptime", runtime.getUptime());
    gauge(gauges, "jvm.attribute.vendor", format(Locale.ROOT, "%s %s %s (%s)",
        runtime.getVmVendor(), runtime.getVmName(), runtime.getVmVersion(),
        runtime.getSpecVersion()));
    ClassLoadingMXBean classes = ManagementFactory.getClassLoadingMXBean();
    gauge(gauges, "jvm.classloader.loaded", classes.getTotalLoadedClassCount());
    gauge(gauges, "jvm.classloader.unloaded", classes.getUnloadedClassCount());
    OperatingSystemMXBean system = ManagementFactory.getOperatingSystemMXBean();
    if (system instanceof com.sun.management.UnixOperatingSystemMXBean unix) {
      gauge(gauges, "jvm.filedescriptor",
          (double) unix.getOpenFileDescriptorCount() / unix.getMaxFileDescriptorCount());
    }
    for (GarbageCollectorMXBean collector : ManagementFactory.getGarbageCollectorMXBeans()) {
      String name = "jvm.gc." + collector.getName().replaceAll("\\s+", "-");
      gauge(gauges, name + ".count", collector.getCollectionCount());
      gauge(gauges, name + ".time", collector.getCollectionTime());
    }
    MemoryMXBean memory = ManagementFactory.getMemoryMXBean();
    MemoryUsage heap = memory.getHeapMemoryUsage();
    MemoryUsage nonHeap = memory.getNonHeapMemoryUsage();
    usage(gauges, "jvm.memory.heap", heap);
    usage(gauges, "jvm.memory.non-heap", nonHeap);
    gauge(gauges, "jvm.memory.total.committed", heap.getCommitted() + nonHeap.getCommitted());
    gauge(gauges, "jvm.memory.total.init", heap.getInit() + nonHeap.getInit());
    gauge(gauges, "jvm.memory.total.max",
        nonHeap.getMax() == -1 ? -1 : heap.getMax() + nonHeap.getMax());
    gauge(gauges, "jvm.memory.total.used", heap.getUsed() + nonHeap.getUsed());
    for (MemoryPoolMXBean pool : ManagementFactory.getMemoryPoolMXBeans()) {
      String name = "jvm.memory.pools." + pool.getName().replaceAll("\\s+", "-");
      usage(gauges, name, pool.getUsage());
      MemoryUsage afterGc = pool.getCollectionUsage();
      if (afterGc != null) {
        gauge(gauges, name + ".used-after-gc", afterGc.getUsed());
      }
    }
    for (BufferPoolMXBean buffers : ManagementFactory.getPlatformMXBeans(BufferPoolMXBean.class)) {
      if ("direct".equals(buffers.getName()) || "mapped".equals(buffers.getName())) {
        String name = "jvm.buffers." + buffers.getName();
        gauge(gauges, name + ".capacity", buffers.getTotalCapacity());
        gauge(gauges, name + ".count", buffers.getCount());
        gauge(gauges, name + ".used", buffers.getMemoryUsed());
      }
    }
    ThreadMXBean threads = ManagementFactory.getThreadMXBean();
    Map<Thread.State, Integer> states = new EnumMap<>(Thread.State.class);
    for (Thread.State state : Thread.State.values()) {
      states.put(state, 0);
    }
    for (ThreadInfo info : threads.getThreadInfo(threads.getAllThreadIds())) {
      if (info != null) {
        states.merge(info.getThreadState(), 1, Integer::sum);
      }
    }
    for (Map.Entry<Thread.State, Integer> state : states.entrySet()) {
      gauge(gauges, "jvm.threads." + state.getKey().name().toLowerCase(Locale.ROOT) + ".count",
          state.getValue());
    }
    long[] deadlocked = threads.findDeadlockedThreads();
    List<String> deadlocks = new ArrayList<>();
    if (deadlocked != null) {
      for (ThreadInfo info : threads.getThreadInfo(deadlocked, Integer.MAX_VALUE)) {
        deadlocks.add(format("%s locked on %s (owned by %s):%n", info.getThreadName(),
            info.getLockName(), info.getLockOwnerName()));
      }
    }
    gauge(gauges, "jvm.threads.count", threads.getThreadCount());
    gauge(gauges, "jvm.threads.daemon.count", threads.getDaemonThreadCount());
    gauge(gauges, "jvm.threads.deadlock.count", deadlocks.size());
    gauge(gauges, "jvm.threads.deadlocks", deadlocks);
    gauge(gauges, "jvm.threads.peak.count", threads.getPeakThreadCount());
    gauge(gauges, "jvm.threads.total_started.count", threads.getTotalStartedThreadCount());
    requestMetrics.addGauges(gauges);
    SortedMap<String, Object> meters = new TreeMap<String, Object>(logbackEventMeters.snapshot());
    requestMetrics.addMeters(meters);
    Map<String, Object> registry = new LinkedHashMap<>();
    registry.put("version", "4.0.0");
    registry.put("gauges", gauges);
    registry.put("counters", requestMetrics.counters());
    registry.put("histograms", new TreeMap<String, Object>());
    registry.put("meters", meters);
    registry.put("timers", requestMetrics.timers());
    return registry;
  }

  private static void usage(Map<String, Object> gauges, String prefix, MemoryUsage usage) {
    gauge(gauges, prefix + ".committed", usage.getCommitted());
    gauge(gauges, prefix + ".init", usage.getInit());
    gauge(gauges, prefix + ".max", usage.getMax());
    long whole = usage.getMax() == -1 ? usage.getCommitted() : usage.getMax();
    gauge(gauges, prefix + ".usage", whole == 0 ? Double.NaN : (double) usage.getUsed() / whole);
    gauge(gauges, prefix + ".used", usage.getUsed());
  }

  private static void gauge(Map<String, Object> gauges, String name, Object value) {
    Map<String, Object> gauge = new LinkedHashMap<>();
    gauge.put("value", value);
    gauges.put(name, gauge);
  }
}
