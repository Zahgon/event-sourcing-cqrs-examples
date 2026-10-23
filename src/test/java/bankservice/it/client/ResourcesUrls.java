package bankservice.it.client;

import static com.google.common.base.Preconditions.checkNotNull;

import java.net.URI;
import org.springframework.web.util.UriComponentsBuilder;
import org.springframework.web.util.UriTemplate;

public class ResourcesUrls {

  private final String baseUrl;

  public ResourcesUrls(String baseUrl) {
    this.baseUrl = checkNotNull(baseUrl);
  }

  public URI clientsUrl() {
    return localhost().path("/clients").build().toUri();
  }

  public URI clientUrl(String clientId) {
    return clientUriTemplate().expand(clientId);
  }

  public UriTemplate clientUriTemplate() {
    return new UriTemplate(baseUrl + "/clients/{id}");
  }

  public URI accountsUrl() {
    return localhost().path("/accounts").build().toUri();
  }

  public URI accountUrl(String accountId) {
    return accountUriTemplate().expand(accountId);
  }

  public UriTemplate accountUriTemplate() {
    return new UriTemplate(baseUrl + "/accounts/{id}");
  }

  public URI depositsUrl(String accountId) {
    return localhost().path("/accounts/{id}/deposits").buildAndExpand(accountId).toUri();
  }

  public URI withdrawalsUrl(String accountId) {
    return localhost().path("/accounts/{id}/withdrawals").buildAndExpand(accountId).toUri();
  }

  public URI clientAccountsUrl(String clientId) {
    return localhost().path("/clients/{id}/accounts").buildAndExpand(clientId).toUri();
  }

  public URI accountTransactionsUrl(String accountId) {
    return localhost().path("/accounts/{id}/transactions").buildAndExpand(accountId).toUri();
  }

  private UriComponentsBuilder localhost() {
    return UriComponentsBuilder.fromUriString(baseUrl);
  }
}
