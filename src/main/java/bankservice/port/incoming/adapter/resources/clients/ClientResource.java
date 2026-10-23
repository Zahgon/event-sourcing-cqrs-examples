package bankservice.port.incoming.adapter.resources.clients;

import static com.google.common.base.Preconditions.checkNotNull;
import static org.springframework.http.MediaType.APPLICATION_JSON_VALUE;

import bankservice.domain.model.client.Client;
import bankservice.domain.model.client.Email;
import bankservice.service.client.ClientService;
import bankservice.service.client.UpdateClientCommand;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(path = "/clients/{id}", produces = APPLICATION_JSON_VALUE)
public class ClientResource {

  private final ClientService clientService;

  public ClientResource(ClientService clientService) {
    this.clientService = checkNotNull(clientService);
  }

  @GetMapping
  public ResponseEntity<ClientDto> get(@PathVariable("id") UUID clientId) {
    Optional<Client> possibleClient = clientService.loadClient(clientId);
    if (!possibleClient.isPresent()) {
      return ResponseEntity.notFound().build();
    }
    return ResponseEntity.ok(toDto(possibleClient.get()));
  }

  @PutMapping(consumes = APPLICATION_JSON_VALUE)
  // Not required at the binding level, so a request with no body and no content type still
  // reaches validation, where @NotNull rejects it with 422 as the original did.
  public ResponseEntity<Void> put(@PathVariable("id") UUID clientId,
      @Valid @NotNull @RequestBody(required = false) ClientDto clientDto) {
    UpdateClientCommand command = new UpdateClientCommand(
        clientId, clientDto.getName(), new Email(clientDto.getEmail()));
    clientService.process(command);
    return ResponseEntity.noContent().build();
  }

  private ClientDto toDto(Client client) {
    ClientDto dto = new ClientDto();
    dto.setId(client.getId());
    dto.setName(client.getName());
    dto.setEmail(client.getEmail().getValue());
    return dto;
  }
}
