package bankservice.port.incoming.adapter.resources;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Reproduces the error page Jetty rendered for a failure that carried no entity.
 *
 * <p>A resource that answered with an empty {@code Response.status(...)} left Jersey with nothing
 * to write, and Jersey turns that into a servlet {@code sendError}, so the container - not the
 * application - produced the body (see {@link JettyErrorPages}). Spring MVC instead returns the
 * bare status with no entity, so every one of those answers lost its body and its content type.
 *
 * <p>Jersey also refused, before routing and before its logging filter ran, a request whose
 * {@code Content-Type} it could not parse, again with a bare 400 that Jetty turned into its page.
 */
public class JettyErrorPageFilter extends OncePerRequestFilter {

  /**
   * Runs inside {@link JettyGzipFilter}, so its page is measured like any other entity.
   */
  public static final int ORDER = JettyGzipFilter.ORDER + 100;

  /**
   * The name Dropwizard registered the Jersey container under, which Jetty printed into the page.
   */
  private static final String SERVLET_NAME = "jersey";

  @Override
  protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
      FilterChain filterChain) throws ServletException, IOException {
    String contentType = request.getHeader(HttpHeaders.CONTENT_TYPE);
    if (contentType != null && MediaTypeHeader.parameters(contentType).isEmpty()) {
      send(request, response, HttpStatus.BAD_REQUEST.value());
      return;
    }
    CountingResponseWrapper counting = new CountingResponseWrapper(response);
    filterChain.doFilter(request, counting);
    int status = counting.getStatus();
    if (status < HttpStatus.BAD_REQUEST.value() || counting.getWrittenByteCount() > 0
        || response.isCommitted()) {
      return;
    }
    send(request, response, status);
  }

  private static void send(HttpServletRequest request, HttpServletResponse response, int status)
      throws IOException {
    HttpStatus resolved = HttpStatus.resolve(status);
    String reason = resolved == null ? Integer.toString(status) : resolved.getReasonPhrase();
    JettyErrorPages.send(request, response, status, reason, SERVLET_NAME);
  }
}
