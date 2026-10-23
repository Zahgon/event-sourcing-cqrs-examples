package bankservice.it;

import static java.math.BigDecimal.TEN;
import static java.util.UUID.randomUUID;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

class WithdrawalsIT extends BaseIT {

  @Test
  void returnAccountNotFound() {
    ObjectNode deposit = resourcesDtos.withdrawalDto(TEN);
    ResponseEntity<JsonNode> response =
        resourcesClient.postWithdrawal(randomUUID().toString(), deposit);
    assertThat(response.getStatusCode().value(), equalTo(404));
  }

  @Test
  void withdrawAccount() {
    BigDecimal previousBalance = BigDecimal.valueOf(9.99);
    BigDecimal withdrawalAmount = BigDecimal.valueOf(5.55);
    BigDecimal expectedBalance = BigDecimal.valueOf(4.44);
    String accountId = stateSetup.newAccountWithBalance(randomUUID().toString(), previousBalance);
    {
      ObjectNode withdrawal = resourcesDtos.withdrawalDto(withdrawalAmount);
      ResponseEntity<JsonNode> response = resourcesClient.postWithdrawal(accountId, withdrawal);
      assertThat(response.getStatusCode().value(), equalTo(204));
    }
    {
      ResponseEntity<JsonNode> response = resourcesClient.getAccount(accountId);
      JsonNode account = response.getBody();
      assertThat(account.get("balance").asDouble(), equalTo(expectedBalance.doubleValue()));
      assertThat(response.getStatusCode().value(), equalTo(200));
    }
  }

  @Test
  void returnBadRequestForNonSufficientFunds() {
    String accountId = stateSetup.newAccount(randomUUID().toString());
    ObjectNode withdrawal = resourcesDtos.withdrawalDto(BigDecimal.valueOf(1000));
    ResponseEntity<JsonNode> response = resourcesClient.postWithdrawal(accountId, withdrawal);
    assertThat(response.getStatusCode().value(), equalTo(400));
  }
}
