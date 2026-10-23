package bankservice.bootstrap;

import static java.util.concurrent.Executors.newSingleThreadExecutor;

import com.google.common.eventbus.AsyncEventBus;
import java.util.concurrent.ExecutorService;

/**
 * The asynchronous event bus that feeds the projections, delivering events on one thread of its
 * own. Closing it shuts that thread down once the events already posted have been delivered.
 */
final class SingleThreadEventBus extends AsyncEventBus implements AutoCloseable {

  private final ExecutorService executor;

  SingleThreadEventBus() {
    this(newSingleThreadExecutor());
  }

  private SingleThreadEventBus(ExecutorService executor) {
    super(executor);
    this.executor = executor;
  }

  @Override
  public void close() {
    executor.shutdown();
  }
}
