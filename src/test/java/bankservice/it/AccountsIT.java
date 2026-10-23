package bankservice.it;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.core.IsEqual.equalTo;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.util.UriTemplate;

class AccountsIT extends BaseIT {

  @Test
  void newAccount() {
    String clientId = stateSetup.newClient("Joan", "joan@example.com");
    ResponseEntity<JsonNode> response =
        resourcesClient.postAccount(resourcesDtos.accountDto(clientId));
    assertThat(response.getStatusCode().value(), equalTo(201));
    UriTemplate accountUriTemplate = resourcesClient.getResourcesUrls().accountUriTemplate();
    assertTrue(accountUriTemplate.matches(response.getHeaders().getFirst(HttpHeaders.LOCATION)));
  }
}
