package bankservice.port.incoming.adapter.resources;

import org.springframework.boot.actuate.autoconfigure.web.ManagementContextConfiguration;
import org.springframework.boot.actuate.autoconfigure.web.ManagementContextType;
import org.springframework.boot.web.embedded.jetty.JettyServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;

/**
 * Configures the admin server that the original ran on its own connector.
 *
 * <p>Dropwizard builds the admin connector from the same server factory as the application one, so
 * the {@code GzipHandler} sat in front of the admin servlet as well and every admin answer with a
 * compressible status advertised {@code Vary: Accept-Encoding}. Spring Boot puts the management
 * endpoints in a child context with its own servlet registry, which the filters registered by the
 * bootstrap never reach, so those answers lost the field.
 *
 * <p>Imported through the {@code ManagementContextConfiguration.imports} descriptor under
 * {@code META-INF/spring}. It deliberately sits outside the bootstrap package: the annotation is
 * itself a {@code @Configuration}, so component scanning would otherwise also apply it to the
 * application context and stamp the field on twice.
 */
@ManagementContextConfiguration(ManagementContextType.CHILD)
public class AdminServerConfiguration {

  /**
   * Dropwizard's request log on the admin connector.
   */
  @Bean
  public WebServerFactoryCustomizer<JettyServletWebServerFactory> adminRequestLog() {
    return JettyRequestLog.customizer();
  }

  /**
   * Dropwizard's allowed-methods filter guarded the admin connector as well.
   */
  @Bean
  public FilterRegistrationBean<AllowedMethodsFilter> adminAllowedMethodsFilter() {
    FilterRegistrationBean<AllowedMethodsFilter> registration =
        new FilterRegistrationBean<>(new AllowedMethodsFilter());
    registration.setOrder(AllowedMethodsFilter.ORDER);
    return registration;
  }

  @Bean
  public FilterRegistrationBean<JettyGzipFilter> adminJettyGzipFilter() {
    FilterRegistrationBean<JettyGzipFilter> registration =
        new FilterRegistrationBean<>(new JettyGzipFilter());
    registration.setOrder(JettyGzipFilter.ORDER);
    return registration;
  }

  /**
   * The Dropwizard admin servlet's endpoints: menu, ping, health checks, thread dump, metrics, CPU
   * profile and tasks. The Logback meters and the request metrics come from the application
   * context, the parent of this one.
   */
  @Bean
  public FilterRegistrationBean<AdminOperationsFilter> adminOperationsFilter(
      LogbackEventMeters logbackEventMeters, RequestMetrics requestMetrics) {
    FilterRegistrationBean<AdminOperationsFilter> registration = new FilterRegistrationBean<>(
        new AdminOperationsFilter(logbackEventMeters, requestMetrics));
    registration.setOrder(AdminOperationsFilter.ORDER);
    return registration;
  }
}
