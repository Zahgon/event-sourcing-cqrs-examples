package bankservice.port.incoming.adapter.resources;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * The request and response logging Jersey's {@code LoggingFeature} did for the original at INFO
 * with every entity: one event when a request arrives (request line, headers, the first 1024 bytes
 * of the entity) and one when its answer is complete (status, the application's headers, the
 * first 1024 bytes of the entity), both numbered with the same per-request id.
 */
public class RequestLogFilter extends OncePerRequestFilter {

  /**
   * Runs inside {@link JettyErrorPageFilter}: like Jersey, it sees a status the container turns
   * into an error page as a status without an entity. It runs outside {@link OptionsEntityFilter},
   * whose entity Jersey wrote itself.
   */
  public static final int ORDER = JettyErrorPageFilter.ORDER + 50;

  private static final Logger LOG = LoggerFactory.getLogger("bankservice.http");
  private static final int MAX_ENTITY_BYTES = 1024;
  private static final List<String> RESPONSE_HEADERS = List.of(HttpHeaders.ALLOW,
      HttpHeaders.CONTENT_TYPE, HttpHeaders.LOCATION);

  private static final Pattern CHARSET_PARAMETER =
      Pattern.compile("(?i)\\s*;\\s*charset\\s*=\\s*(\"(?:[^\"\\\\]|\\\\.)*\"|[^;]*)");

  private final AtomicLong ids = new AtomicLong();

  @Override
  protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
      FilterChain filterChain) throws ServletException, IOException {
    long id = ids.incrementAndGet();
    String target = request.getMethod() + " " + target(request);
    byte[] entity = null;
    String received = null;
    RuntimeException unreadable = null;
    try {
      try (InputStream input = request.getInputStream()) {
        entity = input.readAllBytes();
      }
      received = received(request, id, target, entity);
    } catch (IOException failure) {
      unreadable = new UncheckedIOException(failure);
    } catch (IllegalArgumentException unknownCharset) {
      unreadable = unknownCharset;
    }

    BufferingResponseWrapper buffered = new BufferingResponseWrapper(response);
    try {
      if (unreadable == null) {
        LOG.info(received);
        filterChain.doFilter(new BufferedRequest(request, entity), buffered);
      } else {
        DropwizardErrorMapper.writeLoggedError(buffered, unreadable);
      }
    } finally {
      final byte[] answer = buffered.entity();
      StringBuilder responded = new StringBuilder();
      notification(responded, id, "Server responded with a response", target);
      responded.append(id).append(" < ").append(buffered.getStatus()).append('\n');
      for (String name : RESPONSE_HEADERS) {
        String value = HttpHeaders.CONTENT_TYPE.equals(name)
            ? buffered.getContentType() : buffered.getHeader(name);
        if (value != null) {
          responded.append(id).append(" < ").append(name).append(": ").append(value).append('\n');
        }
      }
      String encoding = buffered.getCharacterEncoding();
      appendEntity(responded, answer,
          encoding == null ? StandardCharsets.UTF_8 : Charset.forName(encoding));
      LOG.info(responded.toString());
      if (answer.length > 0 && !response.isCommitted()) {
        response.getOutputStream().write(answer);
      }
    }
  }

  /**
   * The event of an arriving request. Jersey decoded the entity with the {@code charset} parameter
   * of its {@code Content-Type}, so an entity it cannot decode fails the request here, before the
   * event is written.
   */
  private static String received(HttpServletRequest request, long id, String target,
      byte[] entity) {
    StringBuilder received = new StringBuilder();
    notification(received, id, "Server has received a request", target);
    received.append(id).append(" > ").append(request.getMethod()).append(' ')
        .append(request.getRequestURL());
    if (request.getQueryString() != null) {
      received.append('?').append(request.getQueryString());
    }
    received.append('\n');
    List<String> names = Collections.list(request.getHeaderNames());
    names.sort(String.CASE_INSENSITIVE_ORDER);
    for (String name : names) {
      received.append(id).append(" > ").append(name).append(": ")
          .append(String.join(",", Collections.list(request.getHeaders(name)))).append('\n');
    }
    if (entity.length > 0) {
      appendEntity(received, entity, charset(request.getHeader(HttpHeaders.CONTENT_TYPE)));
    }
    return received.toString();
  }

  private static String target(HttpServletRequest request) {
    String query = request.getQueryString();
    return query == null ? request.getRequestURI() : request.getRequestURI() + "?" + query;
  }

  private static void notification(StringBuilder b, long id, String note, String target) {
    b.append(id).append(" * ").append(note).append(" on thread ")
        .append(Thread.currentThread().getName()).append(" - ").append(target).append('\n');
  }

  private static void appendEntity(StringBuilder b, byte[] entity, Charset charset) {
    if (entity.length == 0) {
      return;
    }
    b.append(new String(entity, 0, Math.min(entity.length, MAX_ENTITY_BYTES), charset));
    if (entity.length > MAX_ENTITY_BYTES) {
      b.append("...more...");
    }
    b.append('\n');
  }

  /**
   * The {@code charset} parameter of a media type as Jersey read it, UTF-8 without one.
   */
  private static Charset charset(String contentType) {
    String name = contentType == null ? null
        : MediaTypeHeader.parameters(contentType).map(p -> p.get("charset")).orElse(null);
    return name == null ? StandardCharsets.UTF_8 : Charset.forName(name);
  }

  /**
   * Hands the handler the entity bytes the log has already read.
   */
  private static final class BufferedRequest extends HttpServletRequestWrapper {

    private final byte[] entity;

    private BufferedRequest(HttpServletRequest request, byte[] entity) {
      super(request);
      // Jersey's JSON provider read an entity of nothing but whitespace as no entity at all.
      this.entity = request.getContentType() != null && JsonEntity.isBlank(entity)
          ? new byte[0] : entity;
    }

    @Override
    public ServletInputStream getInputStream() {
      ByteArrayInputStream bytes = new ByteArrayInputStream(entity);
      return new ServletInputStream() {
        @Override
        public int read() {
          return bytes.read();
        }

        @Override
        public int read(byte[] b, int off, int len) {
          return bytes.read(b, off, len);
        }

        @Override
        public boolean isFinished() {
          return bytes.available() == 0;
        }

        @Override
        public boolean isReady() {
          return true;
        }

        @Override
        public void setReadListener(ReadListener readListener) {
          throw new UnsupportedOperationException("this service reads requests synchronously");
        }
      };
    }

    @Override
    public BufferedReader getReader() {
      return new BufferedReader(new InputStreamReader(new ByteArrayInputStream(entity),
          StandardCharsets.UTF_8));
    }

    /**
     * Jersey's JSON provider handed Jackson the entity bytes and let it detect their encoding, so
     * the {@code charset} parameter never decided how a body was read; Spring would decode the
     * body with it, and refuse a name it does not know.
     */
    @Override
    public String getContentType() {
      String type = super.getContentType();
      return type == null ? null : CHARSET_PARAMETER.matcher(type).replaceAll("");
    }

    @Override
    public String getHeader(String name) {
      return HttpHeaders.CONTENT_TYPE.equalsIgnoreCase(name) ? getContentType()
          : super.getHeader(name);
    }

    @Override
    public Enumeration<String> getHeaders(String name) {
      if (!HttpHeaders.CONTENT_TYPE.equalsIgnoreCase(name)) {
        return super.getHeaders(name);
      }
      String type = getContentType();
      return Collections.enumeration(type == null ? List.of() : List.of(type));
    }

    @Override
    public String getCharacterEncoding() {
      return null;
    }
  }
}
