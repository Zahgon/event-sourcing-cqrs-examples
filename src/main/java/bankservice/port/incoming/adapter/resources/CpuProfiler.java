package bankservice.port.incoming.adapter.resources;

import java.io.ByteArrayOutputStream;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The sampling CPU profiler behind Dropwizard's {@code /pprof}: it samples the stacks of every
 * thread in one state for a while and writes them in the legacy text-and-binary pprof format (a
 * symbol table, then 64-bit little-endian words: header, one record per distinct stack, trailer).
 */
final class CpuProfiler {

  /** Top frames of threads that the JVM reports as runnable while they really wait for I/O. */
  private static final Set<List<String>> IDLE_FRAMES = Set.of(
      List.of("sun.nio.ch.EPollArrayWrapper", "epollWait"),
      List.of("sun.nio.ch.KQueueArrayWrapper", "kevent0"),
      List.of("java.net.SocketInputStream", "socketRead0"),
      List.of("java.net.SocketOutputStream", "socketWrite0"),
      List.of("java.net.PlainSocketImpl", "socketAvailable"),
      List.of("java.net.PlainSocketImpl", "socketAccept"),
      List.of("sun.nio.ch.ServerSocketChannelImpl", "accept0"));

  private CpuProfiler() {
  }

  /**
   * Samples {@code frequency} times per second for {@code duration} and returns the profile, or
   * {@code null} when the sampling thread was interrupted.
   */
  static byte[] record(Duration duration, int frequency, Thread.State state) {
    ThreadMXBean threads = ManagementFactory.getThreadMXBean();
    Map<List<StackTraceElement>, Long> counts = new LinkedHashMap<>();
    long self = Thread.currentThread().threadId();
    long periodMillis = 1000 / frequency;
    Instant end = Instant.now().plus(duration);
    Instant next = Instant.now();
    while (Instant.now().isBefore(end)) {
      for (ThreadInfo thread : threads.dumpAllThreads(false, false)) {
        if (thread.getThreadState() != state || thread.getThreadId() == self) {
          continue;
        }
        List<StackTraceElement> stack = Arrays.asList(thread.getStackTrace());
        if (stack.isEmpty() || (state == Thread.State.RUNNABLE && isIdle(stack.get(0)))) {
          continue;
        }
        counts.merge(stack, 1L, Long::sum);
      }
      next = next.plusMillis(periodMillis);
      while (next.isBefore(Instant.now()) && next.isBefore(end)) {
        next = next.plusMillis(periodMillis);
      }
      try {
        Thread.sleep(Math.max(next.toEpochMilli() - Instant.now().toEpochMilli(), 0));
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
        return null;
      }
    }
    return write(counts);
  }

  private static boolean isIdle(StackTraceElement top) {
    return IDLE_FRAMES.contains(List.of(top.getClassName(), top.getMethodName()));
  }

  private static byte[] write(Map<List<StackTraceElement>, Long> counts) {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    StringBuilder symbols = new StringBuilder("--- symbol\nbinary=" + mainClassName() + "\n");
    Map<StackTraceElement, Integer> ids = new HashMap<>();
    for (List<StackTraceElement> stack : counts.keySet()) {
      for (StackTraceElement frame : stack) {
        if (!ids.containsKey(frame)) {
          int id = ids.size() + 1;
          ids.put(frame, id);
          symbols.append(String.format("0x%016x ", id)).append(frame).append('\n');
        }
      }
    }
    symbols.append("---\n--- profile\n");
    out.writeBytes(symbols.toString().getBytes(StandardCharsets.UTF_8));
    for (long word : new long[] {0, 3, 0, 1, 0}) {
      putWord(out, word);
    }
    for (Map.Entry<List<StackTraceElement>, Long> sample : counts.entrySet()) {
      putWord(out, sample.getValue());
      putWord(out, sample.getKey().size());
      for (StackTraceElement frame : sample.getKey()) {
        putWord(out, ids.get(frame));
      }
    }
    for (long word : new long[] {0, 1, 0}) {
      putWord(out, word);
    }
    return out.toByteArray();
  }

  private static void putWord(ByteArrayOutputStream out, long word) {
    out.writeBytes(ByteBuffer.allocate(Long.BYTES).order(ByteOrder.LITTLE_ENDIAN)
        .putLong(word).array());
  }

  /**
   * The class at the bottom of the {@code main} thread's stack, or {@code unknown}.
   */
  private static String mainClassName() {
    for (Map.Entry<Thread, StackTraceElement[]> thread : Thread.getAllStackTraces().entrySet()) {
      StackTraceElement[] stack = thread.getValue();
      if ("main".equals(thread.getKey().getName()) && stack.length > 0) {
        return stack[stack.length - 1].getClassName();
      }
    }
    return "unknown";
  }
}
