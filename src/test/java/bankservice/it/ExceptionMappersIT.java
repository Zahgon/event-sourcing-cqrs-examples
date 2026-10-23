package bankservice.it;


import static java.math.BigDecimal.TEN;
import static java.util.UUID.randomUUID;
import static java.util.stream.Collectors.toSet;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertTrue;

import bankservice.port.incoming.adapter.resources.DropwizardErrorMapper;
import bankservice.port.incoming.adapter.resources.OptimisticLockingExceptionMapper;
import bankservice.port.incoming.adapter.resources.ValidationExceptionMapper;
import bankservice.port.incoming.adapter.resources.accounts.AccountNotFoundExceptionMapper;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.web.bind.annotation.RestControllerAdvice;

class ExceptionMappersIT extends BaseIT {

  @Autowired
  private ApplicationContext applicationContext;

  @Test
  void registeredExceptionMappers() {
    Set<Class<?>> classes = applicationContext.getBeansWithAnnotation(RestControllerAdvice.class)
        .values().stream().map(Object::getClass).collect(toSet());
    assertTrue(classes.contains(OptimisticLockingExceptionMapper.class));
    assertTrue(classes.contains(AccountNotFoundExceptionMapper.class));
    assertTrue(classes.contains(ValidationExceptionMapper.class));
    assertTrue(classes.contains(DropwizardErrorMapper.class));
    // The mappers are also observable on the service under test, which may be one already
    // running: an unknown account on a movement is the mapped 404, not the logged 500.
    String unknownAccount = randomUUID().toString();
    assertThat(resourcesClient.postDeposit(unknownAccount, resourcesDtos.depositDto(TEN))
        .getStatusCode().value(), equalTo(404));
    assertThat(resourcesClient.postWithdrawal(unknownAccount, resourcesDtos.withdrawalDto(TEN))
        .getStatusCode().value(), equalTo(404));
  }
}
