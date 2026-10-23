package bankservice.port.incoming.adapter.resources;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CoderResult;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

/**
 * A request entity read as JSON's own encoding detection reads it (UTF-8, UTF-16 or UTF-32),
 * whatever charset its Content-Type names.
 */
final class JsonEntity {

  private static final Charset UTF_32BE = Charset.forName("UTF-32BE");
  private static final Charset UTF_32LE = Charset.forName("UTF-32LE");

  private JsonEntity() {
  }

  /**
   * True when the bytes decode to nothing but JSON whitespace (an empty entity included).
   */
  static boolean isBlank(byte[] bytes) {
    Optional<String> decoded = decode(bytes);
    return decoded.isPresent() && decoded.get().chars().allMatch(JsonEntity::isWhitespace);
  }

  private static boolean isWhitespace(int c) {
    return c == ' ' || c == '\t' || c == '\n' || c == '\r';
  }

  /**
   * RFC 4627 section 3: the zero bytes among the first four tell UTF-32 and UTF-16 from UTF-8.
   */
  private static Charset encoding(byte[] b) {
    if (b.length >= 4 && b[0] == 0 && b[1] == 0 && b[2] == 0) {
      return UTF_32BE;
    }
    if (b.length >= 4 && b[1] == 0 && b[2] == 0 && b[3] == 0) {
      return UTF_32LE;
    }
    if (b.length >= 2 && b[0] == 0) {
      return StandardCharsets.UTF_16BE;
    }
    if (b.length >= 2 && b[1] == 0) {
      return StandardCharsets.UTF_16LE;
    }
    return StandardCharsets.UTF_8;
  }

  private static Optional<String> decode(byte[] bytes) {
    return invalidByte(bytes) < 0
        ? Optional.of(new String(bytes, encoding(bytes))) : Optional.empty();
  }

  /**
   * The 0-based offset of the first byte of the first malformed sequence, or -1 when none is.
   */
  private static int invalidByte(byte[] bytes) {
    CharsetDecoder decoder = encoding(bytes).newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT);
    ByteBuffer in = ByteBuffer.wrap(bytes);
    CharBuffer out = CharBuffer.allocate(bytes.length + 1);
    CoderResult result = decoder.decode(in, out, true);
    if (result.isError()) {
      return in.position();
    }
    result = decoder.flush(out);
    return result.isError() ? in.position() : -1;
  }
}
