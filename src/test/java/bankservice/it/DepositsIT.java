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

class DepositsIT extends BaseIT {

  @Test
  void returnAccountNotFound() {
    ObjectNode deposit = resourcesDtos.depositDto(TEN);
    ResponseEntity<JsonNode> response =
        resourcesClient.postDeposit(randomUUID().toString(), deposit);
    assertThat(response.getStatusCode().value(), equalTo(404));
  }

  @Test
  void depositAccount() {
    String accountId = stateSetup.newAccount(randomUUID().toString());
    BigDecimal amount = TEN;
    {
      ObjectNode deposit = resourcesDtos.depositDto(amount);
      ResponseEntity<JsonNode> response = resourcesClient.postDeposit(accountId, deposit);
      assertThat(response.getStatusCode().value(), equalTo(204));
    }
    {
      ResponseEntity<JsonNode> response = resourcesClient.getAccount(accountId);
      JsonNode account = response.getBody();
      assertThat(account.get("balance").asDouble(), equalTo(amount.doubleValue()));
      assertThat(response.getStatusCode().value(), equalTo(200));
    }
  }
}
