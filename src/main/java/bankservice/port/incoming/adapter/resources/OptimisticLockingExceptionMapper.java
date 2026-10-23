package bankservice.port.incoming.adapter.resources;


import bankservice.domain.model.OptimisticLockingException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice
public class OptimisticLockingExceptionMapper {

  @ExceptionHandler(OptimisticLockingException.class)
  public ResponseEntity<Void> toResponse(OptimisticLockingException exception) {
    return ResponseEntity.status(HttpStatus.CONFLICT).build();
  }
}
