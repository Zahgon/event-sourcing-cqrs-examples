package bankservice.it;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.core.IsEqual.equalTo;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.util.UriTemplate;

class ClientsIT extends BaseIT {

  @Test
  void newClient() {
    ObjectNode clientDto = resourcesDtos.clientDto("John", "john@example.com");
    ResponseEntity<JsonNode> response = resourcesClient.postClient(clientDto);
    assertThat(response.getStatusCode().value(), equalTo(201));
    UriTemplate clientUriTemplate = resourcesClient.getResourcesUrls().clientUriTemplate();
    assertTrue(clientUriTemplate.matches(response.getHeaders().getFirst(HttpHeaders.LOCATION)));
  }
}
