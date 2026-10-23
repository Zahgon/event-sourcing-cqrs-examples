package bankservice.it;

import static com.google.common.util.concurrent.Uninterruptibles.sleepUninterruptibly;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.startsWith;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.time.Duration;
import java.util.UUID;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * Pins the HTTP wire contract the Dropwizard/Jersey source served, on the seams where Spring MVC's
 * defaults differ. Every assertion here was a live divergence between the two packaged artifacts
 * before the corresponding fix landed.
 *
 * <p>This drives a real socket through {@link HttpClient} rather than MockMvc on purpose: the
 * behaviours under test are produced by the servlet container and the framework's own error
 * plumbing, and an in-process test double reports the response the framework <em>would</em> have
 * built rather than the bytes the server writes. Requests with a deliberately absent Content-Type
 * or a deliberately malformed body cannot be expressed through the typed client at all.
 */
class WireContractIT extends BaseIT {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private final HttpClient client = HttpClient.newHttpClient();

  private HttpResponse<String> send(HttpRequest request) throws IOException, InterruptedException {
    return client.send(request, BodyHandlers.ofString());
  }

  private HttpRequest.Builder request(String path) {
    return HttpRequest.newBuilder(URI.create(applicationUrl() + path));
  }

  private JsonNode body(HttpResponse<String> response) throws IOException {
    return MAPPER.readTree(response.body());
  }

  /**
   * Jersey answers 404 when a raw {@code @PathParam UUID} fails to convert; Spring MVC's
   * {@code @PathVariable} conversion failure is a 400. {@code AccountTransactionsResource} is the
   * one source resource that took a raw {@code UUID} instead of Dropwizard's {@code UUIDParam}.
   */
  @Test
  void malformedUuidOnTransactionsIsNotFound() throws Exception {
    HttpResponse<String> response =
        send(request("/accounts/not-a-uuid/transactions").GET().build());

    assertThat(response.statusCode(), is(404));
  }

  /**
   * The four resources that used {@code UUIDParam} answer 400 with Dropwizard's envelope.
   */
  @Test
  void malformedUuidOnAccountIsBadRequestWithDropwizardEnvelope() throws Exception {
    HttpResponse<String> response = send(request("/accounts/not-a-uuid").GET().build());

    assertThat(response.statusCode(), is(400));
    assertThat(response.headers().firstValue("Content-Type").orElseThrow(),
        startsWith("application/json"));
    assertThat(body(response).get("code").asInt(), is(400));
    assertThat(body(response).get("message").asText(), is("path param id is not a UUID."));
  }

  /**
   * Jersey treats an absent Content-Type as matching any {@code @Consumes}, so the request reaches
   * the handler. Spring MVC reads it as {@code application/octet-stream} and answers 415 before the
   * handler runs. The source answered 500 here, from the NPE in the next test.
   */
  @Test
  void absentContentTypeStillDispatchesToTheHandler() throws Exception {
    HttpResponse<String> response =
        send(request("/clients").POST(BodyPublishers.noBody()).build());

    assertThat(response.statusCode(), is(500));
  }

  /**
   * {@code ClientsResource.post} carries no {@code @NotNull}, so upstream a null DTO reached the
   * handler and NPE'd into a 500. Spring's {@code @RequestBody} is mandatory by default, which
   * "repaired" this to a 422 - an improvement, and therefore a regression against the instruction
   * to preserve quirks.
   */
  @Test
  void emptyBodyOnPostClientsIsTheUpstreamFiveHundredQuirk() throws Exception {
    HttpResponse<String> response = send(request("/clients")
        .header("Content-Type", "application/json")
        .POST(BodyPublishers.noBody())
        .build());

    assertThat(response.statusCode(), is(500));
    assertThat(body(response).get("code").asInt(), is(500));
    assertThat(body(response).get("message").asText(),
        matchesPattern("There was an error processing your request\\. "
            + "It has been logged \\(ID [0-9a-f]{16}\\)\\."));
  }

  /**
   * {@code ClientResource.put} does carry {@code @NotNull}, so the same empty body is a validation
   * failure there, not an NPE - and it must stay 422 even with no Content-Type header.
   */
  @Test
  void emptyBodyOnPutClientIsUnprocessableEntityEvenWithoutContentType() throws Exception {
    HttpResponse<String> response = send(request("/clients/" + UUID.randomUUID())
        .PUT(BodyPublishers.noBody())
        .build());

    assertThat(response.statusCode(), is(422));
    assertThat(body(response).get("errors").get(0).asText(),
        is("The request body must not be null"));
  }

  /**
   * Jersey's {@code JsonProcessingExceptionMapper} envelope for a body it cannot parse.
   */
  @Test
  void unparseableJsonIsBadRequestWithDropwizardEnvelope() throws Exception {
    HttpResponse<String> response = send(request("/clients")
        .header("Content-Type", "application/json")
        .POST(BodyPublishers.ofString("{not json"))
        .build());

    assertThat(response.statusCode(), is(400));
    assertThat(body(response).get("code").asInt(), is(400));
    assertThat(body(response).get("message").asText(),
        is("Unable to process JSON"));
  }

  /**
   * Dropwizard's {@code LoggingExceptionMapper} envelope for an unserved method.
   */
  @Test
  void wrongMethodIsMethodNotAllowedWithDropwizardEnvelope() throws Exception {
    HttpResponse<String> response = send(request("/clients").DELETE().build());

    assertThat(response.statusCode(), is(405));
    assertThat(body(response).get("code").asInt(), is(405));
    assertThat(body(response).get("message").asText(), is("HTTP 405 Method Not Allowed"));
  }

  /** Same envelope on a representation the resource cannot produce. */
  @Test
  void unacceptableMediaTypeIsNotAcceptableWithDropwizardEnvelope() throws Exception {
    HttpResponse<String> response = send(request("/clients/" + UUID.randomUUID())
        .header("Accept", "application/xml")
        .GET()
        .build());

    assertThat(response.statusCode(), is(406));
    assertThat(body(response).get("code").asInt(), is(406));
    assertThat(body(response).get("message").asText(), is("HTTP 406 Not Acceptable"));
  }

  /**
   * Dropwizard ships Jackson with {@code WRITE_DATES_AS_TIMESTAMPS} enabled; Spring Boot disables
   * it. {@code TransactionProjection.timestamp} is a {@code ZonedDateTime}, so the default flip
   * silently changes that field from a JSON number to an ISO-8601 string - the single most
   * consequential payload change for a consumer, and one the inherited suite could not see because
   * it only asserted the field was non-null.
   */
  @Test
  void transactionTimestampIsSerialisedAsANumber() throws Exception {
    String clientId = createClient();
    String accountId = createAccount(clientId);
    deposit(accountId);

    HttpResponse<String> response = transactionsOnceProjected(accountId, 1);

    assertThat(response.statusCode(), is(200));
    JsonNode transactions = body(response);
    assertThat(transactions.isArray(), is(true));
    assertThat(transactions.size(), is(1));

    JsonNode timestamp = transactions.get(0).get("timestamp");
    assertThat("timestamp must stay a JSON number, as Dropwizard served it, not an ISO string",
        timestamp.isNumber(), is(true));
  }

  /** The transactions endpoint returns a JSON array, not merely something with a size. */
  @Test
  void transactionsForAnUnknownAccountIsAnEmptyArray() throws Exception {
    HttpResponse<String> response =
        send(request("/accounts/" + UUID.randomUUID() + "/transactions").GET().build());

    assertThat(response.statusCode(), is(200));
    assertThat(body(response).isArray(), is(true));
    assertThat(body(response).size(), is(0));
  }

  private String createClient() throws Exception {
    HttpResponse<String> response = send(request("/clients")
        .header("Content-Type", "application/json")
        .POST(BodyPublishers.ofString("{\"name\":\"wire\",\"email\":\"wire@example.com\"}"))
        .build());
    assertThat(response.statusCode(), is(201));
    return lastPathSegment(response);
  }

  private String createAccount(String clientId) throws Exception {
    HttpResponse<String> response = send(request("/accounts")
        .header("Content-Type", "application/json")
        .POST(BodyPublishers.ofString("{\"clientId\":\"" + clientId + "\",\"type\":\"CHECKING\"}"))
        .build());
    assertThat(response.statusCode(), is(201));
    return lastPathSegment(response);
  }

  private void deposit(String accountId) throws Exception {
    HttpResponse<String> response = send(request("/accounts/" + accountId + "/deposits")
        .header("Content-Type", "application/json")
        .POST(BodyPublishers.ofString("{\"amount\":100.0}"))
        .build());
    assertThat(response.statusCode(), is(204));
  }

  /**
   * The transactions projection is fed asynchronously: read it until it holds {@code count} rows,
   * for at most five seconds.
   */
  private HttpResponse<String> transactionsOnceProjected(String accountId, int count)
      throws Exception {
    HttpResponse<String> response = null;
    for (int attempt = 0; attempt < 50; attempt++) {
      response = send(request("/accounts/" + accountId + "/transactions").GET().build());
      if (response.statusCode() != 200 || body(response).size() >= count) {
        return response;
      }
      sleepUninterruptibly(Duration.ofMillis(100));
    }
    return response;
  }

  private String lastPathSegment(HttpResponse<String> response) {
    String location = response.headers().firstValue("Location").orElseThrow();
    assertThat(location, matchesPattern(".*/[0-9a-f-]{36}$"));
    return location.substring(location.lastIndexOf('/') + 1);
  }

  /** Guards the helper above against a silently reshaped Location header. */
  @Test
  void createdResourcesAdvertiseAbsoluteLocationHeaders() throws Exception {
    HttpResponse<String> response = send(request("/clients")
        .header("Content-Type", "application/json")
        .POST(BodyPublishers.ofString("{\"name\":\"loc\",\"email\":\"loc@example.com\"}"))
        .build());

    assertThat(response.statusCode(), is(201));
    assertThat(response.headers().firstValue("Location").orElseThrow(),
        matchesPattern(Pattern.quote(applicationUrl()) + "/clients/[0-9a-f-]{36}"));
  }

  /** The 422 validation envelope Dropwizard produced, reproduced byte-for-byte. */
  @Test
  void invalidEmailIsUnprocessableEntityWithTheDropwizardValidationEnvelope() throws Exception {
    HttpResponse<String> response = send(request("/clients")
        .header("Content-Type", "application/json")
        .POST(BodyPublishers.ofString("{\"name\":\"x\",\"email\":\"not-an-email\"}"))
        .build());

    assertThat(response.statusCode(), is(422));
    assertThat(body(response).get("errors").get(0).asText(),
        equalTo("email not a well-formed email address"));
  }
}
