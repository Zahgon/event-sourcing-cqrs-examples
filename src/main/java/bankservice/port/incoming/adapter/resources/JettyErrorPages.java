package bankservice.port.incoming.adapter.resources;

import static java.lang.String.format;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.springframework.http.HttpHeaders;

/**
 * The answer Jetty's {@code ErrorHandler} gave whenever a servlet called {@code sendError}: for
 * GET, POST and HEAD a page negotiated from {@code Accept} and {@code Accept-Charset} (HTML, a
 * small JSON document or plain text), for every other method the bare status.
 *
 * <p>Spring MVC and Jetty 12 render neither the same negotiation nor the same documents, so the
 * Jetty 11 behaviour the original exposed is written out here.
 */
final class JettyErrorPages {

  static final String CACHE_CONTROL = "must-revalidate,no-cache,no-store";

  private static final Set<String> PAGE_METHODS = Set.of("GET", "POST", "HEAD");

  private JettyErrorPages() {
  }

  /**
   * Answers {@code status} as {@code sendError(status, message)} did; {@code servlet} is the name
   * the page shows for the servlet that refused the request.
   */
  static void send(HttpServletRequest request, HttpServletResponse response, int status,
      String message, String servlet) throws IOException {
    response.setStatus(status);
    if (!PAGE_METHODS.contains(request.getMethod())) {
      return;
    }
    response.setHeader(HttpHeaders.CACHE_CONTROL, CACHE_CONTROL);
    List<String> charsets = qualityList(request, HttpHeaders.ACCEPT_CHARSET, false);
    Charset charset = null;
    if (!charsets.isEmpty()) {
      for (String name : charsets) {
        if ("*".equals(name)) {
          charset = StandardCharsets.UTF_8;
          break;
        }
        Charset known = known(name);
        if (known != null) {
          charset = known;
        }
      }
      if (charset == null) {
        return;
      }
    }
    List<String> types = qualityList(request, HttpHeaders.ACCEPT, true);
    if (types.isEmpty() && request.getHeader(HttpHeaders.ACCEPT) == null) {
      types = List.of("text/html");
    }
    for (String type : types) {
      String contentType;
      String document;
      Charset encoding = charset;
      switch (type) {
        case "text/html", "text/*", "*/*" -> {
          encoding = encoding == null ? StandardCharsets.ISO_8859_1 : encoding;
          contentType = "text/html;charset=" + charsetName(encoding);
          document = html(request, status, message, servlet, encoding);
        }
        case "text/json", "application/json" -> {
          encoding = encoding == null ? StandardCharsets.UTF_8 : encoding;
          contentType = type;
          document = json(request, status, message, servlet);
        }
        case "text/plain" -> {
          encoding = encoding == null ? StandardCharsets.ISO_8859_1 : encoding;
          contentType = "text/plain;charset=" + charsetName(encoding);
          document = plain(request, status, message, servlet);
        }
        default -> {
          continue;
        }
      }
      byte[] bytes = document.getBytes(encoding);
      response.setContentType(contentType);
      response.setContentLength(bytes.length);
      response.getOutputStream().write(bytes);
      return;
    }
  }

  /**
   * Jetty normalises the names of the three charsets it knows (UTF-8, UTF-16 and ISO-8859-1) to
   * lower case and writes every other one under its canonical name.
   */
  private static String charsetName(Charset charset) {
    return StandardCharsets.UTF_8.equals(charset) || StandardCharsets.UTF_16.equals(charset)
        || StandardCharsets.ISO_8859_1.equals(charset)
        ? charset.name().toLowerCase(Locale.ROOT) : charset.name();
  }

  /**
   * The charset of that name, or null for a name the JVM does not know (Jetty skipped those).
   */
  private static Charset known(String name) {
    try {
      return Charset.forName(name);
    } catch (IllegalArgumentException unknown) {
      return null;
    }
  }

  private static String html(HttpServletRequest request, int status, String message,
      String servlet, Charset charset) {
    String heading = status + " " + sanitize(message);
    return "<html>\n<head>\n"
        + "<meta http-equiv=\"Content-Type\" content=\"text/html;charset=" + charset.name()
        + "\"/>\n"
        + "<title>Error " + heading + "</title>\n"
        + "</head>\n<body><h2>HTTP ERROR " + heading + "</h2>\n"
        + "<table>\n"
        + row("URI", request.getRequestURI())
        + row("STATUS", Integer.toString(status))
        + row("MESSAGE", message)
        + row("SERVLET", servlet)
        + "</table>\n\n</body>\n</html>\n";
  }

  private static String row(String name, String value) {
    return "<tr><th>" + name + ":</th><td>" + sanitize(value) + "</td></tr>\n";
  }

  private static String json(HttpServletRequest request, int status, String message,
      String servlet) {
    return "{\n" + member("servlet", servlet) + ",\n" + member("message", message) + ",\n"
        + member("url", request.getRequestURI()) + ",\n"
        + member("status", Integer.toString(status)) + "\n}";
  }

  private static String member(String name, String value) {
    return quote(name) + ":" + quote(sanitize(value));
  }

  private static String plain(HttpServletRequest request, int status, String message,
      String servlet) {
    return format("HTTP ERROR %d %s\nURI: %s\nSTATUS: %d\nMESSAGE: %s\nSERVLET: %s\n", status,
        sanitize(message), request.getRequestURI(), status, message, servlet);
  }

  /**
   * Jetty's {@code StringUtil.sanitizeXmlString}.
   */
  private static String sanitize(String text) {
    StringBuilder out = new StringBuilder(text.length());
    for (int i = 0; i < text.length(); i++) {
      char c = text.charAt(i);
      switch (c) {
        case '&' -> out.append("&amp;");
        case '<' -> out.append("&lt;");
        case '>' -> out.append("&gt;");
        case '\'' -> out.append("&apos;");
        case '"' -> out.append("&quot;");
        default -> out.append(
            Character.isISOControl(c) && !Character.isWhitespace(c) ? '?' : c);
      }
    }
    return out.toString();
  }

  /**
   * Jetty's {@code QuotedStringTokenizer.quote}.
   */
  private static String quote(String text) {
    StringBuilder out = new StringBuilder("\"");
    for (int i = 0; i < text.length(); i++) {
      char c = text.charAt(i);
      switch (c) {
        case '"', '\\' -> out.append('\\').append(c);
        case '\b' -> out.append("\\b");
        case '\t' -> out.append("\\t");
        case '\n' -> out.append("\\n");
        case '\f' -> out.append("\\f");
        case '\r' -> out.append("\\r");
        default -> {
          if (c < 32) {
            out.append(format("\\u%04x", (int) c));
          } else {
            out.append(c);
          }
        }
      }
    }
    return out.append('"').toString();
  }

  /**
   * The values of a header as Jetty's {@code QuotedQualityCSV} lists them: the {@code q} parameter
   * and everything after it removed, values with quality 0 dropped, sorted by falling quality and,
   * for {@code Accept}, by falling specificity, then in the order they were sent.
   */
  private static List<String> qualityList(HttpServletRequest request, String header,
      boolean mediaRanges) {
    List<Weighted> values = new ArrayList<>();
    for (String field : Collections.list(request.getHeaders(header))) {
      for (String element : splitOutsideQuotes(field, ',')) {
        Weighted weighted = weighted(element, values.size());
        if (weighted != null) {
          values.add(weighted);
        }
      }
    }
    Comparator<Weighted> order = Comparator.comparingDouble((Weighted w) -> -w.quality);
    if (mediaRanges) {
      order = order.thenComparingInt(w -> -specificity(w.value));
    }
    order = order.thenComparingInt(w -> w.index);
    List<String> sorted = new ArrayList<>();
    values.stream().filter(w -> w.quality != 0).sorted(order).forEach(w -> sorted.add(w.value));
    return sorted;
  }

  private static Weighted weighted(String element, int index) {
    List<String> parts = splitOutsideQuotes(element, ';');
    String value = parts.get(0).trim();
    double quality = 1;
    StringBuilder kept = new StringBuilder(value);
    for (String part : parts.subList(1, parts.size())) {
      String parameter = part.trim();
      if (parameter.isEmpty()) {
        continue;
      }
      int equals = parameter.indexOf('=');
      String name = equals < 0 ? parameter : parameter.substring(0, equals).trim();
      String text = equals < 0 ? "" : parameter.substring(equals + 1).trim().replace("\"", "");
      if ("q".equals(name) && equals >= 0) {
        try {
          quality = Double.parseDouble(text);
        } catch (NumberFormatException notANumber) {
          quality = 0;
        }
        break;
      }
      kept.append(';').append(name);
      if (equals >= 0) {
        kept.append('=').append(text);
      }
    }
    if (kept.length() == 0) {
      return null;
    }
    return new Weighted(kept.toString(), quality, index);
  }

  private static int specificity(String value) {
    if ("*/*".equals(value)) {
      return 0;
    }
    if (value.endsWith("/*")) {
      return 1;
    }
    return value.indexOf(';') < 0 ? 2 : 3;
  }

  private static List<String> splitOutsideQuotes(String text, char separator) {
    List<String> parts = new ArrayList<>();
    StringBuilder current = new StringBuilder();
    boolean quoted = false;
    for (int i = 0; i < text.length(); i++) {
      char c = text.charAt(i);
      if (c == '"') {
        quoted = !quoted;
      }
      if (c == separator && !quoted) {
        parts.add(current.toString());
        current.setLength(0);
      } else {
        current.append(c);
      }
    }
    parts.add(current.toString());
    return parts;
  }

  private record Weighted(String value, double quality, int index) {
  }
}
