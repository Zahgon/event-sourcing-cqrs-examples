package bankservice.bootstrap;

import bankservice.domain.model.EventStore;
import bankservice.port.incoming.adapter.resources.AllowedMethodsFilter;
import bankservice.port.incoming.adapter.resources.ConsumedMediaTypeInterceptor;
import bankservice.port.incoming.adapter.resources.DropwizardErrorMapper;
import bankservice.port.incoming.adapter.resources.HeadEntityLengthFilter;
import bankservice.port.incoming.adapter.resources.JettyErrorPageFilter;
import bankservice.port.incoming.adapter.resources.JettyGzipFilter;
import bankservice.port.incoming.adapter.resources.JettyRequestLog;
import bankservice.port.incoming.adapter.resources.LogbackEventMeters;
import bankservice.port.incoming.adapter.resources.OptimisticLockingExceptionMapper;
import bankservice.port.incoming.adapter.resources.OptionsEntityFilter;
import bankservice.port.incoming.adapter.resources.RequestLogFilter;
import bankservice.port.incoming.adapter.resources.RequestMetrics;
import bankservice.port.incoming.adapter.resources.RequestMetricsFilter;
import bankservice.port.incoming.adapter.resources.ResponseFieldFilter;
import bankservice.port.incoming.adapter.resources.UuidPathParams;
import bankservice.port.incoming.adapter.resources.ValidationExceptionMapper;
import bankservice.port.incoming.adapter.resources.accounts.AccountNotFoundExceptionMapper;
import bankservice.port.incoming.adapter.resources.accounts.AccountResource;
import bankservice.port.incoming.adapter.resources.accounts.AccountsResource;
import bankservice.port.incoming.adapter.resources.accounts.deposits.DepositsResource;
import bankservice.port.incoming.adapter.resources.accounts.withdrawals.WithdrawalsResource;
import bankservice.port.incoming.adapter.resources.clients.ClientResource;
import bankservice.port.incoming.adapter.resources.clients.ClientsResource;
import bankservice.port.outgoing.adapter.eventstore.InMemoryEventStore;
import bankservice.projection.accounttransactions.AccountTransactionsResource;
import bankservice.projection.accounttransactions.InMemoryTransactionsRepository;
import bankservice.projection.accounttransactions.TransactionsListener;
import bankservice.projection.accounttransactions.TransactionsRepository;
import bankservice.projection.clientaccounts.AccountsListener;
import bankservice.projection.clientaccounts.AccountsRepository;
import bankservice.projection.clientaccounts.ClientAccountsResource;
import bankservice.projection.clientaccounts.InMemoryAccountsRepository;
import bankservice.service.account.AccountService;
import bankservice.service.client.ClientService;
import com.google.common.eventbus.EventBus;
import jakarta.servlet.Filter;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.web.servlet.DispatcherServletAutoConfiguration;
import org.springframework.boot.autoconfigure.web.servlet.error.ErrorMvcAutoConfiguration;
import org.springframework.boot.web.embedded.jetty.JettyServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.web.servlet.DispatcherServlet;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.PathMatchConfigurer;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.util.pattern.PathPatternParser;

// Jersey owned every path, so /error was as unrouted as any other; Spring Boot's error controller
// would answer it with its own body. The container's error pages are reproduced by the filters.
@SpringBootApplication(exclude = ErrorMvcAutoConfiguration.class)
public class BankServiceApplication {

  public static void main(String[] args) {
    SpringApplication.run(BankServiceApplication.class, args);
  }

  /**
   * Dropwizard's instrumented Logback appender; the admin port's {@code /metrics} reads it. The
   * context closes it, detaching it from the root logger.
   */
  @Bean
  public LogbackEventMeters logbackEventMeters() {
    return LogbackEventMeters.attachToRootLogger();
  }

  /**
   * Dropwizard's request log on the application connector.
   */
  @Bean
  public WebServerFactoryCustomizer<JettyServletWebServerFactory> requestLog() {
    return JettyRequestLog.customizer();
  }

  /**
   * Dropwizard's instrumented handler on the application connector; {@code /metrics} reads it.
   */
  @Bean
  public RequestMetrics requestMetrics() {
    return new RequestMetrics();
  }

  // exception mappers
  @Bean
  public AccountNotFoundExceptionMapper accountNotFoundExceptionMapper() {
    return new AccountNotFoundExceptionMapper();
  }

  @Bean
  public OptimisticLockingExceptionMapper optimisticLockingExceptionMapper() {
    return new OptimisticLockingExceptionMapper();
  }

  @Bean
  public ValidationExceptionMapper validationExceptionMapper() {
    return new ValidationExceptionMapper();
  }

  @Bean
  public DropwizardErrorMapper dropwizardErrorMapper() {
    return new DropwizardErrorMapper();
  }

  // filters
  @Bean
  public FilterRegistrationBean<RequestMetricsFilter> requestMetricsFilter(
      RequestMetrics requestMetrics) {
    return registration(new RequestMetricsFilter(requestMetrics), RequestMetricsFilter.ORDER);
  }

  @Bean
  public FilterRegistrationBean<RequestLogFilter> requestLogFilter() {
    return registration(new RequestLogFilter(), RequestLogFilter.ORDER);
  }

  @Bean
  public FilterRegistrationBean<AllowedMethodsFilter> allowedMethodsFilter() {
    return registration(new AllowedMethodsFilter(), AllowedMethodsFilter.ORDER);
  }

  @Bean
  public FilterRegistrationBean<JettyGzipFilter> jettyGzipFilter() {
    return registration(new JettyGzipFilter(), JettyGzipFilter.ORDER);
  }

  @Bean
  public FilterRegistrationBean<HeadEntityLengthFilter> headEntityLengthFilter() {
    return registration(new HeadEntityLengthFilter(), HeadEntityLengthFilter.ORDER);
  }

  @Bean
  public FilterRegistrationBean<JettyErrorPageFilter> jettyErrorPageFilter() {
    return registration(new JettyErrorPageFilter(), JettyErrorPageFilter.ORDER);
  }

  @Bean
  public FilterRegistrationBean<OptionsEntityFilter> optionsEntityFilter() {
    return registration(new OptionsEntityFilter(), OptionsEntityFilter.ORDER);
  }

  @Bean
  public FilterRegistrationBean<ResponseFieldFilter> responseFieldFilter() {
    return registration(new ResponseFieldFilter(), ResponseFieldFilter.ORDER);
  }

  /**
   * Jersey answered OPTIONS for an unrouted path with its plain 404. Spring's dispatcher falls back
   * to {@code HttpServlet#doOptions} when no handler set an {@code Allow} field and lists every
   * servlet method on that 404, so OPTIONS goes through the handler mappings only.
   */
  @Bean(name = DispatcherServletAutoConfiguration.DEFAULT_DISPATCHER_SERVLET_BEAN_NAME)
  public DispatcherServlet dispatcherServlet() {
    return new DispatcherServlet() {
      private static final long serialVersionUID = 1L;

      @Override
      protected void doOptions(HttpServletRequest request, HttpServletResponse response)
          throws ServletException, IOException {
        processRequest(request, response);
      }
    };
  }

  private static <T extends Filter> FilterRegistrationBean<T> registration(
      T filter, int order) {
    FilterRegistrationBean<T> registration = new FilterRegistrationBean<>(filter);
    registration.setOrder(order);
    return registration;
  }

  /**
   * Jersey treated a trailing slash as part of the same path, so {@code /clients/{id}/} returned
   * the client. Spring Boot 3 stopped matching an optional trailing separator by default.
   */
  @Bean
  public WebMvcConfigurer trailingSlashPathMatch() {
    return new WebMvcConfigurer() {
      @Override
      public void configurePathMatch(PathMatchConfigurer configurer) {
        PathPatternParser parser = new PathPatternParser();
        parser.setMatchOptionalTrailingSeparator(true);
        configurer.setPatternParser(parser);
      }
    };
  }

  @Bean
  public UuidPathParams uuidPathParams() {
    return new UuidPathParams();
  }

  /**
   * The routes whose Jersey resources consumed JSON; the transactions projection declared no
   * consumed type.
   */
  @Bean
  public WebMvcConfigurer consumedMediaType() {
    return new WebMvcConfigurer() {
      @Override
      public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new ConsumedMediaTypeInterceptor())
            .addPathPatterns("/clients", "/clients/**", "/accounts", "/accounts/**")
            .excludePathPatterns("/accounts/*/transactions", "/accounts/*/transactions/");
      }
    };
  }

  // infrastructure
  @Bean
  public EventStore eventStore() {
    return new InMemoryEventStore();
  }

  /**
   * Feeds the projections asynchronously; the context closes it, stopping its delivery thread.
   */
  @Bean
  public EventBus eventBus() {
    return new SingleThreadEventBus();
  }

  // domain model
  @Bean
  public AccountService accountService(EventStore eventStore, EventBus eventBus) {
    return new AccountService(eventStore, eventBus);
  }

  @Bean
  public AccountsResource accountsResource(AccountService accountService) {
    return new AccountsResource(accountService);
  }

  @Bean
  public AccountResource accountResource(AccountService accountService) {
    return new AccountResource(accountService);
  }

  @Bean
  public DepositsResource depositsResource(AccountService accountService) {
    return new DepositsResource(accountService);
  }

  @Bean
  public WithdrawalsResource withdrawalsResource(AccountService accountService) {
    return new WithdrawalsResource(accountService);
  }

  @Bean
  public ClientService clientService(EventStore eventStore) {
    return new ClientService(eventStore);
  }

  @Bean
  public ClientsResource clientsResource(ClientService clientService) {
    return new ClientsResource(clientService);
  }

  @Bean
  public ClientResource clientResource(ClientService clientService) {
    return new ClientResource(clientService);
  }

  // read model
  @Bean
  public TransactionsRepository transactionsRepository() {
    return new InMemoryTransactionsRepository();
  }

  @Bean
  public TransactionsListener transactionsListener(EventBus eventBus,
      TransactionsRepository transactionsRepository) {
    TransactionsListener transactionsListener = new TransactionsListener(transactionsRepository);
    eventBus.register(transactionsListener);
    return transactionsListener;
  }

  @Bean
  public AccountTransactionsResource accountTransactionsResource(
      TransactionsRepository transactionsRepository) {
    return new AccountTransactionsResource(transactionsRepository);
  }

  @Bean
  public AccountsRepository accountsRepository() {
    return new InMemoryAccountsRepository();
  }

  @Bean
  public AccountsListener accountsListener(EventBus eventBus,
      AccountsRepository accountsRepository) {
    AccountsListener accountsListener = new AccountsListener(accountsRepository);
    eventBus.register(accountsListener);
    return accountsListener;
  }

  @Bean
  public ClientAccountsResource clientAccountsResource(AccountsRepository accountsRepository) {
    return new ClientAccountsResource(accountsRepository);
  }
}
