package bankservice.port.incoming.adapter.resources;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Restores the entity Jersey returned from an OPTIONS request.
 *
 * <p>Jersey answered OPTIONS with the allowed methods twice over: in the {@code Allow} field and
 * again as a {@code text/plain} body. Spring MVC sets only the field, so the migration returned an
 * empty answer with no content type. The body is built from a {@link HashSet} for the same reason
 * Jersey's is - the set is what determines the order the methods appear in.
 */
public class OptionsEntityFilter extends OncePerRequestFilter {

  /**
   * Runs inside {@link JettyErrorPageFilter}; a 200 with an entity is not an error page.
   */
  public static final int ORDER = JettyErrorPageFilter.ORDER + 100;

  @Override
  protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
      FilterChain filterChain) throws ServletException, IOException {
    CountingResponseWrapper counting = new CountingResponseWrapper(response);
    filterChain.doFilter(request, counting);
    String allowed = counting.getHeader(HttpHeaders.ALLOW);
    if (!HttpMethod.OPTIONS.matches(request.getMethod()) || allowed == null
        || counting.getStatus() != HttpStatus.OK.value() || counting.getWrittenByteCount() > 0
        || response.isCommitted()) {
      return;
    }
    Set<String> methods = new HashSet<>();
    for (String method : allowed.split(",")) {
      if (!method.isBlank()) {
        methods.add(method.trim());
      }
    }
    String fieldValue = String.join(",", methods);
    response.setHeader(HttpHeaders.ALLOW, null);
    AcceptedType preferred = preferred(request.getHeader(HttpHeaders.ACCEPT));
    if (preferred == null) {
      // Jersey's text/plain handler: the content type before the field, the methods as the body.
      byte[] entity = String.join(", ", methods).getBytes(StandardCharsets.ISO_8859_1);
      response.setContentType("text/plain");
      response.setHeader(HttpHeaders.ALLOW, fieldValue);
      response.setContentLength(entity.length);
      response.getOutputStream().write(entity);
    } else {
      // Jersey's generic handler: the field, then the client's preferred type, and no body.
      response.setHeader(HttpHeaders.ALLOW, fieldValue);
      response.setHeader(HttpHeaders.CONTENT_TYPE, preferred.text());
      response.setContentLength(0);
    }
  }

  /**
   * Jersey offered two OPTIONS handlers, one producing {@code text/plain} and a generic one. The
   * text handler wins when a range that admits {@code text/plain} is at least as acceptable as
   * every other range; otherwise the generic handler answers with the first most acceptable range.
   * Returns null for the text handler.
   */
  static AcceptedType preferred(String accept) {
    if (accept == null || accept.isBlank()) {
      return null;
    }
    List<AcceptedType> ranges = new ArrayList<>();
    for (String part : accept.split(",")) {
      AcceptedType range = AcceptedType.parse(part);
      if (range != null) {
        ranges.add(range);
      }
    }
    if (ranges.isEmpty()) {
      return null;
    }
    double best = ranges.stream().mapToDouble(AcceptedType::quality).max().orElse(1);
    double text = ranges.stream().filter(AcceptedType::admitsTextPlain)
        .mapToDouble(AcceptedType::quality).max().orElse(-1);
    if (text >= best) {
      return null;
    }
    return ranges.stream().filter(r -> r.quality() == best).findFirst().orElse(null);
  }

  /** One media range of an Accept field, written back as Jersey wrote it. */
  record AcceptedType(String type, String subtype, List<String> parameters, double quality) {

    static AcceptedType parse(String text) {
      String[] parts = text.trim().split(";");
      String[] name = parts[0].trim().toLowerCase(Locale.ROOT).split("/", 2);
      if (name.length != 2 || name[0].isEmpty() || name[1].isEmpty()) {
        return null;
      }
      List<String> parameters = new ArrayList<>();
      double quality = 1;
      for (int i = 1; i < parts.length; i++) {
        String parameter = parts[i].trim();
        if (parameter.isEmpty()) {
          continue;
        }
        parameters.add(parameter.replace(" ", ""));
        String[] pair = parameter.split("=", 2);
        if (pair.length == 2 && pair[0].trim().equalsIgnoreCase("q")) {
          try {
            quality = Double.parseDouble(pair[1].trim());
          } catch (NumberFormatException notAQuality) {
            quality = 1;
          }
        }
      }
      return new AcceptedType(name[0], name[1], parameters, quality);
    }

    boolean admitsTextPlain() {
      return "*".equals(type) || "text".equals(type) && ("*".equals(subtype)
          || "plain".equals(subtype));
    }

    String text() {
      StringBuilder out = new StringBuilder(type).append('/').append(subtype);
      parameters.forEach(p -> out.append(';').append(p));
      return out.toString();
    }
  }
}
