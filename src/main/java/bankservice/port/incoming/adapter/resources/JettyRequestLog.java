package bankservice.port.incoming.adapter.resources;

import java.io.IOException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.RequestLog;
import org.eclipse.jetty.server.RequestLogWriter;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.util.NanoTime;
import org.eclipse.jetty.util.component.ContainerLifeCycle;
import org.springframework.boot.web.embedded.jetty.JettyServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;

/**
 * Dropwizard's default request log, kept on both ports: one combined-format line per request with
 * the client address, the time in UTC, the request line, the status, the entity bytes sent, the
 * referrer, the user agent and the milliseconds taken. Like the original it bypasses Logback, so
 * it never counts in the Logback meters.
 */
public class JettyRequestLog extends ContainerLifeCycle implements RequestLog {

  private static final DateTimeFormatter TIME =
      DateTimeFormatter.ofPattern("dd/MMM/yyyy:HH:mm:ss Z", Locale.ROOT).withZone(ZoneOffset.UTC);

  private final RequestLogWriter writer = new RequestLogWriter();

  public JettyRequestLog() {
    addBean(writer);
  }

  /**
   * Installs the log on the Jetty server the customized factory creates.
   */
  public static WebServerFactoryCustomizer<JettyServletWebServerFactory> customizer() {
    return factory -> factory.addServerCustomizers(
        server -> server.setRequestLog(new JettyRequestLog()));
  }

  @Override
  public void log(Request request, Response response) {
    String line = String.format(Locale.ROOT, "%s - - [%s] \"%s %s %s\" %d %d \"%s\" \"%s\" %d",
        Request.getRemoteAddr(request),
        TIME.format(Instant.ofEpochMilli(Request.getTimeStamp(request))),
        request.getMethod(), request.getHttpURI().getPathQuery(),
        request.getConnectionMetaData().getProtocol(), response.getStatus(),
        bytesSent(request, response), header(request, HttpHeader.REFERER),
        header(request, HttpHeader.USER_AGENT), NanoTime.millisSince(request.getBeginNanoTime()));
    try {
      writer.write(line);
    } catch (IOException unwritable) {
      throw new IllegalStateException("could not write the request log", unwritable);
    }
  }

  /**
   * Jetty 11 counted the entity bytes that reached the connection, so an answer to HEAD logged 0;
   * Jetty 12 counts what the application wrote even when the connection drops it.
   */
  private static long bytesSent(Request request, Response response) {
    return "HEAD".equals(request.getMethod()) ? 0 : Response.getContentBytesWritten(response);
  }

  private static String header(Request request, HttpHeader name) {
    String value = request.getHeaders().get(name);
    return value == null ? "-" : value;
  }
}
