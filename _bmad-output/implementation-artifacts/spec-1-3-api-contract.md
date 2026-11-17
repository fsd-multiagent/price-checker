---
title: 'Story 1.3: API contract and shared EAN cases'
type: 'feature'
created: '2026-11-18'
status: 'done'
baseline_revision: '04280966b48c39f421b430d99429faaed9825a56'
review_loop_iteration: 0
followup_review_recommended: false
context:
  - '{project-root}/docs/standards/coding.md'
  - '{project-root}/docs/standards/testing.md'
  - '{project-root}/docs/adr/0007-lookup-api-contract.md'
warnings: ['oversized']
deferred:
  - summary: >-
      Web client generation and generated.test.ts run in no gate or CI; npm test, typecheck and dev lack a generate pre-hook.
    evidence: |-
      sdlc-commands tests list only pytest and the backend check (AGENTS.md TODO, owner PR); only prebuild and precheck call generate.
    location: >-
      web/package.json, AGENTS.md sdlc-commands
    severity: medium
  - summary: >-
      A missing ean query parameter or a non-EAN 400 returns Spring's default 400 without the required Problem.code.
    evidence: |-
      ean is required: true in api/openapi.yaml; story 1.4 owns the controller and error mapping and must map it to EAN_EMPTY.
    location: >-
      api/openapi.yaml /api/lookup
    severity: medium
  - summary: >-
      Web generation needs Java on PATH and downloads the generator jar from Maven Central without a checksum; npm audit reports high basic-ftp in the CLI.
    evidence: |-
      Dev-only tool; story 1.9 (CI scan, S-12) and story 3.2 (web Dockerfile) must decide.
    location: >-
      web/openapitools.json, web/package-lock.json
    severity: medium
  - summary: >-
      ean-cases.json lacks Unicode whitespace and non-ASCII digit cases where Java strip() and JS trim() differ.
    evidence: |-
      AD-13 says only trim; stories 1.4 and 1.7 should pin the whitespace set.
    location: >-
      api/ean-cases.json
    severity: low
---

<intent-contract>

## Intent

**Problem:** There is no API contract yet, so stories 1.4 (server EAN validation) and 1.7 (web EAN form) have no shared shapes, no generated code and no shared EAN test cases; backend and web could drift (AD-3, C-3, C-W1).

**Approach:** Write `api/openapi.yaml` (OpenAPI 3.0.3) with the two endpoints and the AD-5 wire formats, and `api/ean-cases.json` with the shared EAN cases. Wire OpenAPI Generator 7.27.0 into both builds: Gradle plugin `spring` (interfaces only, Spring Boot 4) into `backend/build/`, and `@openapitools/openapi-generator-cli` `typescript-fetch` into `web/src/generated/`. Neither output is committed.

## Boundaries & Constraints

**Always:** generated Java lives only under `ro.pricechecker.api.generated` (AD-3, ArchUnit `api` rules); generated output goes to ignored folders (`backend/build/`, `web/src/generated/`); exact version pins (S-12) and the spine Stack table row updated to the pinned generator version; tests carry the AC id (T-1); the backend check (`backend/gradlew -p backend check`) and web check stay green, including `-Xlint:all -Werror`.

**Never:** controllers, `ean.Ean`, `web/src/ean.ts`, any EAN validation logic or API client use (stories 1.4, 1.7); edits to generated code; editing the `sdlc-commands` block in `AGENTS.md`; contacting any retailer.

## I/O & Edge-Case Matrix

`api/ean-cases.json` content (AC-1.1 to 1.4, AD-13 order: trim, empty, non-digit, length, check digit):

| Scenario | Input | Expected |
|---|---|---|
| valid EAN-13 | `4006381333931`, `5901234123457` | valid, ean = input |
| valid EAN-8 | `96385074`, `73513537` | valid |
| whitespace-padded | `"  5901234123457\t"`, `" 96385074 "` | valid, ean trimmed |
| empty / blank | `""`, `"   "` | `EAN_EMPTY` |
| non-digit | `"40063813339A1"`, `"4006 381333931"`, `"-96385074"` | `EAN_NON_DIGIT` |
| wrong length | `"1234567"`, `"123456789012"`, `"12345678901234"` | `EAN_LENGTH` |
| bad check digit | `"4006381333932"`, `"96385075"` | `EAN_CHECK_DIGIT` |

</intent-contract>

## Code Map

- `backend/build.gradle.kts` -- add `org.openapi.generator` plugin 7.27.0, a `openApiGenerate` config (generator `spring`, `interfaceOnly`, `useSpringBoot4`, `useJakartaEe`, `useTags`, `openApiNullable=false`, `documentationProvider=none`, `annotationLibrary=none`, `useBeanValidation=false`, packages `ro.pricechecker.api.generated[.model]`), add `build/generated/openapi/src/main/java` to `main` sources, make `compileJava` depend on it. Spotless targets `src/**` only, so generated code is not formatted.
- `backend/src/test/java/ro/pricechecker/BuildPinningTest.java` -- existing pin checks; `spineStackTableListsExactPinnedVersions` pattern to extend for the generator row.
- `backend/src/test/java/ro/pricechecker/arch/ArchitectureRules.java` -- `api` may use `lookup, ean, registry`; `org.springframework.http` (not `.client`) is allowed. Generated classes are imported by `ArchitectureTest`, so they must stay inside `api`.
- `_bmad-output/planning-artifacts/architecture/architecture-price-checker-2026-11-17/ARCHITECTURE-SPINE.md:217` -- Stack row for OpenAPI Generator; set to `7.27.0`.
- `web/package.json`, `web/package-lock.json` -- add devDependency `@openapitools/openapi-generator-cli` 2.42.2 (needs Java on PATH; downloads the generator jar on first run); script `generate`; `build` and `check` run `generate` first.
- `web/openapitools.json` -- pins generator version 7.27.0 for the CLI.
- `web/.gitignore`, `web/eslint.config.js`, `web/.prettierignore` -- already ignore `src/generated/`.
- `web/CLAUDE.md` -- document `generate` and the Java requirement (C-11).
- Snakeyaml and Jackson are on the backend test classpath via the Spring Boot starters; use them to read the YAML and JSON in tests.

## Tasks & Acceptance

**Execution:**
- `api/openapi.yaml` -- contract: `GET /api/lookup` (query `ean` string, required) → 200 `LookupResponse {ean, rows: ResultRow[]}`, 400 `application/problem+json` `Problem`; `GET /api/status` → 200 `StatusResponse {retailers: RetailerStatus[]}`; schemas per Design Notes -- AC 1.3.1.
- `api/ean-cases.json` -- cases from the I/O matrix -- AC 1.3.3.
- `backend/build.gradle.kts` -- generator plugin and wiring as in Code Map -- AC 1.3.2.
- `backend/src/test/java/ro/pricechecker/api/ApiContractTest.java` -- AC 1.3.1 checks on the parsed YAML (paths, operations, field names and types, enums, required lists, 400 media type and code enum); AC 1.3.2 checks that `ro.pricechecker.api.generated.LookupApi`, `StatusApi` and model classes load, and that no `*.java` under `src/` declares package `ro.pricechecker.api.generated`.
- `backend/src/test/java/ro/pricechecker/api/EanCasesTest.java` -- AC 1.3.3: valid cases are trimmed 8/13 digits with a correct GS1 check digit (test oracle); each code appears at least once and every error case matches the AD-13 order; at least one padded valid case.
- `backend/src/test/java/ro/pricechecker/BuildPinningTest.java` -- assert the Stack table row equals the plugin version.
- `web/package.json`, `web/package-lock.json`, `web/openapitools.json`, `web/CLAUDE.md` -- client generation -- AC 1.3.2.
- `web/src/generated.test.ts` -- AC 1.3.2: imports `LookupApi`, `StatusApi` and the `ResultRowStatusEnum` from `./generated` and asserts they exist; asserts `src/generated/` is in `web/.gitignore`.
- `ARCHITECTURE-SPINE.md` Stack row -- pinned version.

**Acceptance Criteria:**
- AC 1.3.1: Given `api/openapi.yaml`, when read, then it defines both endpoints with exactly the AD-3/AD-5 fields (`priceMinor` int64, `fetchedAt` date-time, status and availability enums, `lowest`), and 400 as `application/problem+json` with `code` ∈ the four `EAN_*` values.
- AC 1.3.2: Given the backend and web builds, when they run, then Spring interfaces and the TypeScript client are generated from the contract, and no generated file is tracked by git.
- AC 1.3.3: Given `api/ean-cases.json`, when read, then it lists valid EAN-8 and EAN-13 cases, whitespace-padded input, and at least one case per error code.

## Spec Change Log

## Review Triage Log

## Design Notes

Schemas: `ResultRow` required `retailerId, retailerName, status, lowest`; optional `priceMinor` (integer int64, bani RON, absent = unavailable), `availability` enum `IN_STOCK|OUT_OF_STOCK|UNKNOWN`, `url` (uri), `fetchedAt` (date-time, UTC). `status` enum `FOUND|NOT_FOUND|UNAVAILABLE|DISABLED`. Description states that non-FOUND rows carry only the four required fields. A single schema, not `oneOf`, keeps both generators simple. `RetailerStatus` required `retailerId, retailerName, enabled, blocked`; optional `notBefore` date-time. `Problem` (RFC 9457): `type, title, status, detail, instance` optional, `code` required enum `EanErrorCode`.

`ean-cases.json` shape: `{"cases": [{"name": "...", "input": "...", "valid": true, "ean": "..."} | {"name", "input", "valid": false, "code": "EAN_..."}]}`.

## Verification

**Commands:**
- `backend/gradlew -p backend check` -- expected: exit 0, new tests pass.
- `npm --prefix web ci && npm --prefix web run check && npm --prefix web run build` -- expected: exit 0.
- `git status --short` after both builds -- expected: no generated files listed.

### 2026-11-18 — Review pass
- verdicts: 26 findings — high 0, medium 5, low 12, false 2, maybe-false 7
- findings (blind 11, edge 9, verification-gap 2, intent 4 divergences):
  - `[medium]` `[defer]` missing `ean` / non-EAN 400 has no `code` (blind 1, edge 1, edge 2) — controller and error mapping are story 1.4; deferred.
  - `[maybe-false]` `[defer]` Unicode whitespace/digit cases missing (blind 2, edge 6) — settle in stories 1.4/1.7 when both validators exist.
  - `[low]` `[reject]` AD-5 rules only in prose, no UTC/minimum constraints (blind 3, edge 8, intent R5b) — spec chose one schema over oneOf; OpenAPI 3.0 cannot express the UTC rule; server tests in 1.6 own it.
  - `[low]` `[reject]` no 5xx response declared (blind 4) — AD-5: Lookup never returns 5xx for a retailer; not an AC.
  - `[medium]` `[defer]` no generate pre-hook for test/typecheck/dev (blind 5, edge 3) — grouped with the web gate gap; no budget left this round to patch and re-verify.
  - `[medium]` `[defer]` Java + unverified jar download for web (blind 6) — deferred to stories 1.9/3.2.
  - `[low]` `[reject]` thin web drift checks and gitignore-text check instead of git ls-files (blind 7, edge 9, verification-gap other, intent 1.3.2) — git is absent in build contexts; `git status --short` after both builds showed no generated file.
  - `[low]` `[reject]` CLI wrapper version not cross-checked (blind 8) — exact pin enforced by scaffold.test.ts.
  - `[low]` `[reject]` EanCasesTest NPE on malformed case (blind 9, edge 7) — fails loudly; data is valid.
  - `[low]` `[reject]` Problem field types unchecked (blind 10) — RFC 9457 fields are not in the AC.
  - `[low]` `[reject]` duplicate tsconfig reference (blind 11) — harmless; tsc -b passes.
  - `[maybe-false]` `[reject]` stale web generated files on rename (edge 4) — would be low; tsc fails loudly.
  - `[maybe-false]` `[reject]` doFirst delete vs configuration cache (edge 5) — cache not enabled; would be low.
  - `[medium]` `[defer]` web half of AC 1.3.2 in no gate (verification-gap 1) — owner PR for sdlc-commands (AGENTS.md TODO).
  - `[false]` `[reject]` intent R3 (shared cases not consumed) — story order puts validators in 1.4/1.7.
  - `[false]` `[reject]` intent scope beyond touches — enabling files only (R2).
  - `[maybe-false]` `[reject]` remaining intent notes (strip() choice, Problem extra fields) — descriptive, no defect named.

### 2026-11-18 — Review pass (round 4 follow-up)
- verdicts: 25 findings — high 0, medium 1, low 13, false 6, maybe-false 5
- findings (blind 10, edge 6, verification-gap 3, intent 6 divergences):
  - `[maybe-false]` `[defer]` blind 1: NBSP/BOM trimmed by JS `trim()` but not Java `strip()` — carried: same claim as the Unicode whitespace row above; settle in 1.4/1.7.
  - `[maybe-false]` `[defer]` blind 2: no non-ASCII digit or leading-zero case — carried: Unicode digit row above; settle in 1.4/1.7.
  - `[low]` `[reject]` blind 3: `priceMinor` and `lowest=false` rules only in prose — carried: AD-5 prose row above.
  - `[maybe-false]` `[reject]` blind 4: web `generate` leaves stale files — carried: edge 4 row above (tsc fails loudly).
  - `[medium]` `[defer]` blind 5: web gate needs a JRE and downloads an unverified jar — carried: blind 6 row above (stories 1.9/3.2); AGENTS.md is owner-only.
  - `[low]` `[reject]` blind 6: `ean` has no `maxLength`, no 5xx declared — 5xx carried (AD-5); input bounds belong to the 1.4 validator, adding a constraint now is new public surface.
  - `[low]` `[patch]` blind 7: orchestrator test change has a dead `> 1` assertion after `== 3` — removed the dead assertion. The change itself stays: the r3 `tests` gate (AD-18) failed on this wall-clock flake (`test_real_no_required_checks`, 1 failed / 285 passed), not on story code.
  - `[false]` `[reject]` blind 8: `contract.test.ts` sorts lengths as strings — both sides use the same default sort, so equal sets compare equal; no wrong result.
  - `[low]` `[reject]` blind 9: `ApiContractTest` re-parses the YAML per lookup — test-only cost under a second; not worth a refactor.
  - `[low]` `[reject]` blind 10: no `example` values or extra AD-5 descriptions — documentation nicety, no AC requires it.
  - `[maybe-false]` `[reject]` edge 1: stale web generated files — carried: edge 4 row above.
  - `[maybe-false]` `[defer]` edge 2: NBSP trimmed differently — carried: Unicode whitespace row above.
  - `[low]` `[reject]` edge 3: `EanCasesTest` NPE on malformed case — carried: blind 9 row above (fails loudly, data valid).
  - `[low]` `[reject]` edge 4: float `0.05` passed to `timeout_min: int` — pre-existing: the test already passed `0.01`; no type checker runs on tests.
  - `[false]` `[reject]` edge 5: fake `time` namespace lacks other attributes / 1000-tick limit — `gh.py` reads only `time.monotonic` (lines 93, 105, 130, 142) and the test makes under 20 reads.
  - `[low]` `[patch]` edge 6: redundant `> 1` assertion — same root cause as blind 7; removed.
  - `[low]` `[reject]` verification-gap 1: no test that the Gradle `doFirst` delete clears stale generated classes — gate runs start from a fresh worktree with no `build/`; only incremental local builds can meet it, and the fix is a new test.
  - `[low]` `[patch]` verification-gap other 1: dead `> 1` assertion — same as blind 7; removed.
  - `[low]` `[reject]` verification-gap other 2: CLI 2.42.2 in the spine row not cross-checked — carried: blind 8 row above (exact pin enforced by scaffold.test.ts).
  - `[false]` `[reject]` intent 1: test-side GS1 oracle counts as forbidden EAN validation logic — it lives only in tests to check the data; the boundary names `ean.Ean` and `web/src/ean.ts`, which are absent.
  - `[low]` `[reject]` intent 2: AD-5 value rules untested at HTTP layer — carried: no controllers until 1.4/1.6.
  - `[false]` `[reject]` intent 3: `backend/build/` ignore not tested — existing `backend/.gitignore` covers it; `git status` after builds is clean.
  - `[false]` `[reject]` intent 4: tests carry AC-1.3.x, not AC-1.1–1.4 — T-1 asks for this story's AC ids; the case names cite AC-1.x.
  - `[false]` `[reject]` intent 5: `generated.test.ts` constructs the client — test-only existence check; no app code imports `./generated`.
  - `[low]` `[reject]` intent 6: orchestrator test edit is outside the intent — required to clear the r3 gate failure (AD-18, T-8); reverting it brings the flake back.

## Auto Run Result

Status: done

**Summary:** `api/openapi.yaml` (OpenAPI 3.0.3: `GET /api/lookup`, `GET /api/status`, problem+json 400 with `EAN_*` codes) and `api/ean-cases.json` (16 cases). OpenAPI Generator 7.27.0 generates Spring interfaces (`ro.pricechecker.api.generated`, into `backend/build/`) and a `typescript-fetch` client (`web/src/generated/`); neither is committed.

**Files:** `api/openapi.yaml`, `api/ean-cases.json`; `backend/build.gradle.kts` (generator plugin); `ApiContractTest` (1.3.1, 1.3.2), `EanCasesTest` (1.3.3), `BuildPinningTest` (version row); `web/package.json` + lock, `web/openapitools.json`, `web/tsconfig*.json` (separate project for generated code), `web/src/generated.test.ts`, `web/CLAUDE.md`; spine Stack row pinned to 7.27.0.

**Review:** 26 findings; 0 patched; 4 deferred (frontmatter); the rest rejected with reasons above.

**Follow-up review recommended:** false (nothing patched).

**Verification:** `backend/gradlew -p backend check` exit 0; `npm --prefix web ci`, `run check` (25/25 tests) and `run build` exit 0; `git status` shows no generated files.

**Residual risks:** deferred items; the web side is verified only by hand until web commands join the gate.

### Round 2 (2026-11-18)

Round 1 finished the work but hit its $5 budget before committing (AD-18 `dev-outcome`). Round 2 reapplied `.sdlc/feedback/r2/steps_86.discarded.patch` unchanged, re-ran verification and committed it: `backend/gradlew -p backend check` exit 0; `npm --prefix web ci`, `run check` (25/25) and `run build` exit 0; `uv run --directory orchestrator pytest -q` 277 passed, 2 skipped; `git status` shows no generated files.

### Round 3 (2026-11-18)

The round-2 `tests` gate failed with exit 1 (AD-18 `tests-outcome`). The step log says why: "sdlc-commands differ from origin/main (AGENTS.md in the worktree)". The story never edited `AGENTS.md`; `main` moved on after the branch was cut (#16 ADR 0021, #17 web check in the gate). Merged `origin/main` (`a573868`) into the story branch with no conflicts; the `sdlc-commands` block now equals `origin/main`, so the web check is gated too, which also closes the residual risk above. No story code changed. Verification of every gate command: `uv run --directory orchestrator pytest -q` 286 passed, 2 skipped; `backend/gradlew -p backend check` exit 0; `npm --prefix web ci` exit 0; `npm --prefix web run check` exit 0 (30/30 tests); `git status` clean.

### Round 4 (gate fix and follow-up review)

The round-3 `tests` gate failed with exit 1 (AD-18). Its step log showed `uv run --directory orchestrator pytest -q`: 1 failed, 285 passed. The failure was `tests/test_real_adapters.py::test_real_no_required_checks`, a wall-clock test (0.6 s deadline) that polled `gh` only once on a loaded runner. No story code was involved. Fix: the test now patches `sdlc.adapters.gh.time` with a fake clock (1 s per read, 3 s deadline), so it polls exactly three times whatever the runner speed (T-3 deterministic tests). The dead `> 1` assertion was removed after review.

- Files changed: `orchestrator/tests/test_real_adapters.py` (fake clock in `test_real_no_required_checks`).
- Review: 25 findings. Patched 1 entry (low ×3 members: the dead assertion). No new deferrals; carried deferrals unchanged. All rejected findings and reasons are in the triage log.
- Follow-up review recommended: false (follow-up pass; no `high` patched; patched counts high 0, medium 0, low 1).
- Verification: `uv run --directory orchestrator pytest -q` 286 passed, 2 skipped; `backend/gradlew -p backend check` exit 0; `npm --prefix web ci` exit 0; `npm --prefix web run check` exit 0 (30/30); after the patch `tests/test_real_adapters.py` 53 passed, 1 skipped, `ruff check` and `ruff format --check` clean.
- Residual risk: the diff now touches an orchestrator test outside the story's listed files; `origin/main` may fix the same flake separately, which would produce a trivial merge conflict.

### Round 5 (rebase fix)

The round-4 `ci_green` gate failed: step `rebase` ended with exit 1, so `open_pr`, `ci_green` and `merge_pr` never ran (AD-18). Cause: `origin/main` had moved on to `d2ac9d2` (#18 to #24, among them story 2.1 adding the jsoup pin test). The story commit and #20 both appended a test at the same point in `BuildPinningTest.java`, so the rebase hit a content conflict there. The round-3 merge commit could not help, because a rebase replays the linear commits and drops merges. Fix: rebased the story branch onto `origin/main` and kept both tests (`s12_jsoupVersionIsPinnedAndListed` and `ac_1_3_2_spineStackTableListsThePinnedOpenApiGenerator`). The branch is now linear with no merge commit, and the orchestrator's rebase is a no-op. `ARCHITECTURE-SPINE.md` and `build.gradle.kts` merged on their own; the spine lists both jsoup 1.24.1 and OpenAPI Generator 7.27.0. No story logic changed.

- Verification on the rebased branch: `uv run --directory orchestrator pytest -q` 325 passed, 2 skipped; `backend/gradlew -p backend check` exit 0; `npm --prefix web ci` exit 0; `npm --prefix web run check` exit 0 (30/30).
