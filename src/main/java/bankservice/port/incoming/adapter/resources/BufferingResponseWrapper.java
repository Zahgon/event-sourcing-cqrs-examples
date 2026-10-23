package bankservice.port.incoming.adapter.resources;

import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.WriteListener;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpServletResponseWrapper;
import java.io.ByteArrayOutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

/**
 * Holds an entity back so that its length is known before the headers are written, exactly as the
 * original stack did: Jetty's {@code GzipHandler} buffered to decide whether to deflate, and
 * Jersey buffered to decide whether it could declare a {@code Content-Length}.
 */
class BufferingResponseWrapper extends HttpServletResponseWrapper {

  private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
  private ServletOutputStream outputStream;
  private PrintWriter writer;

  BufferingResponseWrapper(HttpServletResponse response) {
    super(response);
  }

  byte[] entity() {
    if (writer != null) {
      writer.flush();
    }
    return buffer.toByteArray();
  }

  @Override
  public ServletOutputStream getOutputStream() {
    if (outputStream == null) {
      outputStream = new BufferingStream(buffer);
    }
    return outputStream;
  }

  @Override
  public PrintWriter getWriter() {
    if (writer == null) {
      writer = new PrintWriter(new OutputStreamWriter(buffer, charset()));
    }
    return writer;
  }

  private Charset charset() {
    String encoding = getCharacterEncoding();
    return encoding == null ? StandardCharsets.ISO_8859_1 : Charset.forName(encoding);
  }

  @Override
  public void flushBuffer() {
    if (writer != null) {
      writer.flush();
    }
  }

  @Override
  public void reset() {
    super.reset();
    buffer.reset();
  }

  @Override
  public void resetBuffer() {
    super.resetBuffer();
    buffer.reset();
  }

  private static final class BufferingStream extends ServletOutputStream {

    private final ByteArrayOutputStream buffer;

    private BufferingStream(ByteArrayOutputStream buffer) {
      this.buffer = buffer;
    }

    @Override
    public void write(int b) {
      buffer.write(b);
    }

    @Override
    public void write(byte[] b, int off, int len) {
      buffer.write(b, off, len);
    }

    @Override
    public boolean isReady() {
      return true;
    }

    @Override
    public void setWriteListener(WriteListener writeListener) {
      throw new UnsupportedOperationException("this service writes responses synchronously");
    }
  }
}
