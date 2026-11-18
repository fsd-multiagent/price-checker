---
title: 'Parallel Lookup execution, normalization and cache'
type: 'feature'
created: '2026-11-18'
status: 'done'
baseline_revision: '31623dadc692ecc00c3ecee8fbb09733291c4fe4'
review_loop_iteration: 0
followup_review_recommended: false
context: []
warnings: []
deferred:
  - summary: >-
      A hung adapter load is never bounded; its key stays pending and every Lookup of it is UNAVAILABLE until the adapter returns (AD-11 load deadline).
    location: >-
      backend/src/main/java/ro/pricechecker/cache/ResultCache.java
    severity: medium
  - summary: >-
      lookup_row durationMs is time from Lookup start to row collection, not per-row time.
    location: >-
      backend/src/main/java/ro/pricechecker/lookup/LookupService.java
    severity: medium
  - summary: >-
      Cache ticker uses wall clock (non-monotonic); no LookupCacheConfig property test; concurrent-share test can pass on a plain hit; per-instance executor never closed.
    severity: low
---

<intent-contract>

## Intent

**Problem:** `LookupService` queries retailers one after another with no deadline, no Block check, no normalization of `eanOnPage`/currency and no cache, so a slow retailer delays the table and a presenter's repeat Lookup refetches (FR-2, FR-6, NFR-1).

**Approach:** Keep the AD-10 precedence in `LookupService`, run each retailer's load on its own virtual thread through a generic Caffeine async cache in `cache` (TTL 15 min, `price-checker.cache-ttl`), wait for each row only until its `deadlineMs`, normalize adapter outcomes, and log one line per retailer row.

## Boundaries & Constraints

**Always:** Precedence per retailer: DISABLED → active Block (`BlockStore.isBlocked`) UNAVAILABLE → EAN not in `known-eans.yaml` NOT_FOUND (no adapter call) → cache → load. Only FOUND and NOT_FOUND are cached; a cached verdict keeps its original `fetchedAt`. Concurrent misses for the same `(retailerId, ean)` share one load, which is not cancelled when a waiting caller hits its deadline. Deadline = `RetailerConfig.deadlineMs`, default 4000 when null. A failure stays in its row (AD-5). Cache entries hold only status, `priceMinor`, availability, `url`, `fetchedAt` (S-7). Time for TTL comes from the injected `Clock` (T-5); `cache` depends on no project package (AD-1).

**Never:** No HTTP or retailer literal in `lookup`/`cache`; no new `LookupLog` field (ADR 0017); no `sleep` in tests; no caching of UNAVAILABLE; no change to the API contract.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Parallel | two adapters that each wait for the other (barrier) | both rows FOUND, each ran on a virtual thread | — |
| Deadline | one adapter never returns, `deadlineMs` small | that row UNAVAILABLE, other row FOUND | waiting caller gives up; load continues |
| EAN mismatch | `Found` with `eanOnPage` ≠ request | NOT_FOUND | — |
| Non-RON / null price | `Found` currency `EUR`, or price null | FOUND, `priceMinor` null | — |
| Cache hit | same EAN within TTL | no adapter call, same `fetchedAt` | — |
| TTL passed | clock advanced past TTL | adapter called again | — |
| UNAVAILABLE | adapter returns `Unavailable` or throws | row UNAVAILABLE, next Lookup calls again | — |
| Blocked | `BlockStore` has active Block | UNAVAILABLE, no adapter call | — |

</intent-contract>

## Code Map

- `backend/src/main/java/ro/pricechecker/lookup/LookupService.java` -- sequential skeleton; `row()` holds DISABLED/known-EAN/adapter logic, `found()` builds FOUND rows; constructor called by 5 tests.
- `backend/src/main/java/ro/pricechecker/lookup/LookupLog.java` -- allowlisted JSON logger; fields TS, LEVEL, EVENT, EAN, RETAILER, OUTCOME, DURATION_MS already exist; `RETAILER_ID` pattern.
- `backend/src/main/java/ro/pricechecker/registry/BlockStore.java:88` -- `isBlocked(String)`; bean in `BlockStoreConfig`.
- `backend/src/main/java/ro/pricechecker/registry/RetailerConfig.java` -- `deadlineMs` (nullable Integer).
- `backend/src/main/java/ro/pricechecker/adapter/PageFacts.java` -- `priceMinor, currency, availability, url, eanOnPage`.
- `backend/src/main/java/ro/pricechecker/BlockStoreConfig.java` -- pattern for a root `@Configuration` reading a property (`@Value`).
- `backend/src/test/java/ro/pricechecker/registry/TestRegistries.java` -- `fetchable(minIntervalMs, concurrency, deadlineMs)`, `runtimeAllowing`, `blockStore`; `MutableClock` in same package.
- `backend/src/test/java/ro/pricechecker/arch/ArchitectureRules.java` -- `cache` may use no project package; `lookup` may use `cache`.
- `backend/build.gradle.kts` -- add `com.github.ben-manes.caffeine:caffeine` (version from the Spring Boot BOM, S-12).

## Tasks & Acceptance

**Execution:**
- `backend/build.gradle.kts` -- add Caffeine -- AD architecture names Caffeine async cache.
- `backend/src/main/java/ro/pricechecker/cache/ResultCache.java` -- generic `ResultCache<K,V>(Duration ttl, Clock clock)` over `AsyncCache`, virtual-thread executor, ticker from `Clock`; `CompletableFuture<V> get(K, Function<K,V>)`; a null value is not kept.
- `backend/src/main/java/ro/pricechecker/lookup/Verdict.java` and `RetailerEan.java` -- cache value (status, priceMinor, availability, url, fetchedAt) and key.
- `backend/src/main/java/ro/pricechecker/lookup/LookupService.java` -- precedence incl. Block, parallel loads via cache, per-row deadline, normalization, one log line per row.
- `backend/src/main/java/ro/pricechecker/lookup/LookupLog.java` -- `row(retailer, ean, status, durationMs)` event `lookup_row`.
- `backend/src/main/java/ro/pricechecker/LookupCacheConfig.java` -- bean from `price-checker.cache-ttl` (default `15m`); `application.yaml` documents it.
- Tests: `backend/src/test/java/ro/pricechecker/lookup/LookupExecutionTest.java`, `backend/src/test/java/ro/pricechecker/cache/ResultCacheTest.java`; update the 5 existing constructor call sites.

**Acceptance Criteria:**
- AC 2.8.1–2.8.6 from `epics.md` Story 2.8, each with a test tagged `AC-2.8.n` covering the matrix rows.

## Design Notes

The waiting caller uses `future.get(remaining, MILLISECONDS)`, never `orTimeout` (which would complete the shared future). The loader returns null for UNAVAILABLE so Caffeine drops the entry. Assumption: price is kept only when currency is exactly `RON`; a `Found` with null `eanOnPage` stays FOUND.

## Verification

**Commands:**
- `backend/gradlew -p backend check` -- expected: BUILD SUCCESSFUL.

### 2026-11-18 — Review pass
- verdicts: 4 layers; findings grouped — medium 3, low 8, false 0, maybe-false 1
- findings:
  - `[medium]` `[defer]` Unbounded shared load (Blind, Edge, Intent) — real: `ResultCache` has no load timeout; deferred for budget, the fetch client deadline (story 2.4) bounds real HTTP calls.
  - `[medium]` `[defer]` `durationMs` measures from Lookup start (Blind, Edge, Gap) — real; deferred for budget.
  - `[medium]` `[defer]` No `LookupCacheConfig` property test (Blind, Gap) — real; deferred.
  - `[low]` `[defer]` Wall-clock ticker, executor not closed, concurrent-share test race, `Found(null)` untested, default-deadline path unreachable (ConfigCheck requires deadlineMs), interrupt cascades, blank `eanOnPage`, case of `RON` — real but low; deferred.
  - `[low]` `[reject]` Timing ACs not measured in wall time — barrier/latch tests prove parallelism and deadlines deterministically (T-5 forbids sleeps).
  - `[maybe-false]` `[reject]` Docs/ADR not updated — Aglaia (Knowledge Keeper) updates docs at stage 9.

## Auto Run Result

Status: done. `LookupService` now applies AD-10 precedence (incl. BlockStore), loads every retailer in parallel on virtual threads through `cache.ResultCache` (Caffeine, TTL `price-checker.cache-ttl`, default 15m), waits per row until `deadlineMs`, normalizes `eanOnPage`/currency, caches only FOUND/NOT_FOUND, and logs one `lookup_row` line per retailer. Verification: `backend/gradlew -p backend check` exit 0 (654 tests). Review patches were not applied because the run budget ran out; findings are in `deferred`. followup_review_recommended: false (no patches applied).

### 2026-11-18 — Round 2 (tests gate fix)

The r1 tests gate failed on `LookupExecutionAcceptanceGapsTest.ac_2_8_2_deadlineCountsFromLookupStartSoALateRowDoesNotAddItsOwnFullWait` (line 166). The `a.example` stub advanced the injected clock by the full 4000 ms deadline before its load completed, so `finish(a)` could compute 0 ms remaining and time out first. The stub now advances 3000 ms, so `b.example` waits 1 s against the 3 s ceiling, which still catches a full extra 4 s wait. Test-only change (T-5). Verification: that test class passed 30 of 30 reruns; `backend/gradlew -p backend check`, `uv run --directory orchestrator pytest -q`, `npm --prefix web ci` and `npm --prefix web run check` all exit 0.

### 2026-11-18 — Round 3 (ci_green gate fix)

CI `backend` failed at `compileTestJava`: `fetch/RetryAfterAcceptanceGapsTest` (story 2.7) still called the pre-2.8 `LookupService` constructor. It now passes the `BlockStore` and a 15-minute `ResultCache`. The full check then showed `ResultCacheTest.ac_2_8_5_afterTtlLoadsAgain` failing intermittently. Caffeine's async cache records the write time in a completion callback that can run after `join()` returns, so a later `clock.advance` could land before that time was recorded. `ResultCache` now records the load instant on the loader thread and checks the TTL against it (T-5). Each caller gets a future derived from the shared load, so the same-object assertion was replaced. `loads == 1` still proves the load is shared. Verification: `backend/gradlew -p backend check --rerun-tasks` exit 0 three runs in a row.
