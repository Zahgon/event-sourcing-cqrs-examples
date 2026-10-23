package bankservice.port.incoming.adapter.resources;

import com.google.common.base.Splitter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.zip.GZIPOutputStream;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Reproduces the Jetty {@code GzipHandler} that Dropwizard installs on every server by default.
 *
 * <p>The handler inflates a request entity sent with {@code Content-Encoding: gzip} (see
 * {@link InflatedRequest}). On the way out, two observable effects were lost in the migration:
 * every GET and POST answer that carries a compressible status advertises
 * {@code Vary: Accept-Encoding} whether or not it is actually compressed, and an entity at or
 * above the 256-byte minimum is returned gzipped when the client offers that encoding. Spring
 * Boot's own {@code server.compression} cannot stand in for this: it drives the handler from an
 * included-media-type list and a 2 KiB minimum entity size, while Dropwizard drives it from an
 * excluded-media-type list and a 256-byte minimum, so the two disagree about which answers are
 * compressed at all.
 */
public class JettyGzipFilter extends OncePerRequestFilter {

  /**
   * Runs outside the other response filters so that it sees the entity they produce.
   */
  public static final int ORDER = Ordered.HIGHEST_PRECEDENCE + 20000;

  /**
   * {@code GzipHandler}'s default set of methods; every other method is passed through.
   */
  private static final Set<String> COMPRESSIBLE_METHODS = Set.of("GET", "POST");

  /**
   * Dropwizard's {@code GzipHandlerFactory#minimumEntitySize} default.
   */
  private static final int MINIMUM_ENTITY_SIZE = 256;

  private static final Pattern ZERO_QUALITY = Pattern.compile("q=0[0.]*");
  private static final Pattern WHITESPACE = Pattern.compile("[ \t]");

  /**
   * Media types {@code GzipHandler} excludes by default because they are already compressed.
   */
  private static final List<String> EXCLUDED_MEDIA_TYPES = List.of(
      "image/", "audio/", "video/", "application/compress", "application/zip", "application/gzip",
      "application/bzip2", "application/brotli", "application/x-xz",
      "application/x-rar-compressed", "application/x-7z-compressed");

  @Override
  protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
      FilterChain filterChain) throws ServletException, IOException {
    HttpServletRequest inflated = InflatedRequest.of(request);
    if (!COMPRESSIBLE_METHODS.contains(request.getMethod())) {
      filterChain.doFilter(inflated, response);
      return;
    }
    BufferingResponseWrapper buffered = new BufferingResponseWrapper(response);
    filterChain.doFilter(inflated, buffered);
    byte[] entity = buffered.entity();
    if (!isCompressibleStatus(buffered.getStatus())) {
      write(response, entity);
      return;
    }
    response.addHeader(HttpHeaders.VARY, HttpHeaders.ACCEPT_ENCODING);
    if (entity.length < MINIMUM_ENTITY_SIZE
        || !isCompressibleMediaType(buffered.getContentType())
        || !acceptsGzip(request)) {
      write(response, entity);
      return;
    }
    response.setHeader(HttpHeaders.CONTENT_ENCODING, "gzip");
    write(response, gzip(entity));
  }

  /**
   * {@code GzipHandler} leaves informational, no-content and redirect or error answers alone, which
   * is why the original carried no {@code Vary} field on any of them.
   */
  private static boolean isCompressibleStatus(int status) {
    return status >= 200 && status < 300 && status != 204 && status != 205;
  }

  private static boolean isCompressibleMediaType(String contentType) {
    if (contentType == null) {
      return false;
    }
    String mediaType = contentType.split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
    return EXCLUDED_MEDIA_TYPES.stream().noneMatch(mediaType::startsWith);
  }

  /**
   * {@code GzipHandler} asked whether an {@code Accept-Encoding} field contains the coding
   * {@code gzip}, and a coding whose only parameter is a zero quality ({@code q=0}, {@code q=0.0})
   * is a refusal, not an offer.
   */
  private static boolean acceptsGzip(HttpServletRequest request) {
    for (String field : Collections.list(request.getHeaders(HttpHeaders.ACCEPT_ENCODING))) {
      for (String coding : Splitter.on(',').split(field)) {
        List<String> parts = Splitter.on(';').limit(2).splitToList(coding);
        if (!"gzip".equals(unquote(parts.get(0).trim()).toLowerCase(Locale.ROOT))) {
          continue;
        }
        String parameters = parts.size() < 2 ? ""
            : WHITESPACE.matcher(parts.get(1)).replaceAll("").toLowerCase(Locale.ROOT);
        if (!ZERO_QUALITY.matcher(parameters).matches()) {
          return true;
        }
      }
    }
    return false;
  }

  private static String unquote(String token) {
    return token.length() >= 2 && token.startsWith("\"") && token.endsWith("\"")
        ? token.substring(1, token.length() - 1) : token;
  }

  private static byte[] gzip(byte[] entity) throws IOException {
    ByteArrayOutputStream compressed = new ByteArrayOutputStream();
    try (GZIPOutputStream gzipStream = new GZIPOutputStream(compressed)) {
      gzipStream.write(entity);
    }
    return compressed.toByteArray();
  }

  private static void write(HttpServletResponse response, byte[] entity) throws IOException {
    response.setContentLength(entity.length);
    if (entity.length > 0) {
      response.getOutputStream().write(entity);
    }
  }
}
