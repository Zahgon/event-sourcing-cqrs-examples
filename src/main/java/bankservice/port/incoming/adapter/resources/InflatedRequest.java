package bankservice.port.incoming.adapter.resources;

import com.google.common.base.Splitter;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PushbackInputStream;
import java.io.UncheckedIOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

/**
 * A request whose gzip-encoded entity is inflated as Jetty's {@code GzipHandler} did it: the entity
 * reads decompressed, and the request fields are rewritten the way the handler rewrote them, so
 * {@code Content-Length} becomes {@code X-Content-Length} and {@code Content-Encoding: gzip}
 * becomes {@code X-Content-Encoding: gzip}.
 */
final class InflatedRequest extends HttpServletRequestWrapper {

  private static final Pattern COMMA_GZIP = Pattern.compile(".*, *gzip");
  private static final String X_CONTENT_LENGTH = "X-Content-Length";
  private static final String X_CONTENT_ENCODING = "X-Content-Encoding";

  private final List<String[]> fields = new ArrayList<>();
  private ServletInputStream input;
  private Map<String, String[]> parameters;

  private InflatedRequest(HttpServletRequest request) {
    super(request);
    for (String name : Collections.list(request.getHeaderNames())) {
      for (String value : Collections.list(request.getHeaders(name))) {
        if (HttpHeaders.CONTENT_LENGTH.equalsIgnoreCase(name)) {
          fields.add(new String[] {X_CONTENT_LENGTH, value});
        } else if (HttpHeaders.CONTENT_ENCODING.equalsIgnoreCase(name)) {
          if ("gzip".equalsIgnoreCase(value)) {
            fields.add(new String[] {X_CONTENT_ENCODING, "gzip"});
          } else if (COMMA_GZIP.matcher(value).matches()) {
            fields.add(new String[] {X_CONTENT_ENCODING, "gzip"});
            fields.add(new String[] {name, value.substring(0, value.lastIndexOf(','))});
          }
        } else {
          fields.add(new String[] {name, value});
        }
      }
    }
  }

  /**
   * The request itself, or its inflating view when a {@code Content-Encoding} lists gzip.
   */
  static HttpServletRequest of(HttpServletRequest request) {
    for (String value : Collections.list(request.getHeaders(HttpHeaders.CONTENT_ENCODING))) {
      for (String coding : Splitter.on(',').split(value)) {
        if ("gzip".equalsIgnoreCase(Splitter.on(';').split(coding).iterator().next().trim())) {
          return new InflatedRequest(request);
        }
      }
    }
    return request;
  }

  @Override
  public String getHeader(String name) {
    for (String[] field : fields) {
      if (field[0].equalsIgnoreCase(name)) {
        return field[1];
      }
    }
    return null;
  }

  @Override
  public Enumeration<String> getHeaders(String name) {
    List<String> values = new ArrayList<>();
    for (String[] field : fields) {
      if (field[0].equalsIgnoreCase(name)) {
        values.add(field[1]);
      }
    }
    return Collections.enumeration(values);
  }

  @Override
  public Enumeration<String> getHeaderNames() {
    Set<String> names = new LinkedHashSet<>();
    for (String[] field : fields) {
      names.add(field[0]);
    }
    return Collections.enumeration(names);
  }

  @Override
  public int getIntHeader(String name) {
    String value = getHeader(name);
    return value == null ? -1 : Integer.parseInt(value);
  }

  @Override
  public int getContentLength() {
    return -1;
  }

  @Override
  public long getContentLengthLong() {
    return -1;
  }

  @Override
  public ServletInputStream getInputStream() throws IOException {
    if (input == null) {
      input = new Inflating(super.getInputStream());
    }
    return input;
  }

  @Override
  public BufferedReader getReader() throws IOException {
    String encoding = getCharacterEncoding();
    return new BufferedReader(new InputStreamReader(getInputStream(),
        encoding == null ? StandardCharsets.UTF_8.name() : encoding));
  }

  @Override
  public String getParameter(String name) {
    String[] values = parameters().get(name);
    return values == null ? null : values[0];
  }

  @Override
  public String[] getParameterValues(String name) {
    String[] values = parameters().get(name);
    return values == null ? null : values.clone();
  }

  @Override
  public Enumeration<String> getParameterNames() {
    return Collections.enumeration(parameters().keySet());
  }

  @Override
  public Map<String, String[]> getParameterMap() {
    return Collections.unmodifiableMap(parameters());
  }

  /**
   * The query parameters followed by those of an inflated form entity; the container would parse
   * the compressed bytes instead.
   */
  private Map<String, String[]> parameters() {
    if (parameters != null) {
      return parameters;
    }
    Map<String, List<String>> collected = new LinkedHashMap<>();
    collect(collected, getQueryString());
    String type = getContentType();
    if (type != null && type.toLowerCase(Locale.ROOT)
        .startsWith(MediaType.APPLICATION_FORM_URLENCODED_VALUE)) {
      try (InputStream entity = getInputStream()) {
        collect(collected, new String(entity.readAllBytes(), StandardCharsets.UTF_8));
      } catch (IOException unreadable) {
        throw new UncheckedIOException(unreadable);
      }
    }
    parameters = new LinkedHashMap<>();
    collected.forEach((name, values) -> parameters.put(name, values.toArray(String[]::new)));
    return parameters;
  }

  private static void collect(Map<String, List<String>> collected, String encoded) {
    if (encoded == null || encoded.isEmpty()) {
      return;
    }
    for (String pair : Splitter.on('&').split(encoded)) {
      if (pair.isEmpty()) {
        continue;
      }
      int equals = pair.indexOf('=');
      String name = equals < 0 ? pair : pair.substring(0, equals);
      String value = equals < 0 ? "" : pair.substring(equals + 1);
      collected.computeIfAbsent(URLDecoder.decode(name, StandardCharsets.UTF_8),
          key -> new ArrayList<>()).add(URLDecoder.decode(value, StandardCharsets.UTF_8));
    }
  }

  /**
   * Inflates the entity; an entity with no bytes at all reads as empty, as Jetty had nothing to
   * decode. The inflater is released as soon as the entity is drained, and on close.
   */
  private static final class Inflating extends ServletInputStream {

    private final PushbackInputStream raw;
    private InputStream inflater;
    private boolean finished;
    private boolean closed;

    private Inflating(InputStream raw) {
      this.raw = new PushbackInputStream(raw, 1);
    }

    private InputStream inflater() throws IOException {
      if (inflater == null) {
        int first = raw.read();
        if (first < 0) {
          inflater = InputStream.nullInputStream();
        } else {
          raw.unread(first);
          inflater = new GZIPInputStream(raw);
        }
      }
      return inflater;
    }

    @Override
    public int read() throws IOException {
      int b = inflater().read();
      if (b < 0) {
        finish();
      }
      return b;
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException {
      int n = inflater().read(b, off, len);
      if (n < 0) {
        finish();
      }
      return n;
    }

    /**
     * At the end of the entity: releases the inflater, and later reads see the end again.
     */
    private void finish() throws IOException {
      finished = true;
      close();
    }

    /**
     * Closes the inflater, releasing its native buffers, and the raw entity beneath it.
     */
    @Override
    public void close() throws IOException {
      if (closed) {
        return;
      }
      closed = true;
      if (inflater != null) {
        inflater.close();
      } else {
        raw.close();
      }
      inflater = InputStream.nullInputStream();
    }

    @Override
    public boolean isFinished() {
      return finished;
    }

    @Override
    public boolean isReady() {
      return true;
    }

    @Override
    public void setReadListener(ReadListener readListener) {
      throw new UnsupportedOperationException("this service reads requests synchronously");
    }
  }
}
