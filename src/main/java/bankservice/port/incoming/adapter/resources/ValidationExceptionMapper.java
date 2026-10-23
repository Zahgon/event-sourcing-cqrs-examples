package bankservice.port.incoming.adapter.resources;

import static java.lang.String.format;
import static java.util.stream.Collectors.toList;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.context.MessageSourceResolvable;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.validation.method.ParameterValidationResult;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;

@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice
public class ValidationExceptionMapper {

  private static final String MISSING_BODY_MESSAGE = "The request body must not be null";
  private static final String UNREADABLE_MESSAGE = "Unable to process JSON";

  @ExceptionHandler(MethodArgumentNotValidException.class)
  public ResponseEntity<Map<String, List<String>>> toResponse(
      MethodArgumentNotValidException exception) {
    List<String> errors = exception.getBindingResult().getAllErrors().stream()
        .map(ValidationExceptionMapper::toMessage)
        .collect(toList());
    return unprocessableEntity(errors);
  }

  /**
   * The same bean-validation failure, reported differently. A handler method that carries a
   * constraint anywhere in its signature - {@code ClientResource#put} declares {@code @NotNull}
   * beside the body - is validated as a method rather than as a single argument, so Spring raises
   * this instead of {@link MethodArgumentNotValidException} and the 422 envelope was lost on that
   * one route while the POST route kept it.
   */
  @ExceptionHandler(HandlerMethodValidationException.class)
  public ResponseEntity<Map<String, List<String>>> toResponse(
      HandlerMethodValidationException exception) {
    List<String> errors = new ArrayList<>();
    for (ParameterValidationResult result : exception.getParameterValidationResults()) {
      if (result.getArgument() == null
          && result.getMethodParameter().hasParameterAnnotation(RequestBody.class)) {
        // Jersey's wording for an absent entity on a parameter declared @NotNull.
        errors.add(MISSING_BODY_MESSAGE);
        continue;
      }
      result.getResolvableErrors().stream()
          .map(ValidationExceptionMapper::toMessage)
          .forEach(errors::add);
    }
    return unprocessableEntity(errors);
  }

  @ExceptionHandler(HttpMessageNotReadableException.class)
  public ResponseEntity<?> toResponse(HttpMessageNotReadableException exception) {
    if (exception.getCause() != null) {
      Map<String, Object> body = new LinkedHashMap<>();
      body.put("code", HttpStatus.BAD_REQUEST.value());
      body.put("message", UNREADABLE_MESSAGE);
      return ResponseEntity.badRequest().contentType(MediaType.APPLICATION_JSON).body(body);
    }
    return unprocessableEntity(List.of(MISSING_BODY_MESSAGE));
  }

  private static String toMessage(MessageSourceResolvable error) {
    if (error instanceof FieldError fieldError) {
      return format("%s %s", fieldError.getField(), fieldError.getDefaultMessage());
    }
    return error.getDefaultMessage();
  }

  private static ResponseEntity<Map<String, List<String>>> unprocessableEntity(
      List<String> errors) {
    return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
        .contentType(MediaType.APPLICATION_JSON)
        .body(Map.of("errors", errors));
  }
}
