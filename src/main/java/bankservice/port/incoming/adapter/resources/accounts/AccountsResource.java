package bankservice.port.incoming.adapter.resources.accounts;

import static com.google.common.base.Preconditions.checkNotNull;
import static org.springframework.http.MediaType.APPLICATION_JSON_VALUE;

import bankservice.service.account.AccountService;
import bankservice.service.account.OpenAccountCommand;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

@RestController
@RequestMapping(path = "/accounts", produces = APPLICATION_JSON_VALUE)
public class AccountsResource {

  private final AccountService accountService;

  public AccountsResource(AccountService accountService) {
    this.accountService = checkNotNull(accountService);
  }

  @PostMapping(consumes = APPLICATION_JSON_VALUE)
  // No @NotNull in the original either: an absent body reaches the handler as null.
  public ResponseEntity<Void> post(@Valid @RequestBody(required = false) AccountDto accountDto) {
    OpenAccountCommand command = new OpenAccountCommand(accountDto.getClientId());
    UUID accountId = accountService.process(command).getId();
    // Resolved against the base URI, as Jersey did: no query string, no trailing slash.
    URI accountUri = ServletUriComponentsBuilder.fromCurrentContextPath()
        .path("/accounts/{id}")
        .buildAndExpand(accountId)
        .toUri();
    return ResponseEntity.created(accountUri).build();
  }
}
