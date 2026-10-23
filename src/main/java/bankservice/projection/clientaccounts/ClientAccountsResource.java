package bankservice.projection.clientaccounts;

import static com.google.common.base.Preconditions.checkNotNull;
import static org.springframework.http.MediaType.APPLICATION_JSON_VALUE;

import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(path = "/clients/{id}/accounts", produces = APPLICATION_JSON_VALUE)
public class ClientAccountsResource {

  private final AccountsRepository accountsRepository;

  public ClientAccountsResource(AccountsRepository accountsRepository) {
    this.accountsRepository = checkNotNull(accountsRepository);
  }

  @GetMapping
  public ResponseEntity<List<AccountProjection>> get(@PathVariable("id") UUID clientId) {
    List<AccountProjection> accounts = accountsRepository.getAccounts(clientId);
    return ResponseEntity.ok(accounts);
  }
}
