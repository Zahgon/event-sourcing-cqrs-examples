package bankservice.port.incoming.adapter.resources;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Reads a {@code Content-Type} value with the grammar Jersey's header reader applied: a type token,
 * {@code /}, a subtype token, then parameters, each introduced by one or more {@code ;} and written
 * {@code name=value} with a token or a quoted string as the value. Whitespace is allowed at either
 * end and around {@code ;}, but not around {@code /} or {@code =}. Jersey refused any other value
 * before routing the request.
 */
final class MediaTypeHeader {

  private static final String SEPARATORS = "()<>@,;:\\\"/[]?={} \t";

  private final String value;
  private int position;

  private MediaTypeHeader(String value) {
    this.value = value;
  }

  /**
   * The parameters of a well-formed media type, names in lower case; empty when it is malformed.
   */
  static Optional<Map<String, String>> parameters(String value) {
    return new MediaTypeHeader(value).read();
  }

  /**
   * Whether a well-formed media type is compatible with {@code application/json} as Jersey
   * compared a request with a resource's consumed type: a wildcard type, or the type
   * {@code application} with a wildcard or {@code json} subtype, in any letter case, parameters
   * ignored.
   */
  static boolean acceptsJson(String value) {
    MediaTypeHeader header = new MediaTypeHeader(value);
    header.skipWhitespace();
    String type = header.token();
    String subtype = type != null && header.adjacent('/') ? header.token() : null;
    if (subtype == null) {
      return false;
    }
    return "*".equals(type) || "application".equalsIgnoreCase(type)
        && ("*".equals(subtype) || "json".equalsIgnoreCase(subtype));
  }

  private Optional<Map<String, String>> read() {
    Map<String, String> parameters = new LinkedHashMap<>();
    skipWhitespace();
    if (token() == null || !adjacent('/') || token() == null) {
      return Optional.empty();
    }
    while (more()) {
      if (!separator(';')) {
        return Optional.empty();
      }
      while (peek() == ';') {
        position++;
      }
      if (!more()) {
        break;
      }
      skipWhitespace();
      String name = token();
      if (name == null || !adjacent('=')) {
        return Optional.empty();
      }
      boolean quotedValue = position < value.length() && value.charAt(position) == '"';
      String parameter = quotedValue ? quoted() : token();
      if (parameter == null) {
        return Optional.empty();
      }
      parameters.put(name.toLowerCase(Locale.ROOT), parameter);
    }
    return Optional.of(parameters);
  }

  private boolean more() {
    skipWhitespace();
    return position < value.length();
  }

  private char peek() {
    skipWhitespace();
    return position < value.length() ? value.charAt(position) : 0;
  }

  private void skipWhitespace() {
    while (position < value.length()
        && (value.charAt(position) == ' ' || value.charAt(position) == '\t')) {
      position++;
    }
  }

  private boolean separator(char separator) {
    if (peek() != separator) {
      return false;
    }
    position++;
    return true;
  }

  private boolean adjacent(char separator) {
    if (position >= value.length() || value.charAt(position) != separator) {
      return false;
    }
    position++;
    return true;
  }

  private String token() {
    int start = position;
    while (position < value.length() && isTokenChar(value.charAt(position))) {
      position++;
    }
    return position > start ? value.substring(start, position) : null;
  }

  private String quoted() {
    StringBuilder text = new StringBuilder();
    position++;
    while (position < value.length()) {
      char c = value.charAt(position++);
      if (c == '"') {
        return text.toString();
      }
      if (c == '\\' && position < value.length()) {
        c = value.charAt(position++);
      }
      text.append(c);
    }
    return null;
  }

  private static boolean isTokenChar(char c) {
    return c > 32 && c < 127 && SEPARATORS.indexOf(c) < 0;
  }
}
