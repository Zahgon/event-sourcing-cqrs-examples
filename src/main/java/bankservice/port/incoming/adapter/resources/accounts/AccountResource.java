package bankservice.port.incoming.adapter.resources.accounts;

import static com.google.common.base.Preconditions.checkNotNull;
import static org.springframework.http.MediaType.APPLICATION_JSON_VALUE;

import bankservice.domain.model.account.Account;
import bankservice.service.account.AccountService;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(path = "/accounts/{id}", produces = APPLICATION_JSON_VALUE)
public class AccountResource {

  private final AccountService accountService;

  public AccountResource(AccountService accountService) {
    this.accountService = checkNotNull(accountService);
  }

  @GetMapping
  public ResponseEntity<AccountDto> get(@PathVariable("id") UUID accountId) {
    Optional<Account> possibleAccount = accountService.loadAccount(accountId);
    if (!possibleAccount.isPresent()) {
      return ResponseEntity.notFound().build();
    }
    return ResponseEntity.ok(toDto(possibleAccount.get()));
  }

  private AccountDto toDto(Account account) {
    AccountDto dto = new AccountDto();
    dto.setId(account.getId());
    dto.setBalance(account.getBalance());
    dto.setClientId(account.getClientId());
    return dto;
  }
}
