package bankservice.port.incoming.adapter.resources.accounts;

import static bankservice.port.incoming.adapter.resources.accounts.AccountNotFoundExceptionMapperTest.NeverFoundAccountResource.calls;
import static java.lang.String.format;
import static java.util.UUID.randomUUID;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.springframework.http.MediaType.APPLICATION_JSON_VALUE;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import bankservice.service.account.AccountNotFoundException;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

class AccountNotFoundExceptionMapperTest {

  static final MockMvc RESOURCES = MockMvcBuilders
      .standaloneSetup(new NeverFoundAccountResource())
      .setControllerAdvice(new AccountNotFoundExceptionMapper())
      .build();

  @Test
  void returnNotFound() throws Exception {
    MvcResult response = RESOURCES
        .perform(get(format("/never-found-account-resource/%s", randomUUID())))
        .andReturn();
    assertThat(response.getResponse().getStatus(), equalTo(404));
    assertThat(calls, equalTo(1));
  }

  @RestController
  @RequestMapping(path = "/never-found-account-resource/{id}", produces = APPLICATION_JSON_VALUE)
  public static class NeverFoundAccountResource {

    static int calls = 0;

    @GetMapping
    public ResponseEntity<Void> get(@PathVariable("id") String id) throws AccountNotFoundException {
      calls++;
      throw new AccountNotFoundException(UUID.fromString(id));
    }
  }
}
