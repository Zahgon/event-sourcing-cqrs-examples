package bankservice.port.incoming.adapter.resources;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpServletResponseWrapper;
import java.io.IOException;
import java.util.Locale;
import java.util.Set;
import org.springframework.http.HttpHeaders;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Keeps two fields Spring MVC adds on its own out of the answers, because Jersey never sent them:
 * the {@code Content-Disposition: inline;filename=f.txt} its reflected-file-download protection
 * stamps on an entity whose request path ends in an unlisted file extension, and the empty
 * {@code Accept-Patch} its OPTIONS handler writes for routes without PATCH.
 */
public class ResponseFieldFilter extends OncePerRequestFilter {

  /**
   * Runs inside every other application filter, right around the dispatcher.
   */
  public static final int ORDER = OptionsEntityFilter.ORDER + 100;

  private static final Set<String> SUPPRESSED = Set.of(
      HttpHeaders.CONTENT_DISPOSITION.toLowerCase(Locale.ROOT),
      HttpHeaders.ACCEPT_PATCH.toLowerCase(Locale.ROOT));

  @Override
  protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
      FilterChain filterChain) throws ServletException, IOException {
    filterChain.doFilter(request, new HttpServletResponseWrapper(response) {
      @Override
      public void setHeader(String name, String value) {
        if (!SUPPRESSED.contains(name.toLowerCase(Locale.ROOT))) {
          super.setHeader(name, value);
        }
      }

      @Override
      public void addHeader(String name, String value) {
        if (!SUPPRESSED.contains(name.toLowerCase(Locale.ROOT))) {
          super.addHeader(name, value);
        }
      }
    });
  }
}
