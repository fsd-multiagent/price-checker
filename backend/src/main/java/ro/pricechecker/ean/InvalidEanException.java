package ro.pricechecker.ean;

/** An input failed the AD-13 rule. The message is the code name only, never the input (AD-14). */
public final class InvalidEanException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  private final EanError error;

  public InvalidEanException(EanError error) {
    super(error.name());
    this.error = error;
  }

  public EanError error() {
    return error;
  }
}
