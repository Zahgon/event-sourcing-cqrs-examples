package bankservice.port.incoming.adapter.resources;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.http.MediaType.APPLICATION_JSON_VALUE;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import bankservice.domain.model.OptimisticLockingException;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

class OptimisticLockingExceptionMapperTest {

  static final MockMvc RESOURCES = MockMvcBuilders
      .standaloneSetup(new ConcurrentlyModifiedResource())
      .setControllerAdvice(new OptimisticLockingExceptionMapper())
      .build();

  @Test
  void returnConflict() throws Exception {
    MvcResult response = RESOURCES
        .perform(put("/concurrently-modified-resource")
            .contentType(APPLICATION_JSON)
            .content("{}"))
        .andReturn();
    assertThat(response.getResponse().getStatus(), equalTo(409));
  }

  @RestController
  @RequestMapping(path = "/concurrently-modified-resource")
  public static class ConcurrentlyModifiedResource {

    @PutMapping(consumes = APPLICATION_JSON_VALUE)
    public ResponseEntity<Void> put(@RequestBody String entity) throws OptimisticLockingException {
      throw new OptimisticLockingException("Testing exception mapper");
    }
  }
}
