package bankservice.it;

import static java.math.BigDecimal.ONE;
import static java.math.BigDecimal.TEN;
import static java.util.UUID.randomUUID;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

class AccountTransactionsIT extends BaseIT {

  @Test
  void returnEmptyTransactions() {
    ResponseEntity<JsonNode> response =
        resourcesClient.getAccountTransactions(randomUUID().toString());
    JsonNode transactions = response.getBody();
    assertThat(transactions.isArray(), equalTo(true));
    assertThat(transactions.size(), equalTo(0));
    assertThat(response.getStatusCode().value(), equalTo(200));
  }

  @Test
  void returnTransactions() {
    String accountId = stateSetup.newAccount(randomUUID().toString());
    resourcesClient.postDeposit(accountId, resourcesDtos.depositDto(BigDecimal.valueOf(99)));
    resourcesClient.postDeposit(accountId, resourcesDtos.depositDto(ONE));
    resourcesClient.postWithdrawal(accountId, resourcesDtos.withdrawalDto(TEN));

    ResponseEntity<JsonNode> response = resourcesClient.getAccountTransactions(accountId, 3);
    JsonNode transactions = response.getBody();
    assertThat(transactions.isArray(), equalTo(true));
    assertThat(transactions.size(), equalTo(3));
    verifyTransaction(transactions.get(0), "DEPOSIT", 99.0);
    verifyTransaction(transactions.get(1), "DEPOSIT", 1.0);
    verifyTransaction(transactions.get(2), "WITHDRAWAL", 10.0);
    assertThat(response.getStatusCode().value(), equalTo(200));
  }

  private void verifyTransaction(JsonNode transaction, String type, double amount) {
    assertThat(transaction.get("type").asText(), equalTo(type));
    assertThat(transaction.get("amount").asDouble(), equalTo(amount));
    assertThat(transaction.get("timestamp"), notNullValue());
    // Dropwizard serialises ZonedDateTime as a numeric epoch (WRITE_DATES_AS_TIMESTAMPS on).
    // notNullValue() alone passes for an ISO string too, which is how the type change hid.
    assertThat(transaction.get("timestamp").isNumber(), equalTo(true));
  }
}
