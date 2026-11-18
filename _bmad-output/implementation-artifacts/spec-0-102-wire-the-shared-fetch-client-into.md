---
title: 'Wire the shared fetch client into Lookups (issue #102)'
type: 'bugfix'
created: '2026-11-18'
status: 'done'
baseline_revision: 'f51e21b51cdf4fa6ee8327f02d70cd43497fd731'
review_loop_iteration: 0
followup_review_recommended: false
context:
  - '{project-root}/docs/standards/coding.md'
  - '{project-root}/docs/standards/testing.md'
warnings: []
deferred:
  - summary: >-
      No Spring-context test shows the real LookupService bean receives a present binder, or that a context without one starts (AC-4 at context level).
    evidence: |-
      LookupFetchWiringTest builds LookupService by hand; FetchClientConfigTest checks the bean alone.
    location: >-
      backend/src/test/java/ro/pricechecker/lookup/LookupFetchWiringTest.java
    severity: medium
  - summary: >-
      AC-3 no-adapter row has no test; E2eJourneysApiTest does not assert the injected binder returns FixtureHttp.
    evidence: |-
      AdapterCatalog requires one-to-one ids, so a no-adapter row needs a hand-built registry; the e2e @Primary binder is only proven indirectly by FOUND rows.
    severity: low
  - summary: >-
      AGENTS.md story 2.8 hung-load deferral and older story specs (1-6, 2-14) still describe the pre-#102 state.
    evidence: |-
      Agent-context and history files; route to Aglaia (Knowledge Keeper).
    severity: low
---

<intent-contract>

## Intent

**Problem:** `LookupService` takes an `Optional<RetailerHttp>` that no main bean provides, so every enabled, allowed, unblocked retailer with a known EAN is `UNAVAILABLE` in 0 ms. AD-6 binds the shared fetch client per retailer (`FetchClient.bind(retailerId)`), so one shared `RetailerHttp` cannot fit.

**Approach:** Add a port `adapter.RetailerHttpBinder` (`RetailerHttp bind(String retailerId)`); `FetchClientConfig` provides it as `fetchClient::bind`; `LookupService` takes `Optional<RetailerHttpBinder>` and binds one client per row it loads, after every existing AD-10 check.

## Boundaries & Constraints

**Always:** `lookup` depends only on `ean`, `cache`, `registry`, `adapter` (ArchUnit, ADR 0006). Check order unchanged: disabled/stopped → DISABLED; Block → UNAVAILABLE; unknown EAN → NOT_FOUND; no adapter or no binder → UNAVAILABLE; only then bind and load. A `bind` exception stays in its row (AD-5). Tests use fake transports or fake `RetailerHttp` and `*.example` domains only (T-2, T-3).

**Never:** no change to `fetch` checks, to the cache key, or to `KnownEanLoad`/health. No real network in any test. The e2e context must keep `FixtureHttp` as its only retailer HTTP.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Loaded through FetchClient | a.example Approved, enabled, allowed, known EAN; fake transport serves page | Row FOUND with price; transport saw the known URL | none |
| Two retailers | a.example and b.example both loadable | binder called with `a.example` and `b.example`; each adapter gets its own bound client | none |
| Decided rows | DISABLED, blocked, NOT_FOUND, no adapter | binder never called | none |
| No binder | `Optional.empty()` | row UNAVAILABLE | none |

</intent-contract>

## Code Map

- `backend/src/main/java/ro/pricechecker/lookup/LookupService.java:51,61,108-115` -- holds `Optional<RetailerHttp> http`; `start` returns UNAVAILABLE when empty. Change to binder and call `bind(id)` just before `cache.get`, inside the loader key lambda's caller.
- `backend/src/main/java/ro/pricechecker/adapter/RetailerHttp.java` -- existing port; new `RetailerHttpBinder` sits beside it.
- `backend/src/main/java/ro/pricechecker/FetchClientConfig.java` -- add `@Bean RetailerHttpBinder retailerHttpBinder(FetchClient)`; root package may depend on all.
- `backend/src/main/java/ro/pricechecker/fetch/FetchClient.java:91,181` -- public constructor with `Transport`; `bind` returns `BoundHttp implements RetailerHttp`. robots.txt 404 → allow all.
- `backend/src/test/java/ro/pricechecker/arch/ArchitectureRules.java:30` -- allowed deps for `lookup`.
- Test callers of `new LookupService(..., Optional.of(http), ...)`: `lookup/*Test.java` (6), `fetch/RetryAfterAcceptanceGapsTest.java:217`, `api/LookupSkeletonHttpTest.java:56`, `retailer/altexro/AltexroGateTest.java:91` -- change to `Optional.of(id -> http)`.
- `backend/src/test/java/ro/pricechecker/e2e/E2eBeans.java` -- must add a `@Primary RetailerHttpBinder` returning `FixtureHttp` for every id, or the e2e server would fetch through `JdkTransport`; `E2eJourneysApiTest:140` keeps checking `FixtureHttp` is the only `RetailerHttp` bean.
- `AGENTS.md:101`, `web/e2e/README.md:23` -- text describing the old optional bean.

## Tasks & Acceptance

**Execution:**
- `backend/src/main/java/ro/pricechecker/adapter/RetailerHttpBinder.java` -- new functional interface -- per-retailer port (AD-6).
- `backend/src/main/java/ro/pricechecker/lookup/LookupService.java` -- take `Optional<RetailerHttpBinder>`, bind per row after all checks -- AC-1..4.
- `backend/src/main/java/ro/pricechecker/FetchClientConfig.java` -- provide binder bean -- AC-1.
- `backend/src/test/java/ro/pricechecker/lookup/LookupFetchWiringTest.java` -- new tests tagged `AC-0.102.1`..`AC-0.102.4` -- real `FetchClient` with fake `Transport`; recording binder.
- test callers listed above, `E2eBeans` -- adapt to the new type.
- `AGENTS.md`, `web/e2e/README.md` -- update the pitfall text -- AC-5.

**Acceptance Criteria:**
- Given a.example Approved, enabled, allowed, unblocked with a known EAN and a `FetchClient` over a fake transport, when a Lookup runs, then the row is FOUND with the adapter's price and the transport received the known URL.
- Given two loadable retailers, when a Lookup runs, then the binder is called once with each id and each adapter receives the client bound to its own id.
- Given DISABLED, blocked, NOT_FOUND and no-adapter rows, when a Lookup runs, then the binder is never called.
- Given no binder, when a Lookup runs, then loadable rows are UNAVAILABLE; the Spring context still starts.
- Given the change, when `backend/gradlew -p backend check` runs, then ArchUnit passes and AGENTS.md no longer says Lookups lack a `RetailerHttp`.

## Spec Change Log

## Review Triage Log

## Verification

**Commands:**
- `backend/gradlew -p backend check` -- expected: BUILD SUCCESSFUL

### 2026-11-19 — Review pass
- verdicts: 18 findings — high 0, medium 3, low 9, false 4, maybe-false 2
- findings:
  - `medium` `patch` [blind] bind runs before cache.get, so cache hits bind and a bind exception hides a cached verdict — moved `binder.bind(id)` into the loader lambda; check green.
  - `low` `patch` [blind] AGENTS.md/Javadoc say bind happens only on load — fixed by the same move.
  - `medium` `defer` [blind] no Spring-context test of the LookupService bean's binder — deferred.
  - `low` `defer` [blind] e2e test does not assert the injected binder is FixtureHttp — deferred.
  - `low` `defer` [blind] AC-3 no-adapter and AC-4 context-start untested; empty-map assertion weak — deferred.
  - `low` `reject` [blind] AC tag on FetchClientConfigTest — cosmetic traceability.
  - `false` `reject` [blind] bindCalls order dependence — rows start in registry order deterministically.
  - `false` `reject` [blind] no compliance note — fetch rules (S-*) unchanged and enforced in FetchClient; owner approved retailers in #100.
  - `low` `defer` [blind] AGENTS.md 2.8 hung-load deferral wording — agent-context file.
  - `low` `reject` [blind] test field init / fake adapter maps IOException to NotFound — test-only, no wrong verdict in current tests.
  - `medium` `patch` [edge] cache-hit bind — same as first row.
  - `false` `reject` [edge] binder returns null — FetchClient.bind never returns null; a null would fail inside the load and stay UNAVAILABLE.
  - `false` `reject` [edge] FetchClientConfigTest get(0) on empty registry — the test registry is fixed and non-empty.
  - `low` `defer` [edge] no-adapter test missing — deferred.
  - `low` `patch` [edge] docs claim vs bind order — fixed by the move.
  - `maybe-false` `defer` [edge] AC-4 context-start unchecked — would need a context without FetchClientConfig.
  - `maybe-false` `defer` [edge] e2e binder unasserted — deferred.
  - `low` `defer` [intent] edits outside declared touches (e2e, api, fetch, altexro tests, web/e2e/README.md) — forced by the constructor change and T-3.
  - verification-gap layer: no gaps.

## Auto Run Result

- Summary: Lookups now load through the shared FetchClient, bound per retailer via the new `adapter.RetailerHttpBinder` port (`FetchClientConfig` bean `fetchClient::bind`); binding happens inside the cache loader, after every AD-10 check.
- Files: `adapter/RetailerHttpBinder.java` (new port); `lookup/LookupService.java` (binder per row); `FetchClientConfig.java` (bean); `lookup/LookupFetchWiringTest.java` (AC-0.102.1–4 over fake Transport); `FetchClientConfigTest.java`; 9 test callers to `Optional.of(id -> http)`; `e2e/E2eBeans.java` (`@Primary` FixtureHttp binder, T-3); `AGENTS.md`, `web/e2e/README.md`.
- Review: 2 patched (1 medium, 1 low, same root cause); 6 deferred; rest rejected with reasons above.
- Follow-up review recommended: false (one medium patched).
- Verification: `backend/gradlew -p backend check` green before and after the patch; LookupFetchWiringTest ran 6 tests, 0 failures.
- Residual risks: real fetching is now live for any Approved, enabled, `fetching: allowed` retailer; AGENTS.md edit may be reverted on story branches by the orchestrator.
