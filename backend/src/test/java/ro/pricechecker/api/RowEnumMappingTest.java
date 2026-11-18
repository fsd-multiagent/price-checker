package ro.pricechecker.api;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import ro.pricechecker.api.generated.model.ResultRow;
import ro.pricechecker.lookup.RowAvailability;
import ro.pricechecker.lookup.RowStatus;

/** Every domain status and availability has a contract value, so mapping never throws (AD-5). */
class RowEnumMappingTest {

  @Test
  @Tag("AC-1.6.4")
  void ac_1_6_4_everyRowStatusMapsToTheContract() {
    for (RowStatus status : RowStatus.values()) {
      assertThat(ResultRow.StatusEnum.valueOf(status.name()).getValue()).isEqualTo(status.name());
    }
  }

  @Test
  @Tag("AC-1.6.4")
  void ac_1_6_4_everyRowAvailabilityMapsToTheContract() {
    for (RowAvailability availability : RowAvailability.values()) {
      assertThat(ResultRow.AvailabilityEnum.valueOf(availability.name()).getValue())
          .isEqualTo(availability.name());
    }
  }
}
