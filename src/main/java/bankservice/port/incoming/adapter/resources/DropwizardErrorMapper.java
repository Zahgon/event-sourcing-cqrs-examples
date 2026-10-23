package bankservice.port.incoming.adapter.resources;

import static java.lang.String.format;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * Reproduces the error envelope the original Dropwizard service emitted. Dropwizard renders every
 * mapped failure as {@code {"code":N,"message":"..."}}; Spring Boot's default is
 * {@code {"timestamp","status","error","path"}}, which is a different wire contract for the same
 * failures. Ordered last so the exception-specific advices still win for the types they declare.
 */
@Order(Ordered.LOWEST_PRECEDENCE)
@RestControllerAdvice
public class DropwizardErrorMapper {

  private static final Logger logger = LoggerFactory.getLogger(DropwizardErrorMapper.class);

  @ExceptionHandler(MethodArgumentTypeMismatchException.class)
  public ResponseEntity<Map<String, Object>> toResponse(
      MethodArgumentTypeMismatchException exception) {
    return error(HttpStatus.BAD_REQUEST,
        format("path param %s is not a UUID.", exception.getName()));
  }

  /**
   * Jersey answered a method it did not route with the {@code Allow} field listing the resource's
   * own methods together with OPTIONS, which every resource supports. Spring reports only the
   * methods the mapping declares, and the field was being dropped altogether when the envelope was
   * rebuilt, so it is put back here.
   */
  @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
  public ResponseEntity<Map<String, Object>> toResponse(
      HttpRequestMethodNotSupportedException exception) {
    // Jersey collected the methods in a HashSet; its iteration order is the order of the field.
    Set<String> allowed = new HashSet<>();
    allowed.add(HttpMethod.OPTIONS.name());
    if (exception.getSupportedHttpMethods() != null) {
      exception.getSupportedHttpMethods().forEach(method -> allowed.add(method.name()));
    }
    return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED)
        .header(HttpHeaders.ALLOW, String.join(",", allowed))
        .contentType(MediaType.APPLICATION_JSON)
        .body(body(HttpStatus.METHOD_NOT_ALLOWED, "HTTP 405 Method Not Allowed"));
  }

  @ExceptionHandler(HttpMediaTypeNotAcceptableException.class)
  public ResponseEntity<Map<String, Object>> toResponse(
      HttpMediaTypeNotAcceptableException exception) {
    return error(HttpStatus.NOT_ACCEPTABLE, "HTTP 406 Not Acceptable");
  }

  @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
  public ResponseEntity<Map<String, Object>> toResponse(
      HttpMediaTypeNotSupportedException exception) {
    return error(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "HTTP 415 Unsupported Media Type");
  }

  /**
   * Jersey was mapped at {@code /*}, so an unrouted path never reached Jetty's own error page: it
   * failed inside Jersey and came back as the same JSON envelope as any other mapped failure.
   */
  @ExceptionHandler({NoResourceFoundException.class, NoHandlerFoundException.class})
  public ResponseEntity<Map<String, Object>> toResponse(Exception exception) {
    return error(HttpStatus.NOT_FOUND, "HTTP 404 Not Found");
  }

  /**
   * Stands in for the JAX-RS {@code WebApplicationException}: Dropwizard's own mapper rendered one
   * as the envelope carrying the status line, not as an empty body.
   */
  @ExceptionHandler(ResponseStatusException.class)
  public ResponseEntity<Map<String, Object>> toResponse(ResponseStatusException exception) {
    HttpStatus status = HttpStatus.valueOf(exception.getStatusCode().value());
    return error(status, format("HTTP %d %s", status.value(), status.getReasonPhrase()));
  }

  @ExceptionHandler(Throwable.class)
  public ResponseEntity<Map<String, Object>> toResponse(Throwable exception) {
    return error(HttpStatus.INTERNAL_SERVER_ERROR, logged(exception));
  }

  /**
   * The same logged 500 for a failure before the request reaches the dispatcher, such as an entity
   * the request log cannot read or decode.
   */
  static void writeLoggedError(HttpServletResponse response, Throwable exception)
      throws IOException {
    byte[] entity = new ObjectMapper().writeValueAsBytes(
        body(HttpStatus.INTERNAL_SERVER_ERROR, logged(exception)));
    response.setStatus(HttpStatus.INTERNAL_SERVER_ERROR.value());
    response.setContentType(MediaType.APPLICATION_JSON_VALUE);
    response.setContentLength(entity.length);
    response.getOutputStream().write(entity);
  }

  private static String logged(Throwable exception) {
    long id = ThreadLocalRandom.current().nextLong();
    logger.error(format("Error handling a request: %016x", id), exception);
    return format(
        "There was an error processing your request. It has been logged (ID %016x).", id);
  }

  private static ResponseEntity<Map<String, Object>> error(HttpStatus status, String message) {
    return ResponseEntity.status(status).contentType(MediaType.APPLICATION_JSON)
        .body(body(status, message));
  }

  private static Map<String, Object> body(HttpStatus status, String message) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("code", status.value());
    body.put("message", message);
    return body;
  }
}
