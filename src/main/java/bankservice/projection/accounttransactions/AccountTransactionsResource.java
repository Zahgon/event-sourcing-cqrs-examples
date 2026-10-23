package bankservice.projection.accounttransactions;

import static com.google.common.base.Preconditions.checkNotNull;
import static org.springframework.http.MediaType.APPLICATION_JSON_VALUE;

import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping(path = "/accounts/{id}/transactions", produces = APPLICATION_JSON_VALUE)
public class AccountTransactionsResource {

  private TransactionsRepository transactionsRepository;

  public AccountTransactionsResource(TransactionsRepository transactionsRepository) {
    this.transactionsRepository = checkNotNull(transactionsRepository);
  }

  @GetMapping
  public ResponseEntity<List<TransactionProjection>> get(@PathVariable("id") String id) {
    UUID accountId;
    try {
      accountId = UUID.fromString(id);
    } catch (IllegalArgumentException notAnAccountId) {
      // This is the one resource the original declared with a raw UUID instead of UUIDParam, and
      // Jersey reports a path parameter it cannot convert as 404 rather than 400. It does so by
      // throwing, so the answer carried Dropwizard's JSON envelope rather than an empty body.
      throw new ResponseStatusException(HttpStatus.NOT_FOUND);
    }
    return ResponseEntity.ok(transactionsRepository.listByAccount(accountId));
  }
}
