package bankservice.port.incoming.adapter.resources;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.HttpMethod;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Gives a HEAD answer the {@code Content-Length} the original carried.
 *
 * <p>Jersey wrote every entity through a buffering output stream, so the size of the entity a HEAD
 * request would have returned was known before the headers went out and the original answered with
 * a {@code Content-Length} and no transfer coding. Spring MVC writes straight to the container,
 * which on a HEAD request discards the bytes as they arrive, never learns the length and falls
 * back to {@code Transfer-Encoding: chunked}. {@link JettyGzipFilter} already buffers GET and POST
 * - the methods Jetty's {@code GzipHandler} claims - so HEAD is the only method left to cover.
 */
public class HeadEntityLengthFilter extends OncePerRequestFilter {

  /**
   * Runs immediately inside {@link JettyGzipFilter}, which passes HEAD straight through, so no
   * response is ever buffered by both filters.
   */
  public static final int ORDER = JettyGzipFilter.ORDER + 50;

  @Override
  protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
      FilterChain filterChain) throws ServletException, IOException {
    if (!HttpMethod.HEAD.matches(request.getMethod())) {
      filterChain.doFilter(request, response);
      return;
    }
    BufferingResponseWrapper buffered = new BufferingResponseWrapper(response);
    filterChain.doFilter(request, buffered);
    byte[] entity = buffered.entity();
    response.setContentLength(entity.length);
    if (entity.length > 0) {
      response.getOutputStream().write(entity);
    }
  }
}
