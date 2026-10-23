package bankservice.port.incoming.adapter.resources;

import static com.google.common.base.Preconditions.checkNotNull;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Feeds {@link RequestMetrics} with every request the application port receives, refused methods
 * included, as Dropwizard's instrumented handler wrapped the whole application context.
 */
public class RequestMetricsFilter extends OncePerRequestFilter {

  /**
   * Outside every other application filter, so refused and compressed answers are counted too.
   */
  public static final int ORDER = AllowedMethodsFilter.ORDER - 100;

  private final RequestMetrics metrics;

  public RequestMetricsFilter(RequestMetrics metrics) {
    this.metrics = checkNotNull(metrics);
  }

  @Override
  protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
      FilterChain filterChain) throws ServletException, IOException {
    long start = System.nanoTime();
    metrics.started();
    try {
      filterChain.doFilter(request, response);
    } finally {
      metrics.completed(request.getMethod(), response.getStatus(), System.nanoTime() - start);
    }
  }
}
