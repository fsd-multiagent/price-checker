---
title: 'Disabled Altex Adapter from synthetic fixtures'
type: 'feature'
created: '2026-11-18'
status: 'done'
baseline_revision: '8a747654277357cf73b147aa18476da8fdb3d242'
review_loop_iteration: 0
followup_review_recommended: false
context:
  - '{project-root}/_bmad-output/implementation-artifacts/epic-4-context.md'
warnings: []
deferred:
  - summary: >-
      No test pins the no-price fallback (first step with any fact wins when several steps disagree).
    evidence: |-
      unparseable-price.html yields facts from JSON-LD only, so dropping the withFacts guard passes all tests.
    location: >-
      backend/src/main/java/ro/pricechecker/retailer/altexro/AltexroAdapter.java:101
    severity: medium
  - summary: >-
      Adapter does not check currency is RON; a EUR page would be Found.
    evidence: |-
      fetchAndExtract compares gtin with ean.value() since round 8, but never checks the currency; not required by 4.1 ACs.
    location: >-
      backend/src/main/java/ro/pricechecker/retailer/altexro/AltexroAdapter.java:85
    severity: medium
  - summary: >-
      StubAdapters maps id to package by removing dots; a hyphenated domain would not match and would get a duplicate stub.
    evidence: |-
      hasRealAdapter uses id.replace(".", ""); only today's ids are safe.
    location: >-
      backend/src/test/java/ro/pricechecker/lookup/StubAdapters.java:231
    severity: low
  - summary: >-
      Selector currency is set only for "lei" text, not "RON".
    evidence: |-
      fromSelectors checks contains("lei") only.
    location: >-
      backend/src/main/java/ro/pricechecker/retailer/altexro/AltexroAdapter.java:132
    severity: low
  - summary: >-
      docs/compliance-register.md does not yet mention the disabled Altex Adapter.
    evidence: |-
      Docs sync belongs to Aglaia (Knowledge Keeper) at stage 9.
    severity: low
---

<intent-contract>

## Intent

**Problem:** The presenter needs an Altex Adapter that exists, is tested offline and ships disabled, to show it waiting behind the compliance gate (UJ-3, SM-2). Today there is no `retailer.altexro` package, no `fixtures/altex.ro/` and no `altex.ro` entry in `config/retailers.yaml`.

**Approach:** Add an `AltexroAdapter` Spring bean that extracts facts with the shared `extract` helpers in AD-7 order (JSON-LD → meta tags → own selectors), test it against hand-written fixtures, and add `altex.ro` to `retailers.yaml` with `adapter: disabled`. Because the register's Altex row has Limits `—`, narrow `ConfigCheck` so register Limits must parse only for entries that can fetch (enabled or Approved).

## Boundaries & Constraints

**Always:** No request to altex.ro, ever (S-1; register Status `Not checked`). Fixtures are synthetic and hold facts only (S-7, T-2). Tests use a fake `RetailerHttp` (T-3). Unparseable price → `priceMinor` null (C-7). Retailer literals and selectors only in `retailer/altexro/` main code (C-5, ADR 0014). Constructor injection, Spotless format, `-Werror`.

**Never:** Edit `docs/source-register.md`. Use `Jsoup.connect` or any HTTP client in the adapter (C-12). Store name, description or image. Weaken the enabled-but-not-Approved check.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| JSON-LD page | `product-jsonld.html` | `Found` price 129999, RON, IN_STOCK, url = productUrl, EAN from gtin13 | — |
| Meta-only page | `product-meta.html` | `Found` from Open Graph tags | — |
| Selector-only page | `product-selectors.html` | `Found` from Altex selectors (`lei` → RON) | — |
| No facts | `no-product.html` | `Unavailable(UNPARSEABLE)` | — |
| Bad price | `unparseable-price.html` | `Found` with `priceMinor` null | — |
| HTTP fails | fake http throws IOException | `Unavailable(HTTP_ERROR)` | caught in adapter |

</intent-contract>

## Code Map

- `backend/src/main/java/ro/pricechecker/adapter/{RetailerAdapter,AdapterOutcome,PageFacts,RetailerHttp}.java` -- port the adapter implements (AD-7).
- `backend/src/main/java/ro/pricechecker/extract/{StructuredData,MetaTags,Prices,ExtractedFacts}.java` -- reuse `fromJsonLd`, `fromMetaTags`, `Prices.toMinor`; `ExtractedFacts` normalizes bad values.
- `backend/src/main/java/ro/pricechecker/lookup/AdapterCatalog.java` -- start fails unless adapter beans and `retailers.yaml` ids match 1:1, so the bean must exist.
- `backend/src/main/java/ro/pricechecker/lookup/LookupService.java:lookup` -- rows only for Approved domains (AC-9.2 already holds; prove it with bundled config).
- `backend/src/main/java/ro/pricechecker/registry/ConfigCheck.java:checkRetailer` -- Limits parse check; `register.limits(d)` empty for `—`.
- `backend/src/test/java/ro/pricechecker/lookup/StubAdapters.java` -- registers a noop per `retailers.yaml` id; would duplicate the real Altex bean.
- `backend/src/test/java/ro/pricechecker/registry/ConfigCheckTest.java:ac_1_5_2_unparseableRegisterLimits` -- currently expects a violation for a disabled Proposed row with `—`.
- `backend/src/test/java/ro/pricechecker/arch/{ArchitectureRules,RetailerTextScan}.java` -- retailer may use only adapter, ean, extract, fetch; literals allowed only under `retailer/<id>/`.
- `backend/build.gradle.kts:processResources` -- bundles `config/*.yaml` and the register into `registry/`.

## Tasks & Acceptance

**Execution:**
- `fixtures/altex.ro/*.html` + `README.md` -- hand-written pages for each matrix row -- T-2, AC 4.1.1.
- `backend/src/main/java/ro/pricechecker/retailer/altexro/AltexroAdapter.java` (+ `package-info.java`) -- `@Component`, id `altex.ro`; `fromStructuredData`, `fromMetaTags`, `fromSelectors` as separate package-visible functions; first step with a price wins, else first step with any facts, else UNPARSEABLE -- AD-7, C-6.
- `config/retailers.yaml` -- add `altex.ro` `adapter: disabled`, curated, 2000 ms, 1, 4000 -- AC 4.1.2.
- `ConfigCheck.java` -- require parseable register Limits only when the entry is enabled or Approved; otherwise still require positive `minIntervalMs`/`concurrency` -- keeps AD-8 for fetching retailers.
- `ConfigCheckTest.java` -- unparseable-Limits test uses an Approved row; add test that a disabled Not checked row with `—` passes.
- `StubAdapters.java` -- skip ids that already have a scanned bean in `ro.pricechecker.retailer.<id-without-dots>`.
- `backend/src/test/java/ro/pricechecker/retailer/altexro/AltexroAdapterTest.java` -- matrix + each step function + requests only productUrl -- AC 4.1.1.
- `backend/src/test/java/ro/pricechecker/retailer/altexro/AltexroGateTest.java` -- AC 4.1.2 (bundled Registry + LookupService: no altex row) and AC 4.1.3 (test copy with `enabled` → ConfigCheck violation and `Registry` load fails).

**Acceptance Criteria:**
- Given fixtures under `fixtures/altex.ro/`, when adapter tests run, then extraction follows JSON-LD → meta → selectors and the only `RetailerHttp.get` is the product URL on a fake (4.1.1).
- Given bundled `retailers.yaml` with Altex disabled and register Status `Not checked`, when a Lookup runs, then no row has id `altex.ro` (4.1.2).
- Given a copy of `retailers.yaml` with Altex `adapter: enabled`, when the register check runs, then it reports `altex.ro` enabled but not approved (4.1.3).
- Given the full Spring context, when it starts, then it starts with the real Altex bean (existing `AppConfigTest`).

## Spec Change Log

- 2026-11-18 (round 2): review blocker CR-1 (D-2) — the ConfigCheck narrowing changed ADR 0009 / AD-8 / AC 1.5.2 without an ADR. Added ADR 0024 (Proposed, amends ADR 0009), indexed it, marked ADR 0009 amended, and aligned AD-8 and AC 1.5.2 wording; code, test and config comments cite ADR 0024. Behaviour unchanged.

- 2026-11-18 (round 5): review blocker CR-1 (D-2) — main added its own ADR 0024 (CI scanners) and ADR 0025, so this story's ADR is renumbered to 0026 and every reference this story adds now cites ADR 0026. Earlier entries keep the old number as history.

## Review Triage Log

## Design Notes

AD-8 says a Limits value that cannot be parsed fails the check, but the owner-only register has `—` for Altex while the story requires an Altex entry. Limits only constrain fetching; an entry that is disabled and not Approved cannot fetch, so it is exempt. Enabled-or-Approved entries keep the full check.

## Verification

**Commands:**
- `backend/gradlew -p backend check` -- expected: BUILD SUCCESSFUL (Spotless, tests, ArchUnit).

## Review Triage Log (pass 1)

### 2026-11-18 — Review pass
- verdicts: 20 findings — high 0, medium 5, low 11, false 2, maybe-false 2
- findings:
  - `[medium]` `[defer]` Blind: adapter ignores the EAN — not in 4.1 ACs; deferred. (Resolved in round 8: see Round 8.)
  - `[false]` `[reject]` Blind: gate test passes without gate — removing the Approved filter adds a DISABLED Altex row, so the test fails.
  - `[low]` `[reject]` Blind: facts not merged across steps — documented design (first step with a price wins); no AC asks for merging.
  - `[low]` `[defer]` Blind: selector currency only for "lei", no non-RON check — deferred with the EAN item.
  - `[medium]` `[defer]` Blind: ConfigCheck relaxation needs ADR / duplicated checks — deferred to Loredan (Architect)/owner.
  - `[low]` `[defer]` Blind: StubAdapters package mapping fragile — deferred.
  - `[low]` `[reject]` Blind: stockOf keywords loose — synthetic selectors, no real page yet; adding variants adds guesses.
  - `[low]` `[reject]` Blind: CRLF / hasSize(3) brittle tests — repo checks out LF; fix adds complexity.
  - `[low]` `[defer]` Blind: compliance register not updated — stage 9 docs sync.
  - `[low]` `[defer]` Edge: hyphenated id in StubAdapters — same root as Blind StubAdapters item.
  - `[maybe-false]` `[reject]` Edge: non-adapter @Component in retailer package skips stub — no such class exists; would be low.
  - `[maybe-false]` `[reject]` Edge: context imports StubAdapters without scanning retailer — StubAdapters is only loaded by full component scan; would be low.
  - `[low]` `[defer]` Edge: "RON" price text gives null currency — same root as Blind currency item.
  - `[low]` `[reject]` Edge: in-stock variants UNKNOWN — same as Blind stockOf.
  - `[low]` `[reject]` Edge: CRLF in AltexroGateTest — same as Blind CRLF.
  - `[medium]` `[defer]` VerifGap: no-price fallback precedence unpinned — deferred (budget), test to add.
  - `[low]` `[reject]` VerifGap other: indisponibil / nu este branches untested — coverage only.
  - `[medium]` `[defer]` Intent: ConfigCheck outside touches narrows AC 1.5.2 — same root as Blind ADR item; owner question raised.
  - `[medium]` `[defer]` Intent: AC 4.1.2 exercised at service layer, not endpoint — AppConfigTest proves context starts with the real bean; endpoint test deferred.
  - `[false]` `[reject]` Intent: "AD-7" means orchestrator spine — price-checker spine AD-7 is the adapter contract.

## Auto Run Result

Status: done

- **Change:** `AltexroAdapter` (JSON-LD → meta → own selectors via `extract` helpers), five synthetic fixtures + README under `fixtures/altex.ro/`, `altex.ro` added to `config/retailers.yaml` with `adapter: disabled`.
- **Files outside `touches`:** `ConfigCheck.java` (unparseable register Limits allowed only for disabled, not Approved entries), `ConfigCheckTest`, `RegistryAcceptanceGapsTest`, `StubAdapters` (skip ids with a real adapter), new tests `AltexroAdapterTest` (12) and `AltexroGateTest` (4).
- **Review:** 0 patches, 10 deferred (6 frontmatter items after grouping), 10 rejected with reasons above.
- **Follow-up review recommended:** false (no patches applied).
- **Verification:** `backend/gradlew -p backend check` exit 0 (Spotless, compile -Werror, all tests, ArchUnit). altex.ro never contacted; `docs/source-register.md` untouched.
- **Residual risk:** ADR 0026 (numbered 0024 before round 5) is Proposed; Loredan (Architect) or the owner must accept it.

### Round 2

- Fixed CR-1 (D-2): ADR 0024 records the Limits exemption for disabled, not Approved entries and amends ADR 0009; AD-8 and AC 1.5.2 updated. `backend/gradlew -p backend check` exit 0.

### Round 3

- Fixed the `ci_green` gate's `rebase-outcome` blocker (AD-18): rebased the branch onto `origin/main`, which had moved on (stories 2-2, 2-3, 2-10). The conflict in `StubAdapters` was settled by taking story 2-10's version (it already skips ids that a real adapter under `ro.pricechecker.retailer` serves; `AltexroAdapter` has the implicit no-arg constructor that version needs), so this story's own `hasRealAdapter` was dropped.
- `AltexroGateTest` now passes the `RuntimeSwitch` and `LookupLog` that story 2-3 added to the `LookupService` constructor. The switch allows every domain, Altex included, so the test shows that `adapter: disabled` alone holds Altex back.
- `CelroAdapterContextTest` (story 2-10) now expects `altex.ro` among the context's adapter ids, because Altex has a real adapter bean.
- Verification: `backend/gradlew -p backend check` exit 0.

### Round 4

- Fixed CR-1 (D-4, AD-18): rebased onto `origin/main` at d7db3f8 (#39, learn:1-5). The only conflict was the AD-8 **Rule:** line in `ARCHITECTURE-SPINE.md`: kept main's new sentence on `registry.ConfigCheck` and `known-eans.yaml`, and re-applied this story's ADR 0024 qualifier ("for an entry that is `adapter: enabled` or Approved (ADR 0024)").
- Verification: `backend/gradlew -p backend check` exit 0.

### Round 5

- Fixed CR-1 (D-2, AD-18): `origin/main` moved to add2d0b and now has ADR 0024 (CI scanners) and ADR 0025 (model and effort per step). Renamed this story's ADR to `docs/adr/0026-register-limits-required-only-for-fetching-entries.md` and updated its index row, ADR 0009's amendment link, spine AD-8, epics AC 1.5.2, the `config/retailers.yaml` header and the `ConfigCheck`/test comments. Rebased onto add2d0b; the only conflict was the ADR index rows, resolved to 0024, 0025 (main) then 0026 (this story).
- Fixed SEC-1 / CR-4 (T-2): `AltexroAcceptanceGapsTest` no longer pins the altex.ro register Status to exactly `Not checked`; it still asserts altex.ro is not Approved.
- Verification: `backend/gradlew -p backend check` exit 0.

### Round 6

- The round 5 blockers `security-outcome` (AD-18) and `security-verdict` (AD-10) came from Veronel (Security & Compliance), whose step ended without a valid verdict. Neither names a defect in the diff, and both code reviews passed. No code change was needed; the security step has to run again.
- code_lens CR-3 (S-1, exact register Status pin) had already been fixed in round 5: `ac_4_1_2_registerKeepsAltexNotChecked` asserts only that altex.ro is in the register and is not Approved.
- CR-2 (C-7, gtin compared with the requested EAN; resolved in round 8) and CR-3 (C-6, `RON` currency text) stay deferred as recorded. CR-4 (D-2): ADR 0026 is still Proposed and needs the owner's acceptance.
- Rebased onto `origin/main` at 6406f067ef29549503dfb6e95c65187302cd9ca5 (#63 story 2-7, #64 learn 2-4) with no conflicts. Main has no new ADR, so 0026 is still free.
- Verification: `backend/gradlew -p backend check` exit 0.

### Round 7

- The round 6 blockers `security-outcome` (AD-18) and `security-verdict` (AD-10) again came from Veronel (Security & Compliance)'s step ending without a valid verdict. Neither names a defect in the diff, and code_review and code_lens both passed. No code change answers them; the security step has to run again.
- Rebased onto `origin/main` at b624f2a18803692e77197a6f044434fd2b6cffe9 (#65 learn 2-5) with no conflicts. Main has no new ADR, so 0026 is still free.
- CR-2 (AD-6, new; resolved in round 8): `AltexroAdapter` maps every `IOException` to `Unavailable(HTTP_ERROR)` instead of returning `fetch.FetchFailure.outcome()`. Deferred to the story that wires `FetchClient` as the `RetailerHttp` bean (2.8), as the reviewer recommends: `CelroAdapter` and `EvomagAdapter` share the pattern, they should change together, and `FetchFailure`'s factories are package-private in `fetch` (outside this story's touches), so no adapter test can build one yet. Altex ships `adapter: disabled`, so no Lookup reaches this path.
- CR-3 (C-7, gtin compared with the requested EAN; resolved in round 8) was deferred in this round. code_lens CR-3 (S-1) was already fixed in round 5. CR-4 (D-2): ADR 0026 is still Proposed; the PR description must name it for the owner's acceptance.
- Verification: `backend/gradlew -p backend check` exit 0.

### Round 8

- The round 7 blockers `security-outcome` (AD-18) and `security-verdict` (AD-10) came for the third time from Veronel (Security & Compliance)'s step ending without a valid verdict; code_review and code_lens both passed. They name no defect, so this round removes the two open major findings the security step could also raise and rebases.
- Rebased onto `origin/main` at 398a852 (#67 to #73, including story 2.8, which wires `FetchClient` and adds a `BlockStore` and `ResultCache` to `LookupService`). `AltexroGateTest` now passes both to the new constructor, as the upstream tests do. Main has no new ADR, so 0026 is still free.
- CR-2 (AD-6) fixed: `AltexroAdapter` returns `FetchFailure.outcome()` (so a 404 is `NotFound`, robots `BLOCKED` stays `BLOCKED`) and maps any other `IOException` to `HTTP_ERROR`. Test helper `fetch.TestFetchFailures` builds failures for adapter tests; `ac_4_1_1_fetchFailureOutcomeIsReturned` covers it. `CelroAdapter` and `EvomagAdapter` keep the old pattern; they are outside this story's touches.
- CR-3 (C-7) fixed: when the chosen step's gtin is present and differs from the requested EAN (leading zeros ignored), the adapter returns `NotFound` and never reports the other product's price. Covered by `ac_4_1_1_otherProductGtinIsNotFound` and `ac_4_1_1_gtinCheckIgnoresLeadingZerosAndMissingGtin`.
- CR-4 (D-2): ADR 0026 is still Proposed; the PR description must name it for the owner's acceptance.
- Verification: `backend/gradlew -p backend check` exit 0.
- QA (Eufrosina, round 8): the C-7 check read only the winning step's gtin, so a JSON-LD naming another product's GTIN followed by a priced meta step still gave `Found` with that price. `fetchAndExtract` now checks the gtin of every step it reads up to the winner and returns `NotFound` on a mismatch. New tests in `AltexroAcceptanceGapsTest`: `ac_4_1_1_otherProductGtinInAnEarlierStepIsNotFoundEvenWhenALaterStepHasThePrice` (failed before the fix), `ac_4_1_1_gtin14WithLeadingZeroOnThePageIsTheSameProduct`, `ac_4_1_1_refusedFetchFailurePassesThroughWithNoExtraction`. `backend/gradlew -p backend check` exit 0.

### Round 9

- Root cause of the `security-outcome` (AD-18) and `security-verdict` (AD-10) blockers, found in the round 8 step log: Veronel (Security & Compliance) did return a `pass` verdict with no findings, but its JSON block had `reviewer`, `verdict`, `summary` and `findings` and no `target`. The orchestrator's `Verdict` model requires `target`, so the block did not parse, and the step ended with "no valid verdict". The diff has no defect behind these blockers. A valid security verdict needs all four keys: `reviewer`, `target`, `verdict`, `findings`.
- Factory proposal (AD-26): `sdlc/prompts/security-story.md` now names the four required keys and the `target` form. The orchestrator turns this edit into an `owner-review` proposal and reverts it on this branch, so it helps only after the owner merges it.
- CR-2 (D-2) fixed: the review log and the round 7 notes now mark the EAN/gtin (C-7) and `FetchFailure` (AD-6) items as resolved in round 8.
- CR-3 / code_lens CR-1 (C-6, `RON` currency text) stay deferred as recorded in the frontmatter. CR-4 (D-2): ADR 0026 is still Proposed; the PR description must name it for the owner's acceptance.
- No code change this round, so no follow-up review pass ran. `origin/main` is still 398a852, so no rebase was needed.
- QA (Eufrosina (QA), round 9): no test gap behind the round 8 blockers; they come from the security verdict format (see above). The story has 33 tests named by criterion ID (T-1): 23 for 4.1.1, 8 for 4.1.2, 2 for 4.1.3, in `AltexroAdapterTest`, `AltexroAcceptanceGapsTest`, `AltexroGateTest` and `AltexroLookupContextTest`. No test was added. `backend/gradlew -p backend check` exit 0.

### Round 10

- The `ci_green-outcome` (AD-18) blocker on PR #81 (head 6827b27d7e88be36c21663ac7ba585174762642e) had two causes. In CI, `spotlessJava` failed because Maven Central answered HTTP 429 while resolving google-java-format, a transient error; the failed jobs were re-run. Locally, `backend/gradlew -p backend check` also failed 5 tests in `e2e.E2eJourneysApiTest`: story 2.14 (#80) added an e2e registry (`src/test/resources/e2e/`) that lists only `cel.ro` and `evomag.ro`, so `AdapterCatalog` refused to start with "adapter without config [altex.ro]" (AD-7).
- Fix: `e2e/retailers.yaml` gets an `altex.ro` entry, `adapter: disabled`, with the same values as `config/retailers.yaml`, and `e2e/source-register.md` gets an `altex.ro` row marked `Not checked`. Altex is not Approved there, so a Lookup has no Altex row and the journeys' expected rows (`cel.ro`, `evomag.ro`) do not change. These two test resources are outside the story's touches, but the story's adapter bean makes them necessary.
- `merge_pr-missing` follows from the CI failure; it needs no change of its own.
- Verification: `backend/gradlew -p backend check` exit 0, with no failing test.

### Round 11

- Root cause of the `security-outcome` (AD-18) and `security-verdict` (AD-10) blockers, found in the round 10 step log (seq 723): Veronel (Security & Compliance) returned `pass` with all four top-level keys, `target` included, but its one `minor` finding had `severity`, `rule` and `message`, with no `id` and no `summary`. The orchestrator's `Finding` model requires `id` and `summary`, so the block did not parse. The diff has no defect behind these blockers.
- Factory proposal (AD-26): `sdlc/prompts/security-story.md` now also names the finding fields (`id`, `severity`, `summary` required; `rule`, `file`, `line`, `fix` optional). The orchestrator turns this edit into an `owner-review` proposal and reverts it on this branch.
- CR-2 (AD-18) fixed: rebased onto `origin/main` 6fe5ee8; no conflicts.
- CR-1 (T-8): `backend/gradlew -p backend check` exit 0 after the rebase. CR-3 (D-2): ADR 0026 is still Proposed; the PR description must name it for the owner's acceptance. code_lens CR-1 (C-6, `RON` currency text) stays deferred as recorded in the frontmatter.
- No code change this round, so no follow-up review pass ran.
- QA (Eufrosina, round 11): no test change. All three criteria are covered by tests whose names carry the criterion ID (T-1): 4.1.1 in `AltexroAdapterTest` (15) and `AltexroAcceptanceGapsTest` (8 of 9), 4.1.2 in `AltexroGateTest`, `AltexroLookupContextTest` and `AltexroAcceptanceGapsTest`, 4.1.3 in `AltexroGateTest`. `backend/gradlew -p backend check` exit 0; the 29 Altex tests pass with no skips. The security blockers do not come from the tests, so a test change cannot fix them.

### Round 12

- Fixed the `rebase-outcome` blocker (AD-18): `origin/main` moved to fef53d4 (#86 story 4-2, #89). Rebased onto it; the only conflict was `_bmad-output/implementation-artifacts/epic-4-context.md`, which both stories added. Kept main's version (merged with story 4-2, a superset of this story's copy). `open_pr-missing`, `ci_green-missing` and `merge_pr-missing` follow from the failed rebase and need no change of their own.
- Restored `sdlc/prompts/security-story.md` to `origin/main`: commit 5192b81 had committed the round 11 factory proposal on this story branch, and a story PR must not change `protected_paths` (AD-26). The proposal stands as recorded in round 11 and goes through the owner.
- Verification: `backend/gradlew -p backend check` exit 0 after the rebase, including story 4-2's `LayoutChangeTest`, `LayoutChangeCoverageTest` and `LayoutChangeHealthCheckTest`.

### Round 13

- Root cause of the round 12 `security-outcome` (AD-18) and `security-verdict` (AD-10) blockers, found in the round 12 step log (seq 780): Veronel (Security & Compliance) returned `pass` with all four top-level keys, but its two findings had `severity: "info"` and `message`, with no `id` and no `summary`. `info` is not a severity and the `Finding` model requires `id` and `summary`, so the block did not parse. The diff has no defect behind these blockers.
- The owner has since merged the round 11 proposal: `origin/main` d7d4868 has `sdlc/prompts/security-story.md` naming the finding fields and saying there is no `info`. Rebased onto d7d4868; the conflicts were only in that prompt (this branch's old commits touching it), resolved to main's version, so the branch no longer differs from main under `sdlc/`.
- Story 0-90 (#96) added `config/runtime.local.example.yaml` and `RuntimeLocalExampleTest`, which requires every `config/retailers.yaml` id to be listed with `fetching: stopped`. Added `altex.ro: { fetching: stopped }` there (outside the story's touches; the new retailer entry makes it necessary).
- code_review CR-1 (D-1, AGENTS.md adapter pitfall) is left to Aglaia (Knowledge Keeper) after merge, as the finding says; story branches revert `AGENTS.md`. CR-2 (C-7, earlier-step gtin mismatch gives `NotFound`) and code_lens CR-1 (C-1, loose `nu este` stock phrase) need no change now, as their fixes say.
- Verification: `backend/gradlew -p backend check` exit 0.
