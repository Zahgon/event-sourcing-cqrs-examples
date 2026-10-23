package bankservice.port.incoming.adapter.resources.accounts;


import bankservice.service.account.AccountNotFoundException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice
public class AccountNotFoundExceptionMapper {

  @ExceptionHandler(AccountNotFoundException.class)
  public ResponseEntity<Void> toResponse(AccountNotFoundException exception) {
    return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
  }
}
