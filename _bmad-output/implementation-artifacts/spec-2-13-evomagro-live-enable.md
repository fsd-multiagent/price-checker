---
title: 'Record evoMAG fixtures, curate Known EANs and enable the Adapter'
type: 'feature'
created: '2026-11-18'
status: 'done'
baseline_revision: '67a3b08e3cd3644a16f6823fff2f00d3742444be'
review_loop_iteration: 0
followup_review_recommended: false
context:
  - '{project-root}/docs/standards/testing.md'
  - '{project-root}/docs/standards/security-compliance.md'
warnings: []
deferred:
  - summary: 'No LookupService test pairs a leading-zero request EAN with a shorter page GTIN (0740617263442 vs 740617263442)'
    location: backend/src/test/java/ro/pricechecker/lookup/LookupExecutionTest.java
    severity: medium
  - summary: 'EvomagPage gtin12/gtin14/gtin8 alternatives and document-order precedence have no unit test'
    location: backend/src/main/java/ro/pricechecker/retailer/evomagro/EvomagPage.java
    severity: low
  - summary: 'FixtureRecorder.factsOnly has no unit test; it runs only with the env var set'
    location: backend/src/test/java/ro/pricechecker/fixtures/FixtureRecorder.java
    severity: low
  - summary: 'Health profile pace 2 makes the first request per host (robots.txt then page) need two 4 s slots inside one 4 s deadline, so the page gets REFUSED'
    location: backend/src/main/resources/application-health.yaml
    severity: medium
---

<intent-contract>

## Intent

**Problem:** evoMAG is Approved (owner PR #100, 2026-11-18) and its adapter is `enabled` with two curated Known EANs, but its adapter tests still run only on hand-written synthetic pages, and the curated URLs were never checked against the live pages (AC 2.13.1, 2.13.2).

**Approach:** Add an opt-in recorder (an env-gated JUnit test that never runs in CI) that fetches each evoMAG Known-EAN URL through the shared `FetchClient` (robots.txt, rate limit, user agent, Block handling) and saves `PageStripper.strip` output as `fixtures/evomag.ro/real-<ean>.html`. Run it once, commit the stripped pages, and make the adapter tests assert FOUND on them. Correct `config/known-eans.yaml` only if a page does not give FOUND.

## Boundaries & Constraints

**Always:** Contact only `evomag.ro` and only the Known-EAN product URLs (plus robots.txt, which the client loads). Go through `FetchClient`; save only stripped pages (S-7, T-2). Keep the recorder off unless `PRICE_CHECKER_RECORD_FIXTURES=evomag.ro` is set (T-3).

**Never:** Request a search URL (`?q=`, `?sn.q=`). Edit `docs/source-register.md`. Add retailer domain literals to main code. Add `layout-changed.html` to normal fixture lists.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Real page in stock | `real-<ean>.html` via `FixtureHttp` | `Found` with price in RON, availability, the product URL | No error expected |
| Recorder off | env unset | test skipped, nothing sent | n/a |
| Fetch refused / blocked | robots disallow, 403, 429 | recorder fails with the outcome, no file written | Stop; do not retry around it |

</intent-contract>

## Code Map

- `backend/src/main/java/ro/pricechecker/retailer/evomagro/EvomagAdapter.java` -- GETs the curated URL, delegates to `EvomagPage.extract`.
- `backend/src/main/java/ro/pricechecker/retailer/evomagro/EvomagPage.java` -- C-6 steps JSON-LD, microdata (first Product scope incl. nested), meta; may need a fix if real markup differs.
- `backend/src/main/java/ro/pricechecker/fetch/FetchClient.java:103` -- public constructor with `Pace`; `bind(retailerId).fetch(URI)`.
- `backend/src/main/java/ro/pricechecker/fetch/PageStripper.java:54` -- `strip(String)`.
- `backend/src/main/java/ro/pricechecker/FetchClientConfig.java` -- how the app wires `FetchClient` (copy for the recorder).
- `backend/src/main/java/ro/pricechecker/registry/Registry.java:72` -- `knownEans()`; `RuntimeSwitch(Path, Clock)`; `BlockStore(Path, RuntimeSwitch, Clock, Listener)`.
- `backend/src/test/java/ro/pricechecker/retailer/evomagro/Fixtures.java` -- `FixtureHttp` fake.
- `config/known-eans.yaml`, `config/retailers.yaml` -- already curated/enabled by the owner (PR #100).

## Tasks & Acceptance

**Execution:**
- `backend/src/test/java/ro/pricechecker/fixtures/FixtureRecorder.java` -- new env-gated recorder, generic by retailer id (runtime.yaml and blocks.json under a temp dir, `retailers.yaml` pace; outside `retailer/evomagro` because that package's tests must name no network client) -- records real pages through the shared client, then `PageStripper.strip` and a `factsOnly` trim (S-7).
- `backend/src/main/java/ro/pricechecker/retailer/evomagro/EvomagPage.java` -- microdata GTIN selector also reads `gtin`, `gtin12`, `gtin14`, `gtin8` (real pages use `gtin` with a 12-digit UPC-A), so the C-7 GTIN check applies.
- `fixtures/evomag.ro/real-<ean>.html` -- recorded, stripped pages -- AC 2.13.1.
- `backend/src/test/java/ro/pricechecker/retailer/evomagro/EvomagRealPagesTest.java` -- each Known EAN of evoMAG has a real fixture and the adapter returns `Found` with RON price, known availability, and no GTIN mismatch -- AC 2.13.1, 2.13.2.
- `fixtures/evomag.ro/README.md` -- list real pages; synthetic pages stay only for branches real pages cannot show.
- `config/known-eans.yaml` -- fix a URL only if its page is not FOUND (done: the A400 120GB URL redirected to its category page; replaced by A400 480GB, `0740617263442`).

**Acceptance Criteria:**
- Given the recorded real pages, when `backend/gradlew -p backend check` runs, then every evoMAG Known EAN's fixture extracts to `Found` (RON price) and no test contacts the network.
- Given `adapter: enabled` for evoMAG, when the register test runs, then it passes because evoMAG is Approved (already asserted by `EvomagAdapterTest`).

## Spec Change Log

## Review Triage Log

Pass 1: high 0, medium 3, low 4, false/rejected 6, deferred 4.
- medium, patched: recorder overwrote fixtures for a redirected page and half-wrote on a mid-run failure. Now it asserts the final URL equals the curated URL and the page has a Product, and writes only after all fetches.
- medium, patched (doc): the factsOnly Javadoc claimed reviews are dropped, but JSON-LD is copied as stripped. The Javadoc now says so.
- medium, deferred: the Lookup leading-zero direction is untested (see `deferred`).
- low, patched: EvomagRealPagesTest NPE on a null eanOnPage. It now asserts isNotNull first.
- low, deferred: gtin variants untested; factsOnly untested.
- rejected: multi-token itemprop/itemtype, AggregateOffer, blank content, nested-scope GTIN. The two live pages show none of these (verified in the recorded markup).
- rejected: env matches `.+`. FetchClient refuses any non-Approved retailer and robots-disallowed path. Recording cel.ro is story 2.12's call.
- rejected: "replace" read as additive, and fixtures rebuilt by factsOnly. These are recorded design decisions (Design Notes, README). The full stripped pages were about 500 KB and held reviewer names (S-7).
- rejected: AC 2.13.3 needs no new test. `EvomagAdapterTest.ac_2_11_3_retailerIdMatchesConfigAndIsEnabledOnlyBecauseApproved` and ConfigCheck already cover it after owner PR #100.

## Auto Run Result

Status: done. The implementation was done inline rather than by an implementation subagent, because the live recording needed iterative fixes (pace, redirect, GTIN). `backend/gradlew -p backend check` passed.

## Design Notes

Synthetic fixtures are kept for edge branches (no product, unparseable price, mixed steps, layout change) that the two real pages cannot show; the happy-path FOUND assertions move to real pages. This is the simplest reading of "replace" that keeps branch coverage.

## Verification

**Commands:**
- `PRICE_CHECKER_RECORD_FIXTURES=evomag.ro backend/gradlew -p backend test --tests '*FixtureRecorder'` -- run once; writes the real fixtures.
- `backend/gradlew -p backend check` -- expected: BUILD SUCCESSFUL.
