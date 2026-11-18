package ro.pricechecker.adapter;

/**
 * Gives the {@link RetailerHttp} bound to one {@code retailers.yaml} id; the shared fetch client
 * implements it (AD-6), so a Lookup binds one client per retailer it loads.
 */
@FunctionalInterface
public interface RetailerHttpBinder {

  /**
   * The client for {@code retailerId}.
   *
   * @throws IllegalArgumentException when the id has no {@code retailers.yaml} entry
   */
  RetailerHttp bind(String retailerId);
}
