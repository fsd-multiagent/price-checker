package ro.pricechecker.api;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import ro.pricechecker.api.generated.model.EanErrorCode;
import ro.pricechecker.api.generated.model.Problem;
import ro.pricechecker.ean.EanError;
import ro.pricechecker.ean.InvalidEanException;
import ro.pricechecker.lookup.LookupLog;

/** Maps every EAN rejection on the lookup endpoint to 400 problem+json and logs it (AD-14). */
@RestControllerAdvice(assignableTypes = LookupController.class)
public class EanProblemHandler {

  private static final String TITLE = "Invalid EAN";

  private final LookupLog log;

  public EanProblemHandler(LookupLog log) {
    this.log = log;
  }

  @ExceptionHandler(InvalidEanException.class)
  ResponseEntity<Problem> invalidEan(InvalidEanException e) {
    return reject(e.error());
  }

  /** A missing {@code ean} parameter is the same as an empty one. */
  @ExceptionHandler(MissingServletRequestParameterException.class)
  ResponseEntity<Problem> missingParameter(MissingServletRequestParameterException e) {
    return reject(EanError.EAN_EMPTY);
  }

  private ResponseEntity<Problem> reject(EanError error) {
    log.eanRejected(error);
    Problem problem =
        new Problem(EanErrorCode.fromValue(error.name()))
            .title(TITLE)
            .status(HttpStatus.BAD_REQUEST.value());
    return ResponseEntity.badRequest()
        .contentType(MediaType.APPLICATION_PROBLEM_JSON)
        .body(problem);
  }
}
