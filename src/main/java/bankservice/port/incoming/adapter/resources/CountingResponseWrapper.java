package bankservice.port.incoming.adapter.resources;

import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.WriteListener;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpServletResponseWrapper;
import java.io.IOException;
import java.io.PrintWriter;

/**
 * Counts the bytes an application writes to a response, so a filter can tell an answer that
 * carries an entity from one that carries none. The original stack made that same distinction:
 * Jersey turned an empty error entity into a {@code sendError}, which is what made Jetty render
 * its own page instead of passing the bare status through.
 */
class CountingResponseWrapper extends HttpServletResponseWrapper {

  private long written;
  private ServletOutputStream outputStream;
  private PrintWriter writer;

  CountingResponseWrapper(HttpServletResponse response) {
    super(response);
  }

  long getWrittenByteCount() {
    return written;
  }

  @Override
  public ServletOutputStream getOutputStream() throws IOException {
    if (outputStream == null) {
      outputStream = new CountingStream(super.getOutputStream());
    }
    return outputStream;
  }

  @Override
  public PrintWriter getWriter() throws IOException {
    if (writer == null) {
      writer = new CountingWriter(super.getWriter());
    }
    return writer;
  }

  @Override
  public void reset() {
    super.reset();
    written = 0;
  }

  @Override
  public void resetBuffer() {
    super.resetBuffer();
    written = 0;
  }

  private final class CountingStream extends ServletOutputStream {

    private final ServletOutputStream delegate;

    private CountingStream(ServletOutputStream delegate) {
      this.delegate = delegate;
    }

    @Override
    public void write(int b) throws IOException {
      delegate.write(b);
      written++;
    }

    @Override
    public void write(byte[] b, int off, int len) throws IOException {
      delegate.write(b, off, len);
      written += len;
    }

    @Override
    public void flush() throws IOException {
      delegate.flush();
    }

    @Override
    public void close() throws IOException {
      delegate.close();
    }

    @Override
    public boolean isReady() {
      return delegate.isReady();
    }

    @Override
    public void setWriteListener(WriteListener writeListener) {
      delegate.setWriteListener(writeListener);
    }
  }

  private final class CountingWriter extends PrintWriter {

    private CountingWriter(PrintWriter delegate) {
      super(delegate);
    }

    @Override
    public void write(int c) {
      super.write(c);
      written++;
    }

    @Override
    public void write(char[] buf, int off, int len) {
      super.write(buf, off, len);
      written += len;
    }

    @Override
    public void write(String s, int off, int len) {
      super.write(s, off, len);
      written += len;
    }
  }
}
