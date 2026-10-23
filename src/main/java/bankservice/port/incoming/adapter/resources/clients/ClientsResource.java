package bankservice.port.incoming.adapter.resources.clients;

import static com.google.common.base.Preconditions.checkNotNull;
import static org.springframework.http.MediaType.APPLICATION_JSON_VALUE;

import bankservice.domain.model.client.Client;
import bankservice.domain.model.client.Email;
import bankservice.service.client.ClientService;
import bankservice.service.client.EnrollClientCommand;
import jakarta.validation.Valid;
import java.net.URI;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

@RestController
@RequestMapping(path = "/clients", produces = APPLICATION_JSON_VALUE)
public class ClientsResource {

  private final ClientService clientService;

  public ClientsResource(ClientService clientService) {
    this.clientService = checkNotNull(clientService);
  }

  @PostMapping(consumes = APPLICATION_JSON_VALUE)
  // The original declared no @NotNull here, unlike ClientResource#put, so an absent body
  // reached the handler as null and failed there. required = false preserves that.
  public ResponseEntity<Void> post(@Valid @RequestBody(required = false) ClientDto newClientDto) {
    EnrollClientCommand enrollClientCommand = new EnrollClientCommand(
        newClientDto.getName(), new Email(newClientDto.getEmail()));
    Client client = clientService.process(enrollClientCommand);
    // Jersey resolved the resource path against the base URI, so the request's query string and
    // any trailing slash never reached the Location field.
    URI clientUri = ServletUriComponentsBuilder.fromCurrentContextPath()
        .path("/clients/{id}")
        .buildAndExpand(client.getId())
        .toUri();
    return ResponseEntity.created(clientUri).build();
  }
}
