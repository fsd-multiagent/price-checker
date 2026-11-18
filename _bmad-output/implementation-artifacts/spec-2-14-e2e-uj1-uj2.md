---
title: 'End-to-end test of UJ-1 and UJ-2 against stubbed retailers'
type: 'feature'
created: '2026-11-18'
status: 'done'
baseline_revision: 'c2c066f83ede02247b8dbe618d437189b50dbf5a'
review_loop_iteration: 0
followup_review_recommended: false
context:
  - '{project-root}/web/CLAUDE.md'
warnings: []
deferred: []
---

<intent-contract>

## Intent

**Problem:** Nothing proves the two demo journeys (UJ-1 compare prices, UJ-2 one retailer fails) work through the real browser, web app and backend together, so the presenter cannot trust the demo (NFR-6, T-4).

**Approach:** A test-only backend entry point (`backend/src/test`) starts the real Spring app with a test register approving two retailers and a stub `RetailerHttp` that serves fixture pages and never touches the network. A Playwright package in `web/e2e/` starts that backend and the Vite dev server (proxying `/api`), drives the page, and a new CI job runs it.

## Boundaries & Constraints

**Always:** Real `LookupService`, cache, adapters and web app run unchanged; only retailer HTTP is stubbed. The stub answers only URLs from the e2e known-EANs file and throws for any other. Each test uses its own EAN so tests are order-independent (T-5); no `sleep`/`waitForTimeout`. Test names carry the AC id (T-1). Pinned exact dependency versions and a committed lockfile (S-12).

**Never:** No main-code change, no change to `web/package.json`/`vite.config.ts`, no real retailer request (T-3), no edit of `docs/source-register.md`. The e2e config classes carry no `@Configuration`/`@Component` annotation so other `@SpringBootTest`s never pick them up.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| UJ-1 | EAN `5940000000011` + trailing spaces, Enter | 2 FOUND rows; cheaper has "Lowest price"; "Found prices at 2 of 2 retailers." | none |
| UJ-2 | EAN `5940000000028`; evomag stub hangs past its 1500 ms deadline | evomag row "Unavailable", cel.ro row Lowest; "Found prices at 1 of 2 retailers." | Lookup stays 200 |
| Cache | EAN `5940000000035` twice | same Fetched text; stub page-request count unchanged after 2nd Lookup | none |

</intent-contract>

## Code Map

- `backend/src/main/java/ro/pricechecker/lookup/LookupService.java:58` -- takes `Optional<RetailerHttp>`; no main bean provides one yet, so an e2e `RetailerHttp` bean is used for every adapter.
- `backend/src/main/java/ro/pricechecker/registry/Registry.java` -- `@Component` reading bundled config; override with a `@Primary` bean from `TestRegistries.of(register, retailers, knownEans)` (`backend/src/test/java/ro/pricechecker/registry/TestRegistries.java:20`).
- `backend/src/main/java/ro/pricechecker/lookup/AdapterCatalog.java` -- ids must match adapter beans: real `CelroAdapter` (`cel.ro`) and `EvomagAdapter` (`evomag.ro`), so the e2e retailers use those two ids.
- `backend/src/main/java/ro/pricechecker/RuntimeSwitchConfig.java`, `BlockStoreConfig.java` -- properties `price-checker.runtime-file`/`blocks-file`; e2e server points them at a temp dir with `fetching: allowed` for both.
- `backend/src/test/java/ro/pricechecker/lookup/StubAdapters.java` -- scanned in; registers nothing because both bundled ids have real adapters.
- `web/src/EanForm.tsx`, `ResultsTable.tsx`, `StatusLine.tsx` -- label "EAN (8 or 13 digits)", region "Price results", `.badge-lowest`, `<time>` in Fetched cell, status copy.
- Spring Boot Gradle plugin `bootTestRun` -- runs the single `main` in test sources on the test classpath; no `build.gradle.kts` change.

## Tasks & Acceptance

**Execution:**
- `backend/src/test/resources/e2e/{source-register.md,retailers.yaml,known-eans.yaml}` -- test register approving `cel.ro`/`evomag.ro`, both `adapter: enabled`, `deadlineMs: 1500`; three EANs each with URLs `https://<id>/e2e/<ean>`.
- `backend/src/test/resources/e2e/pages/<id>/<ean>.html` -- JSON-LD Product pages (gtin13, RON price, InStock); evomag has no page for the UJ-2 EAN (hangs).
- `backend/src/test/java/ro/pricechecker/e2e/E2eServer.java` -- `main`: temp runtime/blocks files, port from `E2E_BACKEND_PORT` (default 18080), `SpringApplication.from(PriceCheckerApplication::main).with(E2eBeans.class)`.
- `backend/src/test/java/ro/pricechecker/e2e/E2eBeans.java` -- unannotated bean source: `@Primary Registry`, `RetailerHttp` = `FixtureHttp`, `RouterFunction` `GET /e2e/requests?ean=` returning the stub's page-request count.
- `backend/src/test/java/ro/pricechecker/e2e/FixtureHttp.java` (+ `FixtureHttpTest`) -- serves pages, counts requests per EAN, hangs (latch, bounded) for URLs with no page, throws for unknown URLs.
- `web/e2e/{package.json,package-lock.json,playwright.config.ts,vite.config.ts,journeys.spec.ts,README.md}` -- Playwright 1.65.1 pinned; webServer starts backend (`bootTestRun`) and Vite (`/api` proxy); outputs under `node_modules/.e2e`.
- `.github/workflows/ci.yml` -- e2e steps appended to the `backend` job (it already has Java 21): setup-node, `npm ci` both packages, `playwright install --with-deps chromium`, run. A new job would change the `ci` needs list that the protected `orchestrator/tests/test_ci_story_1_9.py` pins.

**Acceptance Criteria:**
- Given the e2e stack, when Playwright types `5940000000011  ` and presses Enter, then both rows show and the cheaper one carries "Lowest price" (2.14.1).
- Given the evomag stub hangs, when `5940000000028` is looked up, then evomag reads "Unavailable", cel.ro is Lowest and the status line reads "Found prices at 1 of 2 retailers." (2.14.2).
- Given `5940000000035` looked up twice, when the table returns, then the Fetched text is unchanged and `/e2e/requests` reports no new request (2.14.3).
- Given CI, when the e2e steps of the `backend` job run, then it passes with only the fixture stub as retailer HTTP (2.14.4).

## Spec Change Log

## Review Triage Log

## Design Notes

Real domains appear only as ids in the test register because the real adapters' ids are fixed; no request can leave the stub. The UJ-2 "timeout" is the LookupService deadline (1500 ms), the real path for a slow retailer; the stub releases its thread after 10 s.

## Verification

**Commands:**
- `backend/gradlew -p backend check` -- passes (FixtureHttpTest included)
- `npm --prefix web/e2e ci && npm --prefix web/e2e test` -- 3 tests pass
- `npm --prefix web run check` -- still passes (e2e files lint/format clean)

## Auto Run Result

**Summary:** Playwright e2e suite for UJ-1/UJ-2 against a test-only backend (`bootTestRun` main `E2eServer`) whose only retailer HTTP is the `FixtureHttp` stub; CI runs it in the `backend` job.

**Files:**
- `backend/src/test/java/ro/pricechecker/e2e/{E2eServer,E2eBeans,FixtureHttp,FixtureHttpTest}.java` -- e2e entry point, unannotated bean source (`@Primary` test Registry, stub, `GET /e2e/requests`), the stub and its unit tests.
- `backend/src/test/resources/e2e/**` -- test register, retailers, known EANs, five JSON-LD pages.
- `web/e2e/{package.json,package-lock.json,playwright.config.ts,vite.config.ts,journeys.spec.ts,README.md}` -- Playwright 1.65.1 package and the three AC tests.
- `.github/workflows/ci.yml` -- e2e steps in the `backend` job (timeout 20 -> 35 min).

**Review (Blind Hunter, Intent Alignment; Edge Case Hunter and Verification Gap skipped for the run's USD budget):**
- Patched (medium 2, low 2): accidental `vulnerability-scan` timeout change reverted; stub hang raised 10 s -> 60 s so only the 1500 ms Lookup deadline can pass UJ-2; UJ-1 asserts no row is Unavailable; Playwright readiness URL is the e2e-only `/e2e/requests` so a dev backend is never reused.
- Deferred: e2e bypasses `FetchClient` (main wires no `RetailerHttp` yet, story 2.8 deferral; swap the stub to a `Transport` stub when it is wired); no Playwright trace upload in CI; e2e TS not type-checked; suite not in the orchestrator `tests` gate (needs an owner `sdlc-commands` change); `new URL().pathname` paths; temp dir not deleted.
- Rejected: real ids `cel.ro`/`evomag.ro` in test data (the real adapters' ids are fixed and `AdapterCatalog` requires a match; the stub refuses every URL outside the e2e known-EANs file, so nothing is sent); lockfile "missing" (it was excluded from the review diff only, and is committed); Approach line still says "new CI job" (intent-contract is read-only; a new job would break the protected `test_ci_story_1_9.py` needs check).

**Follow-up review recommended:** false (no high patched; medium patches verified by re-running the suite).

**Verification:** `backend/gradlew -p backend check` exit 0; `CI=1 npm --prefix web/e2e test` 3 passed; `npm --prefix web run check` exit 0; `uv run pytest -q tests/test_ci_story_1_9.py` 38 passed.

**Residual risks:** the CI steps have not run on GitHub yet (`playwright install --with-deps` on ubuntu); the `ci.yml` change makes the PR owner-review (review path).
