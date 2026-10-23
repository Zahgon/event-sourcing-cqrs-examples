package bankservice.port.incoming.adapter.resources.accounts.withdrawals;

import static com.google.common.base.Preconditions.checkNotNull;
import static org.springframework.http.MediaType.APPLICATION_JSON_VALUE;

import bankservice.domain.model.OptimisticLockingException;
import bankservice.domain.model.account.NonSufficientFundsException;
import bankservice.service.account.AccountNotFoundException;
import bankservice.service.account.AccountService;
import bankservice.service.account.WithdrawAccountCommand;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(path = "/accounts/{id}/withdrawals", produces = APPLICATION_JSON_VALUE)
public class WithdrawalsResource {

  private final AccountService accountService;

  public WithdrawalsResource(AccountService accountService) {
    this.accountService = checkNotNull(accountService);
  }

  @PostMapping(consumes = APPLICATION_JSON_VALUE)
  // No @NotNull in the original either: an absent body reaches the handler as null.
  public ResponseEntity<Void> post(@PathVariable("id") UUID accountId,
      @Valid @RequestBody(required = false) WithdrawalDto withdrawalDto)
      throws AccountNotFoundException, OptimisticLockingException {

    WithdrawAccountCommand command = new WithdrawAccountCommand(accountId,
        withdrawalDto.getAmount());
    try {
      accountService.process(command);
    } catch (NonSufficientFundsException e) {
      return ResponseEntity.status(HttpStatus.BAD_REQUEST).build();
    }
    return ResponseEntity.noContent().build();
  }
}
