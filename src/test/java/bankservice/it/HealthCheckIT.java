package bankservice.it;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.core.IsEqual.equalTo;

import java.net.URI;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.ResponseEntity;
import org.springframework.web.util.UriComponentsBuilder;

class HealthCheckIT extends BaseIT {

  private TestRestTemplate client;

  @BeforeEach
  void setUpClass() {
    client = new TestRestTemplate(new RestTemplateBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .readTimeout(Duration.ofSeconds(30)));
  }

  @Test
  void overallHealth() {
    ResponseEntity<String> response = client.getForEntity(healthCheckUrl(), String.class);
    assertThat(response.getStatusCode().value(), equalTo(200));
  }

  /**
   * Dropwizard's admin health check renders its registry as
   * {@code {"deadlocks":{"duration":N,"healthy":true,"timestamp":"..."}}} on
   * {@code application/json}. Spring Boot's stock endpoint answers {@code {"status":"UP"}} on
   * {@code application/vnd.spring-boot.actuator.v3+json}, so asserting the status alone -- as the
   * inherited test does -- cannot tell the two contracts apart.
   */
  @Test
  void healthCheckKeepsTheDropwizardRegistryShape() {
    ResponseEntity<String> response = client.getForEntity(healthCheckUrl(), String.class);
    assertThat(response.getStatusCode().value(), equalTo(200));
    assertThat(response.getHeaders().getContentType().toString(),
        equalTo("application/json"));
    assertThat(response.getBody().startsWith("{\"deadlocks\":{"), equalTo(true));
    assertThat(response.getBody().contains("\"healthy\":true"), equalTo(true));
    assertThat(response.getBody().contains("\"duration\":"), equalTo(true));
    assertThat(response.getBody().contains("\"timestamp\":"), equalTo(true));
    assertThat(response.getBody().contains("\"status\""), equalTo(false));
  }

  private URI healthCheckUrl() {
    return UriComponentsBuilder.fromUriString(adminUrl()).path("/healthcheck").build().toUri();
  }
}
