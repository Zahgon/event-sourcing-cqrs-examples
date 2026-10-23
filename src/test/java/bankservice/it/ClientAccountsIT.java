package bankservice.it;

import static com.google.common.util.concurrent.Uninterruptibles.sleepUninterruptibly;
import static java.math.BigDecimal.TEN;
import static java.util.Collections.emptyMap;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

class ClientAccountsIT extends BaseIT {

  @Test
  void returnClientAccounts() {
    String clientId = UUID.randomUUID().toString();
    verifyClientAccounts(clientId, emptyMap());

    String accountId1 = stateSetup.newAccountWithBalance(clientId, BigDecimal.valueOf(100));
    String accountId2 = stateSetup.newAccountWithBalance(clientId, BigDecimal.valueOf(100));
    String accountId3 = stateSetup.newAccountWithBalance(clientId, BigDecimal.valueOf(100));
    resourcesClient.postWithdrawal(accountId2, resourcesDtos.withdrawalDto(TEN));
    resourcesClient.postDeposit(accountId3, resourcesDtos.depositDto(BigDecimal.valueOf(0.01)));

    Map<String, BigDecimal> expectedAccountsBalances = new HashMap<>();
    expectedAccountsBalances.put(accountId1, BigDecimal.valueOf(100.0));
    expectedAccountsBalances.put(accountId2, BigDecimal.valueOf(90.0));
    expectedAccountsBalances.put(accountId3, BigDecimal.valueOf(100.01));
    verifyClientAccounts(clientId, expectedAccountsBalances);
  }

  /**
   * The client-accounts projection is fed asynchronously, so it is read until it holds exactly the
   * expected balances (for at most five seconds).
   */
  private void verifyClientAccounts(String clientId,
      Map<String, BigDecimal> expectedAccountsBalances) {
    Map<String, BigDecimal> balances = Map.of();
    for (int attempt = 0; attempt < 50; attempt++) {
      ResponseEntity<JsonNode> response = resourcesClient.getClientAccounts(clientId);
      JsonNode accounts = response.getBody();
      assertThat(response.getStatusCode().value(), equalTo(200));
      assertThat(accounts.isArray(), equalTo(true));
      balances = new HashMap<>();
      for (JsonNode account : accounts) {
        balances.put(account.get("accountId").asText(),
            new BigDecimal(account.get("balance").asText()));
      }
      if (balances.equals(expectedAccountsBalances)) {
        return;
      }
      sleepUninterruptibly(Duration.ofMillis(100));
    }
    assertThat(balances, equalTo(expectedAccountsBalances));
  }
}
