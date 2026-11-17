package ro.pricechecker.archfixtures.extractnio.extract;

import java.io.IOException;
import java.net.InetAddress;
import java.nio.file.Files;
import java.nio.file.Path;

/** Uses java.nio.file and java.net (not an HTTP client); only the new extract rule catches this. */
public class NioExtract {
  String read(Path page) throws IOException {
    return Files.readString(page) + InetAddress.getLoopbackAddress();
  }
}
