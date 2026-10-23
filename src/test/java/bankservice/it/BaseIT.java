package bankservice.it;

import bankservice.bootstrap.BankServiceApplication;
import bankservice.it.client.ResourcesClient;
import bankservice.it.client.ResourcesDtos;
import bankservice.it.setup.StateSetup;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;

@SpringBootTest(classes = BankServiceApplication.class, webEnvironment = WebEnvironment.RANDOM_PORT,
    properties = "management.server.port=0")
abstract class BaseIT {

  /**
   * Set together with {@link #ADMIN_URL} to run the suite against a service already running.
   */
  static final String APPLICATION_URL = "bankservice.it.baseUrl";
  static final String ADMIN_URL = "bankservice.it.adminUrl";

  @Value("${local.server.port}")
  private int port;

  @Value("${local.management.port}")
  private int managementPort;

  @Autowired
  private ObjectMapper objectMapper;

  protected ResourcesClient resourcesClient;
  protected ResourcesDtos resourcesDtos;
  protected StateSetup stateSetup;

  @BeforeEach
  public void setUpBaseClass() {
    resourcesClient = new ResourcesClient(applicationUrl());
    resourcesDtos = new ResourcesDtos(objectMapper);
    stateSetup = new StateSetup(resourcesClient, resourcesDtos);
  }

  /** The application port of the service under test, without a trailing slash. */
  protected String applicationUrl() {
    return System.getProperty(APPLICATION_URL, "http://localhost:" + port);
  }

  /** The admin port of the service under test, without a trailing slash. */
  protected String adminUrl() {
    return System.getProperty(ADMIN_URL, "http://localhost:" + managementPort);
  }
}
