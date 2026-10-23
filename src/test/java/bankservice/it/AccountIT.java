package bankservice.it;

import static java.util.UUID.randomUUID;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

class AccountIT extends BaseIT {

  @Test
  void returnAccount() {
    String clientId = UUID.randomUUID().toString();
    String accountId = stateSetup.newAccount(clientId);
    ResponseEntity<JsonNode> response = resourcesClient.getAccount(accountId);
    JsonNode account = response.getBody();
    assertThat(account.get("id").asText(), equalTo(accountId));
    assertThat(account.get("clientId").asText(), equalTo(clientId));
    assertThat(account.get("balance").asDouble(), equalTo(0.0));
    assertThat(response.getStatusCode().value(), equalTo(200));
  }

  @Test
  void returnAccountNotFound() {
    ResponseEntity<JsonNode> response = resourcesClient.getAccount(randomUUID().toString());
    assertThat(response.getStatusCode().value(), equalTo(404));
  }
}
