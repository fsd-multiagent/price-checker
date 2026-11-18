package ro.pricechecker.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import ro.pricechecker.api.generated.model.Problem;
import ro.pricechecker.ean.EanError;
import ro.pricechecker.ean.InvalidEanException;
import ro.pricechecker.lookup.LookupLog;
import ro.pricechecker.lookup.LookupService;

/**
 * AC 1.4.2 gaps found by Eufrosina (QA): every {@link EanError} becomes the same-named contract
 * code, and an invalid EAN stops the request before any adapter or fetch work can start.
 */
@Tag("AC-1.4.2")
@ExtendWith(OutputCaptureExtension.class)
class LookupAcceptanceGapsTest {

  private static final Clock CLOCK =
      Clock.fixed(Instant.parse("2026-11-18T10:15:30Z"), ZoneOffset.UTC);

  @Test
  void ac_1_4_2_everyEanErrorMapsToTheSameNamedContractCode(CapturedOutput output) {
    EanProblemHandler handler = new EanProblemHandler(new LookupLog(CLOCK));
    for (EanError error : EanError.values()) {
      ResponseEntity<Problem> response = handler.invalidEan(new InvalidEanException(error));
      assertThat(response.getStatusCode()).as("%s", error).isEqualTo(HttpStatus.BAD_REQUEST);
      assertThat(response.getHeaders().getContentType())
          .as("%s", error)
          .isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
      Problem body = response.getBody();
      assertThat(body).isNotNull();
      assertThat(body.getCode().getValue()).isEqualTo(error.name());
      assertThat(body.getStatus()).isEqualTo(400);
      assertThat(body.getTitle()).isEqualTo("Invalid EAN");
    }
    assertThat(output.getOut().lines().filter(l -> l.contains("\"ean_rejected\"")).count())
        .isEqualTo(EanError.values().length);
  }

  @Test
  void ac_1_4_2_invalidEanThrowsBeforeAnyResponseIsBuilt() {
    LookupService service = mock(LookupService.class);
    LookupController controller = new LookupController(service);
    for (String input : new String[] {"", "40063813339A1", "1234567", "4006381333932"}) {
      assertThatThrownBy(() -> controller.lookup(input))
          .as(input)
          .isInstanceOf(InvalidEanException.class);
    }
    verifyNoInteractions(service);
  }
}
