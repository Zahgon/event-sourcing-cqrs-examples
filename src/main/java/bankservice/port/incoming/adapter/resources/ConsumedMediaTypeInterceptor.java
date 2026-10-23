package bankservice.port.incoming.adapter.resources;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Refuses a {@code Content-Type} the JSON resources do not consume, also on requests without an
 * entity.
 *
 * <p>Jersey matched the {@code @Consumes(APPLICATION_JSON)} of a resource class against every
 * request routed to it, after the method check and before any parameter was converted, so a
 * {@code GET} carrying {@code Content-Type: text/plain} was a 415. Spring compares a mapping's
 * consumed type only when the handler reads a body.
 */
public class ConsumedMediaTypeInterceptor implements HandlerInterceptor {

  @Override
  public boolean preHandle(HttpServletRequest request, HttpServletResponse response,
      Object handler) throws HttpMediaTypeNotSupportedException {
    String contentType = request.getContentType();
    if ("OPTIONS".equals(request.getMethod()) || contentType == null || contentType.isEmpty()
        || MediaTypeHeader.acceptsJson(contentType)) {
      return true;
    }
    throw new HttpMediaTypeNotSupportedException(contentType);
  }
}
