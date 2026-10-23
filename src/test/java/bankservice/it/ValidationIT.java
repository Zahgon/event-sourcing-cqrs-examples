package bankservice.it;

import static java.util.UUID.randomUUID;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

public class ValidationIT extends BaseIT {

  @Test
  public void returnUnprocessableEntityForInvalidEmail() {
    JsonNode clientDto = resourcesDtos.clientDto("John", "not-an-email");
    ResponseEntity<JsonNode> response = resourcesClient.postClient(clientDto);
    assertThat(response.getStatusCode().value(), is(422));
    assertThat(response.getBody().get("errors").get(0).textValue(),
        is("email not a well-formed email address"));
  }

  @Test
  public void returnUnprocessableEntityForMissingBody() {
    ResponseEntity<JsonNode> response = resourcesClient.putClient(randomUUID().toString(), null);
    assertThat(response.getStatusCode().value(), is(422));
    assertThat(response.getBody().get("errors").get(0).textValue(),
        is("The request body must not be null"));
  }
}
