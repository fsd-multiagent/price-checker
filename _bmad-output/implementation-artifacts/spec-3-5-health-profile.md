---
title: 'Backend health profile'
type: 'feature'
created: '2026-11-18'
status: 'in-review'
baseline_revision: 'c2c066f83ede02247b8dbe618d437189b50dbf5a'
review_loop_iteration: 0
followup_review_recommended: false
context: []
warnings: ['oversized']
deferred: []
---

<intent-contract>

## Intent

**Problem:** Nothing checks that every Known EAN still extracts (FR-8, UJ-4); a broken Adapter is first seen by the audience. Story 3.6 needs a backend mode that runs that check on a GitHub runner and leaves a report and `blocks.json` behind.

**Approach:** A Spring profile `health`: no web server; a runner in `ro.pricechecker.health` loads every Known EAN of every Approved retailer once, without the cache, through the same adapter call and normalization as a Lookup and the same fetch client (paced: rate interval doubled, concurrency 1), then writes `report.json` plus the `PageStripper`-stripped page of each failure and exits non-zero on any failure.

## Boundaries & Constraints

**Always:** `health` uses only `lookup`, `registry`, `fetch` (ADR 0006 arrows). Every request goes through `FetchClient` (C-12), so robots.txt, Approved/enabled/`fetching: allowed`, user agent, Blocks and `notBefore` apply unchanged. The `blocks.json` path must not exist at start (fresh); the run creates it only through `BlockStore`. Saved pages pass `PageStripper.strip` (S-7, T-2). Logs through `LookupLog` only; never log the page.

**Never:** no change to Lookup behaviour or the web profile's limits; no cache for health loads; no deleting an existing `blocks.json`; no GitHub workflow (story 3.6); no retailer domain literal in main code.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|---|---|---|---|
| All good | stub pages FOUND with RON price, IN_STOCK/OUT_OF_STOCK, url | every check `PASSED`, exit 0 | — |
| Missing fact | page without price (or availability UNKNOWN) | check `FAILED`, `missing: [price]`, page saved stripped, exit 1 | — |
| Not FOUND / unavailable | 404, timeout, unparseable | `FAILED`, status recorded, missing all facts | exit 1 |
| robots disallows adapter path | robots.txt `Disallow: /p/` | Block recorded in `blocks.json`; `FAILED`, `blocked: true`; later EANs of that retailer fail blocked, nothing sent | exit 1 |
| Retailer stopped / adapter disabled | not `fetching: allowed` in runtime.yaml | `SKIPPED`, nothing sent | exit unaffected |
| Production `notBefore` in future | copied `notBefore` in runtime.yaml | `SKIPPED` (reason `notBefore`), nothing sent | exit unaffected |
| runtime.yaml missing/unparseable | rejection on refresh | run fails, report `runtimeConfig` rejection | exit 1 |
| blocks.json exists at start | file present | application refuses to start | non-zero exit |

</intent-contract>

## Code Map

- `backend/src/main/java/ro/pricechecker/PriceCheckerApplication.java` -- `main`; must `System.exit(SpringApplication.exit(ctx))` under profile `health` so `ExitCodeGenerator` decides the exit code.
- `backend/src/main/java/ro/pricechecker/FetchClientConfig.java` -- builds `FetchClient`; reads new `price-checker.fetch.interval-factor` (default 1) and `max-concurrency` (default unlimited).
- `backend/src/main/java/ro/pricechecker/fetch/FetchClient.java:73-120` -- limiters built from `RetailerConfig.minIntervalMs/concurrency`; add a `Pace(intervalFactor, maxConcurrency)` applied there.
- `backend/src/main/java/ro/pricechecker/lookup/LookupService.java:149-174` -- `load` + `found` = AD-10 normalization; extract static `normalize(AdapterOutcome, Ean, URI, Instant)` and reuse.
- `backend/src/main/java/ro/pricechecker/lookup/AdapterCatalog.java` -- `find` is package-private; the new lookup entry point uses it.
- `backend/src/main/java/ro/pricechecker/registry/{Registry,RuntimeSwitch,BlockStore}.java` -- Approved set, `knownEans()`, `refresh()/isAllowed()`, `isBlocked()/notBefore()`.
- `backend/src/main/java/ro/pricechecker/fetch/PageStripper.java` -- `strip(String)` for saved pages.
- `backend/src/test/java/ro/pricechecker/arch/ArchitectureRules.java:35` -- `health` may use `lookup`, `registry`, `fetch` only (not `adapter`, `ean`).
- `backend/src/test/java/ro/pricechecker/registry/TestRegistries.java` -- `twoEnabled`, `runtimeAllowing`, `of(...)` for test registries; `MutableClock`.

## Tasks & Acceptance

**Execution:**
- `backend/src/main/java/ro/pricechecker/fetch/FetchClient.java` -- add public record `Pace(int intervalFactor, int maxConcurrency)` with `NORMAL`; new public constructor overload taking it; limiter interval × factor, concurrency = min(config, max) -- AC 3.5.1 pacing inside the one fetch client.
- `backend/src/main/java/ro/pricechecker/lookup/PageSource.java`, `KnownEanLoad.java`, `LookupService.java` -- `PageSource { String get(URI) throws IOException }`; `KnownEanLoad.load(retailerId, ean, url, PageSource)` → `LookupRow` (UNAVAILABLE when no verdict) using the shared `normalize`, no cache -- same core as a Lookup without exposing `adapter` to `health`.
- `backend/src/main/java/ro/pricechecker/health/{HealthCheck,CheckResult,HealthReport}.java` -- run all targets in `retailers.yaml` × `known-eans.yaml` order sequentially; decide SKIPPED/FAILED/PASSED; record last page body per check; write report and stripped pages; `ExitCodeGenerator` + `CommandLineRunner`.
- `backend/src/main/java/ro/pricechecker/HealthConfig.java` + `backend/src/main/resources/application-health.yaml` -- `@Profile("health")` wiring: report dir property `price-checker.health.report-dir`, fresh-`blocks.json` check; profile yaml sets `web-application-type: none`, interval factor 2, max concurrency 1, runner-relative default files.
- tests: `health/HealthCheckTest`, `health/HealthProfileTest`, `fetch/FetchPaceTest`, `lookup/KnownEanLoadTest` -- names carry AC ids 3.5.1–3.5.3 (T-1).

**Acceptance Criteria:**
- Given the `health` profile, when the context starts, then there is no web server, the fetch limits are interval ×2 and concurrency 1, and an existing `blocks.json` stops startup (3.5.1).
- Given a stub transport, when a retailer's request interval is 10 ms under `Pace(2,1)`, then two sends are ≥ 20 ms apart and never concurrent (3.5.1).
- Given stub retailers serving good pages and allow-all robots, when the run ends, then every Known EAN is PASSED and the exit code is 0; the same EAN checked twice sends twice (no cache) (3.5.2).
- Given any failing check, when the run ends, then exit code is 1 and `report.json` names retailer, EAN, status, missing facts and `blocked`, with the stripped page saved for it (3.5.3).

## Design Notes

Report (`<report-dir>/report.json`): `{"passed":bool,"runtimeConfig":null|"MISSING"…,"checks":[{"retailer","ean","outcome":"PASSED|FAILED|SKIPPED","reason"?,"status","missing":[...],"blocked":bool,"page":"pages/<retailer>/<ean>.html"?}]}`. Missing facts: `price` (null), `availability` (null/UNKNOWN), `url` (null); non-FOUND rows miss all three.

## Verification

**Commands:**
- `backend/gradlew -p backend check` -- expected: BUILD SUCCESSFUL (Spotless, ArchUnit, text scan, tests).

## Auto Run Result

Status: in-review (implementation done; `backend/gradlew -p backend check` passes; every I/O matrix row has a passing `ac_3_5_*` test).
Step-04 self-review was not run in this dev step because of the run's USD budget; the stage-7 review by Filofteia (Code Reviewer) and Veronel (Security & Compliance) covers it.
Points for the reviewers: the change goes beyond `health/` (a `FetchClient.Pace` overload, `lookup.KnownEanLoad`/`PageSource`, a `LookupLog.healthCheck` event, and a widened `LookupLogAcceptanceGapsTest` that admits an `Enum<?>` outcome); under the profile the web beans are still created but unused; default `health/` paths are relative to the working directory, so story 3.6 should set them.
