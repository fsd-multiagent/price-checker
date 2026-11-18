package ro.pricechecker.retailer;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AssignableTypeFilter;
import ro.pricechecker.adapter.RetailerAdapter;

/**
 * Guards for story 4.2: every real Adapter has an altered fixture and is exercised by {@link
 * LayoutChangeTest} (AC 4.2.1), and only the failure-expecting tests name the altered fixture, so
 * normal CI stays green (AC 4.2.3). Reads only the repository, never the network (T-3).
 */
class LayoutChangeCoverageTest {

  /** Built from parts so this file does not count as a reader of the altered fixture. */
  private static final String ALTERED = "layout" + '-' + "changed.html";

  private static final Path TEST_SOURCES = Path.of("src", "test", "java");

  /** The tests that read the altered fixture; each asserts the failure it causes. */
  private static final Set<String> FAILURE_EXPECTING_TESTS =
      Set.of("LayoutChangeTest.java", "LayoutChangeHealthCheckTest.java");

  /** Ids of the real Adapters under {@code ro.pricechecker.retailer}, found by classpath scan. */
  private static Set<String> realAdapterIds() {
    ClassPathScanningCandidateComponentProvider scanner =
        new ClassPathScanningCandidateComponentProvider(false);
    scanner.addIncludeFilter(new AssignableTypeFilter(RetailerAdapter.class));
    Set<String> ids = new TreeSet<>();
    for (BeanDefinition candidate : scanner.findCandidateComponents("ro.pricechecker.retailer")) {
      try {
        ids.add(
            ((RetailerAdapter)
                    Class.forName(candidate.getBeanClassName())
                        .getDeclaredConstructor()
                        .newInstance())
                .retailerId());
      } catch (ReflectiveOperationException e) {
        throw new IllegalStateException(candidate.getBeanClassName(), e);
      }
    }
    return ids;
  }

  @Test
  @Tag("AC-4.2.1")
  void ac_4_2_1_everyRealAdapterHasAnAlteredFixture() {
    Set<String> ids = realAdapterIds();

    assertThat(ids).isNotEmpty();
    assertThat(ids)
        .allSatisfy(
            id -> assertThat(Path.of("..", "fixtures", id, ALTERED)).as(id).isRegularFile());
  }

  @Test
  @Tag("AC-4.2.1")
  void ac_4_2_1_layoutChangeTestCoversEveryRealAdapter() {
    Set<String> covered =
        LayoutChangeTest.adapters()
            .map(a -> ((RetailerAdapter) a.get()[0]).retailerId())
            .collect(Collectors.toCollection(TreeSet::new));

    assertThat(covered).isEqualTo(realAdapterIds());
  }

  @Test
  @Tag("AC-4.2.3")
  void ac_4_2_3_onlyFailureExpectingTestsNameTheAlteredFixture() throws IOException {
    Set<String> readers;
    try (Stream<Path> files = Files.walk(TEST_SOURCES)) {
      readers =
          files
              .filter(p -> p.toString().endsWith(".java"))
              .filter(LayoutChangeCoverageTest::mentionsAlteredFixture)
              .map(p -> p.getFileName().toString())
              .collect(Collectors.toCollection(TreeSet::new));
    }

    assertThat(readers).isEqualTo(new TreeSet<>(FAILURE_EXPECTING_TESTS));
  }

  private static boolean mentionsAlteredFixture(Path file) {
    try {
      return Files.readString(file).contains(ALTERED);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
