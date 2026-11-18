package ro.pricechecker.lookup;

import java.net.URI;
import org.springframework.beans.factory.support.BeanDefinitionBuilder;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.ImportBeanDefinitionRegistrar;
import org.springframework.core.type.AnnotationMetadata;
import ro.pricechecker.adapter.AdapterOutcome;
import ro.pricechecker.adapter.RetailerAdapter;
import ro.pricechecker.adapter.RetailerHttp;
import ro.pricechecker.ean.Ean;
import ro.pricechecker.registry.Registry;

/**
 * Test-only: one no-op adapter bean per {@code retailers.yaml} id, so Spring contexts start while
 * no real adapter exists (AC 1.6.1 assumption).
 */
@Configuration(proxyBeanMethods = false)
@Import(StubAdapters.Registrar.class)
public class StubAdapters {

  static final class Registrar implements ImportBeanDefinitionRegistrar {
    @Override
    public void registerBeanDefinitions(AnnotationMetadata meta, BeanDefinitionRegistry registry) {
      new Registry()
          .retailers()
          .forEach(r ->
              registry.registerBeanDefinition(
                  "stubAdapter-" + r.domain(),
                  BeanDefinitionBuilder.genericBeanDefinition(
                          RetailerAdapter.class, () -> noop(r.domain()))
                      .getBeanDefinition()));
    }
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
