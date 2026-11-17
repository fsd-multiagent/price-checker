package ro.pricechecker.archfixtures.httpoutside.lookup;

import java.net.http.HttpClient;

public class ClientUser {
  Object client() {
    return HttpClient.newHttpClient();
  }
}
