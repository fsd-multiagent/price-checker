---
title: 'BlockStore and Block handling'
type: 'feature'
created: '2026-11-18'
status: 'done'
baseline_revision: 'fa0559c8cf6be0476026ef09f11e708646688252'
review_loop_iteration: 0
followup_review_recommended: false
context:
  - '{project-root}/_bmad-output/planning-artifacts/architecture/architecture-price-checker-2026-11-17/ARCHITECTURE-SPINE.md'
warnings: []
deferred:
  - summary: >-
      A failed blocks.json write is swallowed in FetchClient.block with no log line; the Block stays
      active in memory and the WARN line went out, but a restart forgets it.
    evidence: |-
      FetchClient.block catches UncheckedIOException from BlockStore.record and only returns
      BLOCKED. LookupLog has no event for a persistence failure (AD-14 allowlist).
    location: 'backend/src/main/java/ro/pricechecker/fetch/FetchClient.java'
    severity: medium
  - summary: >-
      An unknown reason or field in blocks.json makes the backend refuse to start (fail closed);
      story 2.7 must extend the parser together with notBefore so a rollback does not brick start.
    evidence: |-
      BlockStore.parse uses Reason.valueOf and writes only the known fields.
    location: 'backend/src/main/java/ro/pricechecker/registry/BlockStore.java'
    severity: low
---

<intent-contract>

## Intent

**Problem:** A 403, a challenge page or a robots.txt disallow only refuses the current request today (`Unavailable(BLOCKED)`). The next Lookup tries again, and a restart forgets the refusal (FR-11, S-6).

**Approach:** Add `registry.BlockStore`, the only reader and writer of `blocks.json`. `FetchClient` calls `BlockStore.record` on every Block cause and refuses every request while `BlockStore.isBlocked` is true. `RuntimeSettings` learns `clearGeneration` and `runnerBlock` so that only an operator clear ends a Block (AD-9 generation rule).

## Boundaries & Constraints

**Always:** atomic whole-map write (temp file in the same directory, then `ATOMIC_MOVE`); `record` re-reads `runtime.yaml` and stamps `blockGeneration = max(polled, file) clearGeneration`; a Block is active while `blockGeneration >= clearGeneration` or `runnerBlock` is present; a Block is never retried; the WARN line goes through `LookupLog` (allowlisted fields: retailer, outcome = reason, ts); time comes from the injected `Clock` (T-5); `registry` reads no property and has no logger (a listener carries the event out).

**Never:** `notBefore`, 429 handling (story 2.7); `/api/status` (2.9); wiring the fetch client into `LookupService` or row precedence (2.8); any retry through another IP, proxy, user agent or altered headers (AC-11.3); retailer-specific challenge markers.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| 403 | page answers 403 | one request sent, `record(HTTP_403)`, `blocks.json` written, WARN line, `Unavailable(BLOCKED)` | not retried |
| Challenge | 2xx or 5xx body with a generic marker (`_cf_chl_opt`, `cf-browser-verification`, `_Incapsula_Resource`, `px-captcha`) | `record(CHALLENGE)`, `Unavailable(BLOCKED)` | 5xx challenge not retried |
| robots disallow | robots.txt disallows the path | `record(ROBOTS)`, no page request | — |
| Active Block | entry with gen >= clearGeneration, or `runnerBlock` | nothing sent, `Unavailable(BLOCKED)`, `isBlocked` true | — |
| Restart | new `BlockStore` on the same file | Block still active | — |
| Clear | `clearGeneration` incremented, then polled | Block ends | — |
| Clear raced | clear written, Block recorded, then poll | Block stays active | — |
| Missing `blocks.json` at start | no file | no Blocks | — |
| Unreadable/unparseable `blocks.json` at start | bad JSON | constructor throws, app refuses to start | fail closed |
| Write fails | directory not writable | Block stays active in memory, WARN still logged | `UncheckedIOException` from `record` |
| Bad `clearGeneration` | not a non-negative integer | whole `runtime.yaml` rejected, last good kept | existing rejection path |

</intent-contract>

## Code Map

- `backend/src/main/java/ro/pricechecker/fetch/FetchClient.java` -- `check(url)` gate; `follow` status handling (403 currently `HTTP_ERROR`, 5xx retried); `checkRobots` throws `BLOCKED` at the "story 2.6 records it" comment.
- `backend/src/main/java/ro/pricechecker/registry/RuntimeSwitch.java` -- `refresh()` poll; add a package-private `readFile()` for `record`'s re-read, plus `clearGeneration(domain)` and `hasRunnerBlock(domain)`.
- `backend/src/main/java/ro/pricechecker/registry/RuntimeSettings.java` -- parser; today it ignores `clearGeneration`/`runnerBlock`.
- `backend/src/main/java/ro/pricechecker/lookup/LookupLog.java` -- add `blocked(retailer, reason)` at WARN.
- `backend/src/main/java/ro/pricechecker/FetchClientConfig.java`, `RuntimeSwitchConfig.java` -- wiring pattern for a file-path property.
- `backend/src/test/java/ro/pricechecker/fetch/{FetchClientTest,FetchClientRecheckTest,RobotsTest}.java` -- construct `FetchClient`; give them a `BlockStore` on a `@TempDir` file.
- `backend/src/test/java/ro/pricechecker/arch/{ArchitectureRules,RetailerTextScan}.java` -- `registry` depends on no app package; no domain-like literals in main code.

## Tasks & Acceptance

**Execution:**
- `backend/src/main/java/ro/pricechecker/registry/RuntimeSettings.java` -- parse `clearGeneration` (default 0) and `runnerBlock` (present = blocked).
- `backend/src/main/java/ro/pricechecker/registry/RuntimeSwitch.java` -- accessors and `readFile()`.
- `backend/src/main/java/ro/pricechecker/registry/BlockStore.java` -- load at start, `record`, `isBlocked`, atomic write, `Listener`.
- `backend/src/main/java/ro/pricechecker/fetch/ChallengePage.java` -- generic marker detector.
- `backend/src/main/java/ro/pricechecker/fetch/FetchClient.java` -- Block gate in `check`; record on 403, challenge and robots disallow.
- `backend/src/main/java/ro/pricechecker/lookup/LookupLog.java`, `backend/src/main/java/ro/pricechecker/BlockStoreConfig.java`, `FetchClientConfig.java` -- WARN line and wiring (`price-checker.blocks-file`, default `/opt/price-checker/state/blocks.json`).
- Tests: `registry/BlockStoreTest`, `registry/RuntimeSettingsTest` additions, `fetch/BlockTest`, `fetch/ChallengePageTest`, `arch/NoRetryWorkaroundTest` (AC 2.6.4 text scan).

**Acceptance Criteria:**
- Given a 403, a challenge page or a robots disallow, when fetched, then `blocks.json` holds the Block, exactly one request was sent and a WARN line names the retailer (2.6.1).
- Given an active Block, when any request is made, then nothing is sent and `isBlocked` is true, also after a new `BlockStore` reads the same file (2.6.2).
- Given a polled `clearGeneration` increment, then the Block ends; given a Block recorded after the clear was written but before the poll, then it stays active (2.6.3).
- Given the main sources, when scanned, then no proxy, alternative user agent or header-changing code exists (2.6.4).

## Spec Change Log

## Review Triage Log

### 2026-11-18 — Review pass

Layers: Edge Case Hunter only. Blind Hunter, Verification Gap and Intent Alignment were skipped because the run budget was low; the pipeline's QA and code-review stages review the diff next. Before the review, the step-03 diff check found that `record` returned early on an already active Block without re-reading `runtime.yaml`, so an in-flight Block after an unpolled clear was cancelled at the next poll (AD-9). Fixed: the file is read before the early return, with the test `ac_2_6_3_inFlightBlockAfterAnUnpolledClearOfAnOlderBlockStaysActive`.

- verdicts: 11 findings — high 0, medium 3, low 5, false 3, maybe-false 0
- findings:
  - `[medium]` `[patch]` A missing state directory (fresh deploy) makes every write fail. Fixed: `Files.createDirectories` before the temp file, with the test `ac_2_6_1_recordCreatesAMissingStateDirectory`.
  - `[medium]` `[defer]` A failed write is swallowed with no log line. Recorded in `deferred`: it needs a new allowlisted log event.
  - `[medium]` `[reject]` Marker false positives on normal pages. The markers (`_cf_chl_opt`, `cf-browser-verification`, `_Incapsula_Resource`, `px-captcha`) appear only on interstitial pages. Generic CDN script paths and reCAPTCHA were left out on purpose, and `ChallengePageTest` checks ordinary pages.
  - `[low]` `[reject]` A challenge behind 404, 4xx or 3xx is not detected. The spec matrix limits detection to 2xx and 5xx, 429 belongs to story 2.7, and a 404 challenge is not a pattern the architecture names (C-1).
  - `[false]` `[reject]` A robots.txt 403 records a Block. It does not: `loadRobots` goes through `attempt`, not `follow`, and maps a 4xx to allow-all (RFC 9309, story 2.5).
  - `[low]` `[reject]` A `runnerBlock: null` counts as a Block. That is fail closed by intent; `clear-block` removes the key.
  - `[low]` `[defer]` An unknown reason in `blocks.json` fails startup. That is fail closed by design; it is recorded in `deferred` for story 2.7.
  - `[false]` `[reject]` A missing `clearGeneration` (0) reactivates Blocks. A retailer missing from `runtime.yaml` is stopped anyway (AD-9), and `clearGeneration` only ever increments (kill-switch contract).
  - `[low]` `[reject]` `readFile` returns empty on a malformed file, so `inFile` is 0. `record` then uses the polled value, which is the last good settings: the same fail-safe as the poll.
  - `[low]` `[reject]` The AC 2.6.4 text scan is brittle to refactors. A rename fails the scan loudly, which is the intended guard. A second `Transport` would be caught by the header and user-agent patterns.
  - `[false]` `[reject]` The listener throwing skips the write. `LookupLog` writes to stdout and does not throw in practice, and a `RuntimeException` is contained per row by `LookupService`.

## Design Notes

`blocks.json`: `{"retailers": {"<domain>": {"blockGeneration": 3, "blockedAt": "<ISO instant>", "reason": "HTTP_403"}}}`. Story 2.7 adds `notBefore` to the same entry. An unparseable file at start stops the app instead of silently clearing Blocks: fail closed, like `Registry`. `record` on an already active Block does nothing, so concurrent hits log once.

## Verification

**Commands:**
- `backend/gradlew -p backend check` -- expected: BUILD SUCCESSFUL

## Auto Run Result

Status: done

**Summary:** `registry.BlockStore` owns `blocks.json`: it loads the file at start (a missing file means no Blocks, a bad file stops the app), writes the whole map atomically on `record`, and answers `isBlocked`. A Block is active while `blockGeneration >= clearGeneration` or a `runnerBlock` is present. `FetchClient` records a Block on a 403, a generic challenge page (2xx or 5xx) or a robots.txt disallow, never retries it, and refuses every request, robots.txt included, while the retailer is blocked. `RuntimeSettings` parses `clearGeneration` and `runnerBlock`. `LookupLog.blocked` writes the WARN line.

**Files changed:**
- `registry/BlockStore.java` (new): the Block state and `blocks.json`.
- `registry/RuntimeSettings.java`, `registry/RuntimeSwitch.java`: `clearGeneration`, `runnerBlock`, and `readFile()` for the re-read in `record`.
- `fetch/ChallengePage.java` (new): the generic marker detector.
- `fetch/FetchClient.java`: the Block gate, and recording on all three causes.
- `lookup/LookupLog.java`: the `retailer_blocked` WARN event.
- `BlockStoreConfig.java` (new), `FetchClientConfig.java`, `application.yaml`: wiring and the `price-checker.blocks-file` property.
- Tests: `BlockStoreTest`, `BlockTest`, `ChallengePageTest` and `NoRetryWorkaroundTest` (new). `RuntimeSettingsTest` and `LookupLogTest` gain cases. `LookupLogAcceptanceGapsTest` now allows a domain `String` for `blocked`. The fetch tests and `FetchClientConfigTest` were adapted to the new constructor.

**Review:** two patches (the in-flight Block after an unpolled clear, and the missing state directory), two deferred items, seven rejected (reasons in the Review Triage Log).

**Follow-up review recommended:** false. One medium finding was patched and none were high.

**Verification:** `backend/gradlew -p backend check` passes (Spotless, compile with `-Xlint:all -Werror`, tests, ArchUnit, RetailerTextScan) after the last change. No test is skipped. Every matrix row has a passing test.

**Residual risks:**
- A failed `blocks.json` write is not logged (deferred).
- A robots.txt disallow of one path blocks the whole retailer until an operator clears it, as AD-6 says.
- The parser rejects unknown fields, so story 2.7 must extend it in step with `notBefore`.
