package ro.pricechecker.api;

import java.time.ZoneOffset;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import ro.pricechecker.api.generated.LookupApi;
import ro.pricechecker.api.generated.model.LookupResponse;
import ro.pricechecker.api.generated.model.ResultRow;
import ro.pricechecker.ean.Ean;
import ro.pricechecker.lookup.LookupResult;
import ro.pricechecker.lookup.LookupRow;
import ro.pricechecker.lookup.LookupService;

/**
 * {@code GET /api/lookup}: validates the EAN before any other work (S-11, AD-13), then maps the
 * lookup rows to the generated DTOs. Rejections are mapped by {@link EanProblemHandler}.
 */
@RestController
public class LookupController implements LookupApi {

  private final LookupService lookupService;

  public LookupController(LookupService lookupService) {
    this.lookupService = lookupService;
  }

  @Override
  public ResponseEntity<LookupResponse> lookup(String ean) {
    Ean valid = Ean.parse(ean);
    LookupResult result = lookupService.lookup(valid);
    List<ResultRow> rows = result.rows().stream().map(LookupController::toDto).toList();
    return ResponseEntity.ok(new LookupResponse(result.ean(), rows));
  }

  private static ResultRow toDto(LookupRow row) {
    ResultRow dto =
        new ResultRow(
            row.retailerId(),
            row.retailerName(),
            ResultRow.StatusEnum.valueOf(row.status().name()),
            row.lowest());
    dto.setPriceMinor(row.priceMinor());
    if (row.availability() != null) {
      dto.setAvailability(ResultRow.AvailabilityEnum.valueOf(row.availability().name()));
    }
    dto.setUrl(row.url());
    if (row.fetchedAt() != null) {
      dto.setFetchedAt(row.fetchedAt().atOffset(ZoneOffset.UTC));
    }
    return dto;
  }
}
