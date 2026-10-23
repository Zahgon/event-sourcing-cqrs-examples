package bankservice.it.client;

import static com.google.common.util.concurrent.Uninterruptibles.sleepUninterruptibly;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.UncheckedIOException;
import java.net.URI;
import java.time.Duration;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

public class ResourcesClient {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private final TestRestTemplate restTemplate;
  private final ResourcesUrls resourcesUrls;

  public ResourcesClient(String baseUrl) {
    this.restTemplate = new TestRestTemplate(new RestTemplateBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .readTimeout(Duration.ofSeconds(30)));
    this.resourcesUrls = new ResourcesUrls(baseUrl);
  }

  public ResourcesUrls getResourcesUrls() {
    return resourcesUrls;
  }

  public ResponseEntity<JsonNode> postClient(JsonNode clientDto) {
    return exchange(resourcesUrls.clientsUrl(), HttpMethod.POST, json(clientDto));
  }

  public ResponseEntity<JsonNode> getClient(String clientId) {
    return exchange(resourcesUrls.clientUrl(clientId), HttpMethod.GET,
        HttpEntity.EMPTY);
  }

  public ResponseEntity<JsonNode> putClient(String clientId, JsonNode clientDto) {
    return exchange(resourcesUrls.clientUrl(clientId), HttpMethod.PUT,
        json(clientDto));
  }

  public ResponseEntity<JsonNode> postAccount(JsonNode accountDto) {
    return exchange(resourcesUrls.accountsUrl(), HttpMethod.POST, json(accountDto));
  }

  public ResponseEntity<JsonNode> getAccount(String accountId) {
    return exchange(resourcesUrls.accountUrl(accountId), HttpMethod.GET,
        HttpEntity.EMPTY);
  }

  public ResponseEntity<JsonNode> postDeposit(String accountId, JsonNode depositDto) {
    return exchange(resourcesUrls.depositsUrl(accountId), HttpMethod.POST,
        json(depositDto));
  }

  public ResponseEntity<JsonNode> postWithdrawal(String accountId, JsonNode withdrawalDto) {
    return exchange(resourcesUrls.withdrawalsUrl(accountId), HttpMethod.POST,
        json(withdrawalDto));
  }

  public ResponseEntity<JsonNode> getClientAccounts(String clientId) {
    return exchange(resourcesUrls.clientAccountsUrl(clientId), HttpMethod.GET,
        HttpEntity.EMPTY);
  }

  public ResponseEntity<JsonNode> getAccountTransactions(String accountId) {
    return exchange(resourcesUrls.accountTransactionsUrl(accountId), HttpMethod.GET,
        HttpEntity.EMPTY);
  }

  /**
   * The transactions once the asynchronously fed projection holds {@code count} rows, read for at
   * most five seconds; the last answer is returned either way.
   */
  public ResponseEntity<JsonNode> getAccountTransactions(String accountId, int count) {
    ResponseEntity<JsonNode> response = getAccountTransactions(accountId);
    for (int attempt = 0; attempt < 50; attempt++) {
      JsonNode rows = response.getBody();
      if (rows != null && rows.isArray() && rows.size() >= count) {
        return response;
      }
      sleepUninterruptibly(Duration.ofMillis(100));
      response = getAccountTransactions(accountId);
    }
    return response;
  }

  /**
   * Reads the entity as JSON only when the service declared it as JSON. Statuses the service
   * answers without an entity of its own carry the container's HTML error page instead.
   */
  private ResponseEntity<JsonNode> exchange(URI url, HttpMethod method, HttpEntity<?> entity) {
    ResponseEntity<String> response = restTemplate.exchange(url, method, entity, String.class);
    JsonNode body = null;
    MediaType type = response.getHeaders().getContentType();
    if (response.getBody() != null && type != null
        && type.isCompatibleWith(MediaType.APPLICATION_JSON)) {
      try {
        body = MAPPER.readTree(response.getBody());
      } catch (JsonProcessingException e) {
        throw new UncheckedIOException(e);
      }
    }
    return new ResponseEntity<>(body, response.getHeaders(), response.getStatusCode());
  }

  private static HttpEntity<JsonNode> json(JsonNode dto) {
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);
    return new HttpEntity<>(dto, headers);
  }
}
