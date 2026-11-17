package ro.pricechecker.archfixtures.urloutside.lookup;

import java.io.IOException;
import java.net.URI;

public class ConnectionUser {
  Object open() throws IOException {
    return URI.create("https://localhost/").toURL().openConnection();
  }
}
