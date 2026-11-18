---
title: 'Status endpoint'
type: 'feature'
created: '2026-11-18'
status: 'done'
baseline_revision: '71375a3dfdc6ee8915706bdcffee080199b18ad3'
review_loop_iteration: 0
followup_review_recommended: false
context:
  - '{project-root}/_bmad-output/planning-artifacts/architecture/architecture-price-checker-2026-11-17/ARCHITECTURE-SPINE.md'
warnings: []
deferred:
  - summary: >-
      StatusController copies the Approved filter and the enabled rule from LookupService, so the two can drift.
    evidence: |-
      StatusController.status() and LookupService.lookup() each filter registry.retailers() by approvedDomains(), and each computes config.enabled() && runtime.isAllowed(id). A shared method needs a change outside this story's touches (api/).
    location: >-
      backend/src/main/java/ro/pricechecker/api/StatusController.java:49
    severity: low
  - summary: >-
      AGENTS.md does not yet describe /api/status (refreshes the Kill switch, every retailer enabled false locally without runtime.yaml, notBefore only when in the future).
    evidence: |-
      Agent-context docs are updated by Aglaia (Knowledge Keeper) at stage 9.
    location: >-
      AGENTS.md
    severity: low
---

<intent-contract>

## Intent

**Problem:** `GET /api/status` is in the contract (`api/openapi.yaml`, `StatusResponse`/`RetailerStatus`) but no controller implements it, so the Health check cannot learn which retailers are enabled, blocked or waiting out a `Retry-After` (AD-3, AD-16).

**Approach:** A `StatusController` in `api` implements the generated `StatusApi`: it refreshes the Kill switch like a Lookup does, then lists every Approved retailer from `Registry.retailers()` with `enabled` = adapter enabled and `RuntimeSwitch.isAllowed`, `blocked` = `BlockStore.isBlocked`, and `notBefore` = `BlockStore.notBefore` when it is after the injected `Clock`'s now.

## Boundaries & Constraints

**Always:** only Approved retailers (`registry.approvedDomains()`), in `retailers.yaml` order; `retailerId` and `retailerName` are the register domain (as in `LookupService`); `notBefore` is ISO-8601 UTC and omitted when absent or not in the future (`non_null` inclusion already drops nulls); a rejected `runtime.yaml` is logged through `LookupLog.runtimeConfigRejected`, as in a Lookup; the response never contains a price or any Lookup field.

**Never:** a contract change (the schema already exists); a new log event; contacting any retailer; business logic outside the small mapping in the controller; changes outside `backend/src/main/java/ro/pricechecker/api/` and its tests.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Mixed registry | a (Approved, enabled, allowed), b (Approved, adapter disabled), c (Proposed) | 200, `retailers` = [a enabled true blocked false, b enabled false blocked false]; c absent; no `notBefore` | — |
| Kill switch stopped | a Approved+enabled, `runtime.yaml` without a | a `enabled: false` | — |
| Active Block | `BlockStore.record(a, HTTP_403)` | a `blocked: true` | — |
| Future notBefore | `recordRetryAfter(a, now+60s)` | a `notBefore` = that instant (UTC, `Z`) | — |
| Past notBefore | stored `notBefore` before now | `notBefore` absent | — |
| Runner notBefore/block | `runtime.yaml` `notBefore` / `runnerBlock` for a | reported like the own ones (via `BlockStore`) | — |

</intent-contract>

## Code Map

- `api/openapi.yaml:38-117` -- `operationId: status`, tag `Status` → generated `ro.pricechecker.api.generated.StatusApi`, models `StatusResponse(retailers)`, `RetailerStatus(retailerId, retailerName, enabled, blocked)` + `setNotBefore(OffsetDateTime)`.
- `backend/src/main/java/ro/pricechecker/api/LookupController.java` -- pattern: `@RestController implements <Api>`, constructor injection, map to generated DTOs, `atOffset(ZoneOffset.UTC)`.
- `backend/src/main/java/ro/pricechecker/lookup/LookupService.java:51-58,72-75` -- Approved filter and `enabled` rule (`config.enabled() && runtime.isAllowed(id)`), `runtime.refresh().ifPresent(log::runtimeConfigRejected)`.
- `backend/src/main/java/ro/pricechecker/registry/BlockStore.java` -- `isBlocked(String)`, `notBefore(String)` (later of own and runner value); bean in `BlockStoreConfig`.
- `backend/src/main/java/ro/pricechecker/registry/RuntimeSwitch.java:48,91` -- `refresh()`, `isAllowed`.
- `backend/src/test/java/ro/pricechecker/arch/ArchitectureRules.java:27` -- `api` may depend on `lookup`, `ean`, `registry`.
- Tests: `api/LookupSkeletonHttpTest` (`@WebMvcTest` + `@TestConfiguration` wiring), `registry/TestRegistries` (`mixed()`, `runtimeAllowing`, `blockStore`), `registry/MutableClock`.

## Tasks & Acceptance

**Execution:**
- `backend/src/main/java/ro/pricechecker/api/StatusController.java` -- new controller implementing `StatusApi` as in Approach -- AD-3 contract.
- `backend/src/test/java/ro/pricechecker/api/StatusControllerTest.java` -- `@WebMvcTest(StatusController.class)` over `TestRegistries.mixed()`, a real `RuntimeSwitch` and `BlockStore`, fixed clock; one test per matrix row, tagged `AC-2.9.1`/`AC-2.9.2`.

**Acceptance Criteria:**
- Given Approved retailers in various states, when `GET /api/status` is called, then each Approved retailer appears with exactly `retailerId`, `retailerName`, `enabled`, `blocked` (and `notBefore` only when waiting), no price field, and no non-Approved retailer appears (2.9.1).
- Given an active Block or a future `notBefore` in `BlockStore`, when called, then `blocked` and `notBefore` equal `BlockStore.isBlocked` / `BlockStore.notBefore` (2.9.2).

## Spec Change Log

## Review Triage Log

### 2026-11-18 — Review pass

Layers: Blind Hunter and Edge Case Hunter. Verification Gap and Intent Alignment were skipped to stay inside the run budget; the pipeline's QA and code-review stages review the diff next.

- verdicts: 13 findings — high 0, medium 0, low 11, false 2, maybe-false 0
- findings:
  - `[low]` `[patch]` The refresh-and-log of a rejected `runtime.yaml` had no test. Added `ac_2_9_1_unparseableRuntimeFileStillAnswersWithEveryRetailerNotEnabled` (200, both Approved retailers `enabled: false`). The log line itself is covered by `LookupLog` tests.
  - `[low]` `[patch]` The test name claimed `retailers.yaml` order, but in `mixed()` the config order equals alphabetical order. Renamed it to `ac_2_9_1_listsOnlyApprovedRetailersWithoutPrices`.
  - `[low]` `[reject]` No test gives the Proposed c.example a Block or `notBefore`. The Approved filter runs before any state is read, so state cannot make c.example appear. A test would add setup without covering a new path.
  - `[low]` `[patch]` The Block test did not pin `enabled` next to `blocked`. Added `enabled: true`, and merged the redundant `isTrue`/`isEqualTo(isBlocked)` pair into one assertion.
  - `[false]` `[reject]` "Unexplained `runtime.refresh()`" in the Block test. It loads the settings before `record`, so the Block's generation is compared with the polled `clearGeneration`. This is harmless and explicit, not a defect.
  - `[low]` `[reject]` The 15 s poll timing is not tested through `/api/status`. `RuntimeSwitchTest` covers it, and the controller only calls `refresh()`.
  - `[low]` `[defer]` The Approved filter and the `enabled` rule are copied from `LookupService`, so the two can drift. The fix needs a shared method in `registry` or `lookup`, which is outside this story's `touches`.
  - `[low]` `[reject]` The case where both `notBefore` sources exist is untested here. The later-of-two merge is `BlockStore.notBefore`'s job and is tested in `BlockStoreTest`; the controller passes the result through.
  - `[low]` `[reject]` An Approved retailer with a disabled adapter and a Block is untested. `isBlocked` does not read the config, and adding the case would not exercise a new branch.
  - `[false]` `[defer]` `AGENTS.md` does not describe `/api/status`. Not a code defect; agent-context docs are Aglaia (Knowledge Keeper)'s at stage 9.
  - `[low]` `[reject]` A concurrent `refresh()` between the `isAllowed` and `isBlocked` reads can mix two settings in one entry. The window is one poll (15 s), the next call is consistent, and a Lookup has the same property. A snapshot API would add surface.
  - `[low]` `[reject]` (Edge Case Hunter) The later-of-two `notBefore` merge is not reached in the controller test. Same as the Blind Hunter finding above.
  - `[low]` `[reject]` A runner Block and a runner `notBefore` on the same retailer are not tested together. They are independent reads (`isBlocked`, `notBefore`), each already covered.

## Design Notes

`notBefore` is reported only when in the future: the story asks whether a retailer is "waiting", and a past value no longer holds requests. The Health check copies it into the runner's `runtime.yaml` (AD-16), where a past value would be harmless but noise.

## Verification

**Commands:**
- `backend/gradlew -p backend check` -- expected: BUILD SUCCESSFUL

## Auto Run Result

Status: done

**Summary:** `GET /api/status` is implemented. `api.StatusController` implements the generated `StatusApi`. It refreshes the Kill switch as a Lookup does, logging a rejected `runtime.yaml` through `LookupLog`. It then returns one entry per Approved retailer, in `retailers.yaml` order. Each entry has `retailerId` and `retailerName` (both the register domain), `enabled` (adapter enabled and fetching allowed), `blocked` (`BlockStore.isBlocked`), and `notBefore` (`BlockStore.notBefore`, ISO-8601 UTC). `notBefore` is included only while it is in the future. The response has no price fields, the contract is unchanged, and there is no new log event.

**Files changed:**
- `backend/src/main/java/ro/pricechecker/api/StatusController.java` (new): the endpoint.
- `backend/src/test/java/ro/pricechecker/api/StatusControllerTest.java` (new): 8 `@WebMvcTest` tests tagged `AC-2.9.1`/`AC-2.9.2`, using a real `RuntimeSwitch`, a real `BlockStore` and a fixed clock.

**Review:** 3 patches, all low: a test for an unparseable `runtime.yaml`, a test name that claimed an ordering it did not check, and an `enabled` assertion beside `blocked`. 2 items deferred: the Approved and `enabled` rules duplicated from `LookupService`, and the missing `AGENTS.md` note. 8 rejected, with reasons in the Review Triage Log. The patches were applied by the orchestrating session, not the implementation subagent, to save budget.

**Follow-up review recommended:** false. Patched: high 0, medium 0, low 3.

**Verification:** `backend/gradlew -p backend check` passes after the patches (Spotless, compile with `-Xlint:all -Werror`, tests, ArchUnit, RetailerTextScan). All 8 `StatusControllerTest` tests ran and passed, none were skipped, and every row of the matrix has a covering test.

**Residual risks:**
- The Approved filter and the `enabled` rule are duplicated between `StatusController` and `LookupService` (deferred).
- A `notBefore` equal to or before now is left out, so the Health check sees only active pauses (Design Notes).
- `retailerName` is the domain until `retailers.yaml` gets a `name` field.
