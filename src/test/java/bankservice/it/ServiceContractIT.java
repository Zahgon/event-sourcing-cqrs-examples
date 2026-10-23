package bankservice.it;

import static java.util.UUID.randomUUID;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import org.junit.jupiter.api.Test;

/**
 * The parts of the contract a typed client cannot see: exact entity text and header fields.
 */
class ServiceContractIT extends BaseIT {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private final HttpClient client = HttpClient.newHttpClient();

  @Test
  void accountBalanceKeepsTwoDecimals() throws Exception {
    String accountId = openAccount();
    assertThat(move(accountId, "deposits", "100").statusCode(), is(204));
    assertThat(move(accountId, "withdrawals", "0.5").statusCode(), is(204));

    HttpResponse<String> account = get("/accounts/" + accountId);

    assertThat(account.statusCode(), is(200));
    assertThat(account.body(), containsString("\"balance\":99.50"));
  }

  @Test
  void unknownAccountAnswersTheContainerErrorPage() throws Exception {
    String path = "/accounts/" + randomUUID();

    HttpResponse<String> response = get(path);

    assertThat(response.statusCode(), is(404));
    assertThat(response.headers().firstValue("Content-Type").orElse(""),
        equalTo("text/html;charset=iso-8859-1"));
    assertThat(response.body(), containsString("<h2>HTTP ERROR 404 Not Found</h2>"));
    assertThat(response.body(), containsString("<tr><th>URI:</th><td>" + path + "</td></tr>"));
  }

  @Test
  void unroutedPathAnswersTheErrorEnvelope() throws Exception {
    HttpResponse<String> response = get("/no-such-route");

    assertThat(response.statusCode(), is(404));
    JsonNode body = MAPPER.readTree(response.body());
    assertThat(body.size(), is(2));
    assertThat(body.get("code").asInt(), is(404));
    assertThat(body.get("message").asText(), equalTo("HTTP 404 Not Found"));
  }

  private String openAccount() throws Exception {
    HttpResponse<String> response = send(request("/accounts")
        .header("Content-Type", "application/json")
        .POST(BodyPublishers.ofString("{\"clientId\":\"" + randomUUID() + "\"}"))
        .build());
    assertThat(response.statusCode(), is(201));
    String location = response.headers().firstValue("Location").orElseThrow();
    return location.substring(location.lastIndexOf('/') + 1);
  }

  private HttpResponse<String> move(String accountId, String movement, String amount)
      throws Exception {
    return send(request("/accounts/" + accountId + "/" + movement)
        .header("Content-Type", "application/json")
        .POST(BodyPublishers.ofString("{\"amount\":" + amount + "}"))
        .build());
  }

  private HttpResponse<String> get(String path) throws Exception {
    return send(request(path).GET().build());
  }

  private HttpRequest.Builder request(String path) {
    return HttpRequest.newBuilder(URI.create(applicationUrl() + path));
  }

  private HttpResponse<String> send(HttpRequest request) throws IOException, InterruptedException {
    return client.send(request, BodyHandlers.ofString());
  }
}
