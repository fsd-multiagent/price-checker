package ro.pricechecker.lookup;

import java.lang.reflect.InvocationTargetException;
import java.net.URI;
import java.util.HashSet;
import java.util.Set;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.support.BeanDefinitionBuilder;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.ImportBeanDefinitionRegistrar;
import org.springframework.core.type.AnnotationMetadata;
import org.springframework.core.type.filter.AssignableTypeFilter;
import ro.pricechecker.adapter.AdapterOutcome;
import ro.pricechecker.adapter.RetailerAdapter;
import ro.pricechecker.adapter.RetailerHttp;
import ro.pricechecker.ean.Ean;
import ro.pricechecker.registry.Registry;

/**
 * Test-only: one no-op adapter bean per {@code retailers.yaml} id that no real adapter in {@code
 * ro.pricechecker.retailer} serves yet, so Spring contexts start with exactly one adapter per id
 * (AC 1.6.1 assumption, story 2.10).
 */
@Configuration(proxyBeanMethods = false)
@Import(StubAdapters.Registrar.class)
public class StubAdapters {

  private static final String RETAILER_PACKAGE = "ro.pricechecker.retailer";

  static final class Registrar implements ImportBeanDefinitionRegistrar {
    @Override
    public void registerBeanDefinitions(AnnotationMetadata meta, BeanDefinitionRegistry registry) {
      Set<String> served = realAdapterIds();
      new Registry()
          .retailers().stream()
              .filter(r -> !served.contains(r.domain()))
              .forEach(r ->
                  registry.registerBeanDefinition(
                      "stubAdapter-" + r.domain(),
                      BeanDefinitionBuilder.genericBeanDefinition(
                              RetailerAdapter.class, () -> noop(r.domain()))
                          .getBeanDefinition()));
    }
  }

  /** Ids of the real adapters under {@code ro.pricechecker.retailer} (no-arg instantiation). */
  static Set<String> realAdapterIds() {
    ClassPathScanningCandidateComponentProvider scanner =
        new ClassPathScanningCandidateComponentProvider(false);
    scanner.addIncludeFilter(new AssignableTypeFilter(RetailerAdapter.class));
    Set<String> ids = new HashSet<>();
    for (BeanDefinition candidate : scanner.findCandidateComponents(RETAILER_PACKAGE)) {
      try {
        Class<?> type = Class.forName(candidate.getBeanClassName());
        RetailerAdapter adapter = (RetailerAdapter) type.getDeclaredConstructor().newInstance();
        ids.add(adapter.retailerId());
      } catch (ClassNotFoundException
          | NoSuchMethodException
          | InstantiationException
          | IllegalAccessException
          | InvocationTargetException e) {
        throw new IllegalStateException("cannot instantiate " + candidate.getBeanClassName(), e);
      }
    }
    return ids;
  }

  static RetailerAdapter noop(String id) {
    return new RetailerAdapter() {
      @Override
      public String retailerId() {
        return id;
      }

      @Override
      public AdapterOutcome fetchAndExtract(Ean ean, URI productUrl, RetailerHttp http) {
        return new AdapterOutcome.NotFound();
      }
    };
  }
}
