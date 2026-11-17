package ro.pricechecker.archfixtures.httpinside.fetch;

import java.net.http.HttpClient;

public class GoodFetch {
  Object client() {
    return HttpClient.newHttpClient();
  }
}
