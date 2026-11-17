package ro.pricechecker.arch;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Text scan for what ArchUnit cannot see (ADR 0014): retailer domains and CSS selectors in string
 * literals outside {@code retailer/<id>/}.
 */
final class RetailerTextScan {

  private static final Pattern STRING_LITERAL = Pattern.compile("\"(?:[^\"\\\\\\n]|\\\\.)*\"");

  private static final Pattern DOMAIN =
      Pattern.compile(
          "(?<![\\w.-])[a-z0-9-]+(?:\\.[a-z0-9-]+)*\\.(?:ro|com|net|org|eu|shop|store)(?![\\w-])",
          Pattern.CASE_INSENSITIVE);

  private static final Pattern ATTRIBUTE_SELECTOR =
      Pattern.compile("\\[[\\w-]+[~|^$*]?=[^\\]]*\\]");

  private static final Pattern COMBINATOR_SELECTOR =
      Pattern.compile("[\\w.#\\]-]\\s*[>+~]\\s*[\\w.#\\[-]");

  private static final Pattern TAG_SELECTOR =
      Pattern.compile(
          "^(?:div|span|a|p|li|ul|ol|meta|table|td|tr|th|h[1-6]|section|img|button|strong|b|i|em)"
              + "[.#][\\w-]+");

  private static final Pattern BARE_SELECTOR = Pattern.compile("^[.#][A-Za-z_][\\w-]*$");

  private RetailerTextScan() {}

  /**
   * Returns the names of sources (outside {@code retailer/}) holding a domain or selector literal.
   */
  static List<String> offendingFiles(Map<String, String> sourcesByPath) {
    List<String> offenders = new ArrayList<>();
    new TreeMap<>(sourcesByPath)
        .forEach((path, source) -> {
          if (!isRetailerPath(path) && hasForbiddenLiteral(source)) {
            offenders.add(path);
          }
        });
    return offenders;
  }

  static List<String> scanDirectory(Path sourceRoot) {
    Map<String, String> sources = new TreeMap<>();
    try (Stream<Path> files = Files.walk(sourceRoot)) {
      for (Path file :
          (Iterable<Path>) files.filter(p -> p.toString().endsWith(".java"))::iterator) {
        sources.put(sourceRoot.relativize(file).toString(), Files.readString(file));
      }
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    return offendingFiles(sources);
  }

  /** Only {@code ro/pricechecker/retailer/<id>/...} is exempt (AD-1). */
  private static boolean isRetailerPath(String path) {
    return path.replace('\\', '/').matches("ro/pricechecker/retailer/[^/]+/.+");
  }

  private static boolean hasForbiddenLiteral(String source) {
    Matcher literals = STRING_LITERAL.matcher(source);
    while (literals.find()) {
      String literal = literals.group();
      String text = literal.substring(1, literal.length() - 1);
      if (DOMAIN.matcher(text).find()
          || ATTRIBUTE_SELECTOR.matcher(text).find()
          || COMBINATOR_SELECTOR.matcher(text).find()
          || TAG_SELECTOR.matcher(text).find()
          || BARE_SELECTOR.matcher(text).find()) {
        return true;
      }
    }
    return false;
  }
}
