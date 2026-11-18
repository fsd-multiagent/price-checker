---
title: 'Runtime kill-switch config read by the backend'
type: 'feature'
created: '2026-11-18'
status: 'done'
baseline_revision: '37236ecaa18be8277bca478f5094e7cd5d87fcf1'
review_loop_iteration: 0
followup_review_recommended: true
context:
  - '{project-root}/_bmad-output/planning-artifacts/architecture/architecture-price-checker-2026-11-17/ARCHITECTURE-SPINE.md'
warnings: []
deferred:
  - summary: >-
      No runtime.yaml exists outside the VM, so a local or compose run shows every row DISABLED and logs MISSING every 15 s.
    evidence: |-
      Fail closed is required by AD-9; the VM mount and the kill-switch workflow are stories 3.2 and 3.4, and a sample file or a CLAUDE.md note belongs there.
    location: >-
      backend/src/main/resources/application.yaml
    severity: medium
---

<intent-contract>

## Intent

**Problem:** The operator has no way to stop a retailer without a deploy (FR-7, S-8). The backend ignores `runtime.yaml`, so every Approved, enabled retailer is always fetched.

**Approach:** Add a `runtime.yaml` reader in `registry` that re-reads the file at most every 15 s (time from the injected `Clock`), fails closed, and keeps the last good settings on a bad read. `LookupService` asks it before building each row and shows a stopped retailer as `DISABLED`; a rejected read is logged through `LookupLog`.

## Boundaries & Constraints

**Always:**
- File format: top-level `retailers:` mapping keyed by register domain; each entry a mapping with `fetching: allowed|stopped`. Other per-retailer keys (`clearGeneration`, `runnerBlock`, `notBefore`) are tolerated and ignored (stories 2.6, 2.7 read them).
- Path from Spring property `price-checker.runtime-file` (env `PRICE_CHECKER_RUNTIME_FILE`), default `/opt/price-checker/config/runtime.yaml` (AD-9).
- Fail closed: retailer missing from the file, or no good read since start, means stopped.
- A read is rejected (keep last good, log an error) when the file is missing or unreadable, is not valid YAML, has no `retailers` mapping, an entry is not a mapping, or `fetching` is not exactly `allowed` or `stopped`.
- Re-read when no read happened yet or at least 15 s passed since the last read attempt (injected `Clock`, T-5). Thread-safe: the snapshot is immutable and swapped atomically.
- Row precedence: adapter `disabled` or fetching stopped gives `DISABLED`, before the known-EAN check and before any adapter call.
- Rows stay exactly the Approved `retailers.yaml` entries (AD-8); `runtime.yaml` can only stop, never add or enable.
- Error log is one `LookupLog` line: level `ERROR`, event `runtime_config_rejected`, outcome the reason code; never the file content or exception message (AD-14).
- `registry` keeps depending on no other app package (ArchUnit).

**Never:** No scheduler thread or `@EnableScheduling`; no write to `runtime.yaml`; no Block, `clearGeneration` or `notBefore` logic; no change to `retailers.yaml`, the register or the API contract.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Allowed | `a.example: {fetching: allowed}` | a.example row built as before (adapter called) | none |
| Stopped | `fetching: stopped` | row `DISABLED`, no adapter call | none |
| Flip within 60 s | stopped → allowed, clock +15 s | next Lookup row restored | none |
| Not due | file changed, clock +14 s | old settings still used | none |
| Missing retailer | file lists only b.example | a.example `DISABLED` | none |
| No good read since start | file missing / garbage | every row `DISABLED` | ERROR line logged per rejected read |
| Bad after good | good allowed, then garbage | still allowed | ERROR line, outcome `UNPARSEABLE` |
| Bad value | `fetching: yes` | whole read rejected, last good kept | ERROR line, outcome `UNPARSEABLE` |
| Non-Approved allowed | c.example (Proposed) `allowed` | no c.example row, no adapter call | none |

</intent-contract>

## Code Map

- `backend/src/main/java/ro/pricechecker/registry/RegistryYaml.java` -- SnakeYAML `SafeConstructor` + no duplicate keys pattern to reuse for the new parser.
- `backend/src/main/java/ro/pricechecker/registry/Registry.java` -- `approvedDomains()`; registry bean style (`@Component`, package-private test constructor).
- `backend/src/main/java/ro/pricechecker/lookup/LookupService.java:40-63` -- `lookup` filters Approved; `row()` returns `DISABLED` for `!config.enabled()`: add the runtime check there.
- `backend/src/main/java/ro/pricechecker/lookup/LookupLog.java` -- allowlisted JSON logger; add `runtimeConfigRejected(String reason)`.
- `backend/src/test/java/ro/pricechecker/registry/TestRegistries.java` -- `mixed()` registry: a (Approved, enabled), b (Approved, disabled), c (Proposed).
- `LookupService` constructed in `lookup/LookupServiceTest.java:56`, `lookup/LookupSkeletonAcceptanceGapsTest.java:125`, `api/LookupSkeletonHttpTest.java:54` -- update for the new constructor argument.
- `backend/src/test/java/ro/pricechecker/arch/ArchitectureRules.java:32` -- `registry` -> nothing; `lookup` -> registry allowed.

## Tasks & Acceptance

**Execution:**
- `backend/src/main/java/ro/pricechecker/registry/RuntimeSettings.java` -- new: immutable parsed settings (`isAllowed(domain)`), static `parse(String)` throwing `IllegalArgumentException` on rejection -- one parser.
- `backend/src/main/java/ro/pricechecker/registry/RuntimeSwitch.java` -- new `@Component`: path + `Clock`; `refresh()` returns `Optional<String>` rejection reason (`MISSING`, `UNREADABLE`, `UNPARSEABLE`) when a due read failed; `isAllowed(domain)` on current snapshot -- 15 s lazy poll, fail closed.
- `backend/src/main/java/ro/pricechecker/lookup/LookupLog.java` -- add `runtimeConfigRejected` -- AC-7.5 error log.
- `backend/src/main/java/ro/pricechecker/lookup/LookupService.java` -- inject `RuntimeSwitch` + `LookupLog`; call `refresh()` once per Lookup, log rejection, `DISABLED` when stopped.
- Tests: `registry/RuntimeSettingsTest.java`, `registry/RuntimeSwitchTest.java` (temp file, mutable clock), `lookup/RuntimeKillSwitchTest.java` (I/O matrix through `LookupService`), update the three constructor call sites, `LookupLogTest` for the new line -- tags `AC-2.3.1`..`AC-2.3.4` (T-1).

**Acceptance Criteria:**
- Given `fetching: stopped` for an Approved enabled retailer, when the clock advances 15 s and a Lookup runs, then its row is `DISABLED` with no adapter call; setting `allowed` and advancing 15 s restores it (2.3.1).
- Given the retailer missing, or no good read since start, when a Lookup runs, then the row is `DISABLED` (2.3.2).
- Given a good file then an unparseable one, when polled, then the last good settings stay and one ERROR `runtime_config_rejected` line with only allowlisted keys is written (2.3.3).
- Given `allowed` for a Proposed retailer, when a Lookup runs, then it has no row and its adapter is not called (2.3.4).

## Design Notes

Lazy polling instead of a scheduler: the read is triggered by a Lookup when 15 s passed on the injected `Clock`, so tests stay deterministic (T-5) and no thread is needed; the observable bound (change visible within 15 s of the next Lookup, well under 60 s) is the same. `RuntimeSwitch` returns the rejection reason instead of logging so `registry` keeps no dependency and `LookupLog` stays the only log (AD-14).

## Verification

**Commands:**
- `backend/gradlew -p backend check` -- expected: BUILD SUCCESSFUL (Spotless, `-Werror`, tests, ArchUnit).

## Auto Run Result

**Summary:** The backend reads `runtime.yaml` (path `price-checker.runtime-file`, env `PRICE_CHECKER_RUNTIME_FILE`, default `/opt/price-checker/config/runtime.yaml`) at most every 15 s on the injected Clock, lazily on each Lookup. It fails closed (missing retailer or no good read means stopped), keeps the last good settings on a bad read, and logs one allowlisted ERROR line `runtime_config_rejected`. A stopped retailer's row is `DISABLED` before any other check or adapter call. `runtime.yaml` can never add a row for a non-Approved retailer.

**Deviations from the spec (forced by existing tests):** `RuntimeSwitch` is a plain class wired by root-level `RuntimeSwitchConfig` (AC 1.5.4 forbids `@Value` in `registry`); the rejection reason is the enum `RuntimeSwitch.Rejection` (AC 1.4.3 allows only enum or primitive `LookupLog` parameters).

**Files changed:**
- `backend/src/main/java/ro/pricechecker/registry/RuntimeSettings.java` -- parser and immutable settings.
- `backend/src/main/java/ro/pricechecker/registry/RuntimeSwitch.java` -- 15 s lazy poll, fail closed, rejection reasons.
- `backend/src/main/java/ro/pricechecker/RuntimeSwitchConfig.java` -- bean wiring from the property.
- `backend/src/main/java/ro/pricechecker/lookup/LookupService.java` -- refresh per Lookup, log rejection, `DISABLED` when stopped.
- `backend/src/main/java/ro/pricechecker/lookup/LookupLog.java` -- `runtimeConfigRejected`.
- `backend/src/main/resources/application.yaml` -- `price-checker.runtime-file`.
- Tests: `RuntimeSettingsTest`, `RuntimeSwitchTest`, `RuntimeKillSwitchTest`, `RuntimeSwitchConfigTest`, `LookupLogTest`, helpers `MutableClock`, `TestRegistries.runtimeAllowing`, and three updated `LookupService` call sites.

**Review:** 30 findings. Patched 2 entries (both medium): clock stepping backwards, and the untested property wiring. Deferred 1 (medium): no local `runtime.yaml` until stories 3.2 and 3.4. All other findings were rejected, with reasons in the triage log. Patches were applied by the parent session because the run budget was low.

**Follow-up review recommended:** true. Two medium entries were patched on a first pass; the unverified risk is the new `RuntimeSwitchConfigTest` Spring context and the backwards-clock branch, which were not re-reviewed.

**Verification:** `backend/gradlew -p backend check` passed (Spotless, `-Werror` compile, all tests, ArchUnit), before and after the patches. Every row of the I/O matrix has a passing test.

**Residual risks:** Any backend run without the file shows every row `DISABLED`. A retailer entry without `fetching` (for example, one written by a later workflow with only `runnerBlock`) rejects the whole file.
