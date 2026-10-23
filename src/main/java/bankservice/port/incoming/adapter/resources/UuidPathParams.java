package bankservice.port.incoming.adapter.resources;

import java.beans.PropertyEditorSupport;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.web.bind.WebDataBinder;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.InitBinder;

/**
 * Dropwizard's UUIDParam accepted only the canonical 8-4-4-4-12 hexadecimal form (in either letter
 * case). Spring's default conversion also takes shortened groups and surrounding spaces, and a
 * failed conversion falls back to the lenient default editor, so the strict form is a binder
 * editor of its own.
 */
@ControllerAdvice
public class UuidPathParams {

  private static final Pattern CANONICAL = Pattern.compile(
      "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

  @InitBinder
  public void canonicalUuids(WebDataBinder binder) {
    binder.registerCustomEditor(UUID.class, new PropertyEditorSupport() {
      @Override
      public void setAsText(String text) {
        if (!CANONICAL.matcher(text).matches()) {
          throw new IllegalArgumentException("not a canonical UUID: " + text);
        }
        setValue(UUID.fromString(text));
      }
    });
  }
}
