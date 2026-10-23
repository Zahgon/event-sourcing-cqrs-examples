package bankservice.port.incoming.adapter.resources.accounts.deposits;

import static com.google.common.base.Preconditions.checkNotNull;
import static org.springframework.http.MediaType.APPLICATION_JSON_VALUE;

import bankservice.domain.model.OptimisticLockingException;
import bankservice.service.account.AccountNotFoundException;
import bankservice.service.account.AccountService;
import bankservice.service.account.DepositAccountCommand;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(path = "/accounts/{id}/deposits", produces = APPLICATION_JSON_VALUE)
public class DepositsResource {

  private final AccountService accountService;

  public DepositsResource(AccountService accountService) {
    this.accountService = checkNotNull(accountService);
  }

  @PostMapping(consumes = APPLICATION_JSON_VALUE)
  // No @NotNull in the original either: an absent body reaches the handler as null.
  public ResponseEntity<Void> post(@PathVariable("id") UUID accountId,
      @Valid @RequestBody(required = false) DepositDto depositDto)
      throws AccountNotFoundException, OptimisticLockingException {

    DepositAccountCommand command = new DepositAccountCommand(accountId,
        depositDto.getAmount());
    accountService.process(command);
    return ResponseEntity.noContent().build();
  }
}
