package ro.pricechecker.api;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import ro.pricechecker.api.generated.StatusApi;
import ro.pricechecker.api.generated.model.RetailerStatus;
import ro.pricechecker.api.generated.model.StatusResponse;
import ro.pricechecker.lookup.LookupLog;
import ro.pricechecker.registry.BlockStore;
import ro.pricechecker.registry.Registry;
import ro.pricechecker.registry.RetailerConfig;
import ro.pricechecker.registry.RuntimeSwitch;

/**
 * {@code GET /api/status} (AD-3, AD-16): one entry per Approved retailer, in {@code retailers.yaml}
 * order, with {@code enabled} (adapter enabled and fetching allowed by the Kill switch), {@code
 * blocked} ({@link BlockStore#isBlocked}) and {@code notBefore} ({@link BlockStore#notBefore}) only
 * while it is in the future. Refreshes the Kill switch like a Lookup does. Never carries a price.
 */
@RestController
public class StatusController implements StatusApi {

  private final Registry registry;
  private final RuntimeSwitch runtime;
  private final BlockStore blocks;
  private final Clock clock;
  private final LookupLog log;

  public StatusController(
      Registry registry, RuntimeSwitch runtime, BlockStore blocks, Clock clock, LookupLog log) {
    this.registry = registry;
    this.runtime = runtime;
    this.blocks = blocks;
    this.clock = clock;
    this.log = log;
  }

  @Override
  public ResponseEntity<StatusResponse> status() {
    runtime.refresh().ifPresent(log::runtimeConfigRejected);
    Instant now = clock.instant();
    List<RetailerStatus> retailers = new ArrayList<>();
    for (RetailerConfig config : registry.retailers()) {
      if (registry.approvedDomains().contains(config.domain())) {
        retailers.add(toDto(config, now));
      }
    }
    return ResponseEntity.ok(new StatusResponse(retailers));
  }

  private RetailerStatus toDto(RetailerConfig config, Instant now) {
    String id = config.domain();
    RetailerStatus dto =
        new RetailerStatus(id, id, config.enabled() && runtime.isAllowed(id), blocks.isBlocked(id));
    Optional<Instant> notBefore = blocks.notBefore(id);
    if (notBefore.isPresent() && notBefore.get().isAfter(now)) {
      dto.setNotBefore(notBefore.get().atOffset(ZoneOffset.UTC));
    }
    return dto;
  }
}
