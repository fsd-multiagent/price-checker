package ro.pricechecker.api;

import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import ro.pricechecker.api.generated.LookupApi;
import ro.pricechecker.api.generated.model.LookupResponse;
import ro.pricechecker.ean.Ean;

/**
 * {@code GET /api/lookup}: validates the EAN before any other work (S-11, AD-13). Rejections are
 * mapped by {@link EanProblemHandler}. Rows arrive with the lookup service (story 1.6).
 */
@RestController
public class LookupController implements LookupApi {

  @Override
  public ResponseEntity<LookupResponse> lookup(String ean) {
    Ean valid = Ean.parse(ean);
    return ResponseEntity.ok(new LookupResponse(valid.value(), List.of()));
  }
}
