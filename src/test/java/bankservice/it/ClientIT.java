package bankservice.it;

import static java.util.UUID.randomUUID;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.core.IsEqual.equalTo;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

class ClientIT extends BaseIT {

  @Test
  void returnClient() {
    String name = "Jensen";
    String email = "jensen@example.com";
    String clientId = stateSetup.newClient(name, email);
    ResponseEntity<JsonNode> response = resourcesClient.getClient(clientId);
    JsonNode client = response.getBody();
    assertThat(client.get("id").textValue(), equalTo(clientId));
    assertThat(client.get("name").textValue(), equalTo(name));
    assertThat(client.get("email").textValue(), equalTo(email));
  }

  @Test
  void returnClientNotFound() {
    ResponseEntity<JsonNode> response = resourcesClient.getClient(randomUUID().toString());
    assertThat(response.getStatusCode().value(), equalTo(404));
  }

  @Test
  void updateClient() {
    String clientId = stateSetup.newClient("Jaya", "jaya@example.com");
    String newName = "Jayan";
    String newEmail = "jayan@example.com";
    {
      ObjectNode clientDto = resourcesDtos.clientDto(newName, newEmail);
      ResponseEntity<JsonNode> response = resourcesClient.putClient(clientId, clientDto);
      assertThat(response.getStatusCode().value(), equalTo(204));
    }
    {
      ResponseEntity<JsonNode> response = resourcesClient.getClient(clientId);
      JsonNode clientDto = response.getBody();
      assertThat(clientDto.get("name").textValue(), equalTo(newName));
      assertThat(clientDto.get("email").textValue(), equalTo(newEmail));
    }
  }
}
