package bankservice.it.setup;

import static com.google.common.base.Preconditions.checkNotNull;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertTrue;

import bankservice.it.client.ResourcesClient;
import bankservice.it.client.ResourcesDtos;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.util.UriTemplate;

public class StateSetup {

  private final ResourcesClient resourcesClient;
  private final ResourcesDtos resourcesDtos;

  public StateSetup(ResourcesClient resourcesClient, ResourcesDtos resourcesDtos) {
    this.resourcesClient = checkNotNull(resourcesClient);
    this.resourcesDtos = checkNotNull(resourcesDtos);
  }

  public String newClient(String name, String email) {
    ObjectNode clientDto = resourcesDtos.clientDto(name, email);
    ResponseEntity<JsonNode> response = resourcesClient.postClient(clientDto);
    assertThat(response.getStatusCode().value(), equalTo(201));
    UriTemplate clientUriTemplate = resourcesClient.getResourcesUrls().clientUriTemplate();
    String location = response.getHeaders().getFirst(HttpHeaders.LOCATION);
    assertTrue(clientUriTemplate.matches(location));
    return clientUriTemplate.match(location).get("id");
  }

  public String newAccount(String clientId) {
    ResponseEntity<JsonNode> response =
        resourcesClient.postAccount(resourcesDtos.accountDto(clientId));
    assertThat(response.getStatusCode().value(), equalTo(201));
    UriTemplate accountUriTemplate = resourcesClient.getResourcesUrls().accountUriTemplate();
    String location = response.getHeaders().getFirst(HttpHeaders.LOCATION);
    assertTrue(accountUriTemplate.matches(location));
    return accountUriTemplate.match(location).get("id");
  }

  public String newAccountWithBalance(String clientId, BigDecimal balance) {
    String accountId = newAccount(clientId);
    ObjectNode deposit = resourcesDtos.depositDto(balance);
    ResponseEntity<JsonNode> response = resourcesClient.postDeposit(accountId, deposit);
    assertThat(response.getStatusCode().value(), equalTo(204));
    return accountId;
  }
}
