---
title: 'Simulated layout change fails tests and the Health check'
type: 'feature'
created: '2026-11-18'
status: 'in-review'
baseline_revision: 'a15d4e0aa8629fb22fa0ce02b97711f8a7a9aa03'
review_loop_iteration: 0
followup_review_recommended: false
context: []
warnings: []
deferred: []
---

<intent-contract>

## Intent

**Problem:** The presenter has no saved page that shows what a retailer layout change looks like, so the start of the self-healing story (UJ-4, SM-3, AC-8.4) cannot be demonstrated: neither the adapter tests nor the Health check have been shown to catch it.

**Approach:** Add one hand-written `layout-changed.html` per real Adapter (cel.ro, evomag.ro) whose structured data and selectors no longer carry the price. Test-only code checks an Adapter's facts and fails with `<retailer>: missing <fact>`; tests expect that failure for the altered page and its absence for a normal page. A second test runs the real `HealthCheck` with the real Adapters over a stub transport serving the altered page and expects exit 1 and a report naming retailer, EAN and `price`.

## Boundaries & Constraints

**Always:** Only `fixtures/` and `backend/src/test/` change. Fixtures are synthetic, hold facts only (S-7, T-2), and nothing contacts a retailer (T-3): the Health check uses a stub `Transport`. Test names and tags carry `AC-4.2.x` (T-1). Deterministic: `MutableClock`, clock-advancing sleeper (T-5). The altered page is read only by tests that assert the failure, so CI stays green (4.2.3).

**Never:** No main-code change, no change to `config/` or `docs/source-register.md` (the Approved cel.ro/evomag.ro register in the health test is an in-memory test copy). No altered fixture added to the existing adapter tests' normal fixture lists.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|---|---|---|---|
| Normal page | `product-jsonld.html` of each Adapter | fact check passes | — |
| Altered page, adapter test | `layout-changed.html` of each Adapter | `AssertionError` message `cel.ro: missing price` / `evomag.ro: missing price` | test asserts the error |
| Altered page, Health check | stub serves altered page for the fixture EAN, normal pages for the rest | exit 1; that check FAILED, `missing: [price]`, status FOUND, retailer and EAN in `report.json`; other checks PASSED | — |
| Normal pages, Health check | stub serves only normal pages | exit 0, every check PASSED | — |

</intent-contract>

## Code Map

- `backend/src/main/java/ro/pricechecker/retailer/celro/CelroAdapter.java:66-94` -- field-by-field merge of JSON-LD, meta, selectors `#product-price`, `.stoc_produs`, `.product-ean`; null price with other facts gives `Found` with null price.
- `backend/src/main/java/ro/pricechecker/retailer/evomagro/EvomagAdapter.java` / `EvomagPage.extract` -- whole-step: no step with a price → first step with any fact, null price.
- `backend/src/main/java/ro/pricechecker/health/HealthCheck.java:54-71,163-178` -- public constructor; `missing(LookupRow)` lists `price`, `availability`, `url`; `getExitCode()`, `report()`, writes `report.json`.
- `backend/src/test/java/ro/pricechecker/health/HealthCheckTest.java:93-136` -- pattern to build `HealthCheck` with stub `Transport`, `FetchClient.Pace(2,1)`, `MutableClock`, `KnownEanLoad`, `AdapterCatalog`.
- `backend/src/test/java/ro/pricechecker/registry/TestRegistries.java:20` -- `of(register, retailersYaml, knownEansYaml)`; register markdown format in `SourceRegisterTest.register/row` (package-private, so inline the table).
- `registry/ConfigCheck.java:141-145` -- known-EAN URL host must be the domain or `www.` + domain.
- Normalization: a page `eanOnPage` different from the requested EAN gives `NOT_FOUND`, so the stub replaces the fixture GTIN with the requested EAN for the second Known EAN.
- `fixtures/cel.ro/product-jsonld.html` (GTIN `5941234567899`), `fixtures/evomag.ro/product-jsonld.html` (GTIN `5901234123457`) -- normal baselines.

## Tasks & Acceptance

**Execution:**
- `fixtures/cel.ro/layout-changed.html`, `fixtures/evomag.ro/layout-changed.html` -- synthetic pages: JSON-LD offer price key renamed, microdata `itemprop="price"` and cel.ro `#product-price` renamed; currency, availability and GTIN remain -- the simulated layout change.
- `fixtures/cel.ro/README.md`, `fixtures/evomag.ro/README.md` -- add the file row, say only failure-expecting tests read it.
- `backend/src/test/java/ro/pricechecker/retailer/LayoutChangeTest.java` -- `requireFacts(retailerId, outcome)` (throws `AssertionError` `<retailer>: missing <facts>`), parameterized over both Adapters: normal page passes, altered page throws naming retailer and `price` (4.2.1, 4.2.3).
- `backend/src/test/java/ro/pricechecker/health/LayoutChangeHealthCheckTest.java` -- real Adapters, test registry with both Approved and enabled, stub transport; normal run exit 0, altered run exit 1 with report fields (4.2.2).

**Acceptance Criteria:**
- Given each Adapter's `layout-changed.html`, when the fact check runs on its outcome, then it fails with a message naming the retailer and `price` (4.2.1).
- Given the Health check over a stub serving an altered page, when it runs, then exit code is 1 and `report.json` names the retailer, EAN and `price` (4.2.2).
- Given `backend/gradlew -p backend check`, when it runs, then it is green and only failure-expecting tests extract facts from `layout-changed.html` (4.2.3). Directory scans that only check fixture hygiene (T-2, S-7) may read it too.

## Spec Change Log

## Review Triage Log

- r1 security (AD-18, AD-10): Veronel (Security & Compliance) left no valid verdict. The step failed, not the code, so the diff needs no change for it. The r2 security step should run again.
- r1 code review CR-1 (major, AC-4.2.1): fixed. `LayoutChangeCoverageTest` now requires an altered fixture and a `LayoutChangeTest` row only for enabled-capable Adapters, meaning the retailer is `Proposed` or `Approved` in the source register. Story 4.1's Altex is `Not checked` and cannot be enabled (AC 4.1.3), so 4.1 and 4.2 can merge in either order. When Altex becomes Proposed, that change must add an Altex `layout-changed.html` and a `LayoutChangeTest` row.
- r1 code review CR-2 and code lens CR-1 (minor, AC-4.2.3): kept as is, with the reading made explicit in the AC below. "Used" means facts are extracted from the page. The directory scans in `CelroAdapterCoverageTest` and `EvomagAcceptanceGapsTest` only check page hygiene (T-2, S-7), and the altered page should pass those checks too.
- r1 code review CR-3 (minor, T-5): fixed. The altered-page Health check test is a `@ParameterizedTest` per retailer with its own `@TempDir` and clock. The mutable `reportDir` field is gone.
- r1 code review CR-4 (minor): fixed. The test register row says `Approved 2026-11-01`.
- r1 code lens CR-2 (minor, C-10): kept. Checking currency, availability and EAN as well shows that the altered page lost only the price.

## Design Notes

"The adapter test fails" is shown as a test that asserts the failure message, so the demo can point at the exact message while CI stays green [ASSUMPTION per 4.2.3]. "Health profile pointed at a stub" is `HealthCheck` built as in `HealthCheckTest` (same class the `health` profile runs), not a booted Spring context, because a booted context cannot use the in-memory Approved register.

## Verification

**Commands:**
- `backend/gradlew -p backend check` -- expected: BUILD SUCCESSFUL.

## Auto Run Result

Status: in-review (implementation done; `backend/gradlew -p backend check` passes; `LayoutChangeTest` ran 4 cases and `LayoutChangeHealthCheckTest` ran 3 (the normal run plus one altered run per retailer), and `LayoutChangeCoverageTest` ran 4, all passing. Round 2 fixed the r1 review findings (see Review Triage Log); each I/O matrix row has a passing test).
Step-04 self-review was not run in this dev step because of the run's USD budget. The stage-7 review by Filofteia (Code Reviewer) and Veronel (Security & Compliance) covers it.
Points for the reviewers: "the adapter test fails" is shown by a test that asserts the failure message (`<retailer>: missing price`), so CI stays green (AC 4.2.3 [ASSUMPTION]). The Health check runs as the real `HealthCheck` class with the real Adapters over a stub transport and an in-memory register where both retailers are Approved, not as a booted `health` profile. The existing facts-only fixture scans (`CelroAdapterCoverageTest`, `EvomagAcceptanceGapsTest`) also read `layout-changed.html`; they pass, and they extract nothing from it.
