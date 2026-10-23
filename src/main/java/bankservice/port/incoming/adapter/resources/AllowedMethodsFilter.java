package bankservice.port.incoming.adapter.resources;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Refuses every method outside Dropwizard's default allowed set the way the original did on both
 * connectors: a bare 405 with no {@code Allow} field, no content type and no entity, before the
 * request reaches anything that logs or answers it. TRACE is outside the set, so it is refused too.
 */
public class AllowedMethodsFilter extends OncePerRequestFilter {

  /**
   * Runs outside every other response filter; nothing else should see a refused method.
   */
  public static final int ORDER = JettyGzipFilter.ORDER - 100;

  /**
   * {@code ServerFactory#allowedMethods} as Dropwizard ships it.
   */
  private static final Set<String> ALLOWED =
      Set.of("GET", "POST", "PUT", "DELETE", "HEAD", "OPTIONS", "PATCH");

  @Override
  protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
      FilterChain filterChain) throws ServletException, IOException {
    if (!ALLOWED.contains(request.getMethod())) {
      response.setStatus(HttpStatus.METHOD_NOT_ALLOWED.value());
      return;
    }
    filterChain.doFilter(request, response);
  }
}
