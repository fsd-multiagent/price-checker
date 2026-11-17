---
title: 'Story 1.1: Backend scaffold with enforced package structure'
type: 'feature'
created: '2026-11-17'
status: 'done'
baseline_revision: 'f7ec8cf8dad712430554513604a3b1d589d674f9'
review_loop_iteration: 0
followup_review_recommended: true
context:
  - '{project-root}/docs/adr/0006-backend-ports-and-adapters.md'
  - '{project-root}/docs/adr/0014-backend-build-and-gate-commands.md'
warnings: []
deferred:
  - summary: >-
      Backend check is not in the sdlc-commands tests gate or CI, so AD-1 rules run only by hand.
    evidence: |-
      AGENTS.md sdlc-commands lists only orchestrator pytest; .github/workflows has no gradlew. Fix edits AGENTS.md (ADR 0004, PR to main) and ci.yml (story 1.9).
    location: >-
      AGENTS.md sdlc-commands block
    severity: high
  - summary: >-
      AC-1.1.4 access-log test reads the property, not the running Tomcat pipeline.
    evidence: |-
      AppConfigTest uses the MOCK web environment; a RANDOM_PORT test inspecting the Tomcat valves would observe the started server.
    location: >-
      backend/src/test/java/ro/pricechecker/AppConfigTest.java
    severity: medium
  - summary: >-
      Text scan misses text blocks, descendant selectors like ".price .amount", [attr] without =, other TLDs and resources files; BARE_SELECTOR flags extension literals like ".yaml".
    evidence: |-
      Regex heuristics in RetailerTextScan; no test for these forms. Revisit when registry (story 1.5) and adapters land.
    location: >-
      backend/src/test/java/ro/pricechecker/arch/RetailerTextScan.java
    severity: medium
  - summary: >-
      Wrapper jar checksum and dependency verification metadata not pinned; foojay may download a JDK.
    evidence: |-
      Only distributionSha256Sum is pinned (S-12). Wrapper validation belongs in CI (story 1.9, Pacala (DevOps)).
    location: >-
      backend/gradle/wrapper
    severity: medium
---

<intent-contract>

## Intent

**Problem:** There is no backend yet, so later stories have no place to land and nothing enforces the AD-1 package rules (C-B1, C-5, C-12).

**Approach:** Add a Spring Boot 4 / Java 21 Gradle (Kotlin DSL) project in `backend/` with the feature packages, an ArchUnit test encoding exactly the spine paradigm arrows plus the HTTP-client ban, a source text-scan test for retailer domains and CSS selectors, and app config with Tomcat access logs off and a `Clock` bean. Pin every version and record them in the spine Stack table.

## Boundaries & Constraints

**Always:** `backend/gradlew -p backend check` runs from the repo root (ADR 0014); compile with `-Xlint:all -Werror`; Spotless with google-java-format; constructor injection only (C-B2); test names/tags carry AC ids (T-1, `@Tag("AC-1.1.x")`); every rule has a negative test proving it fails on a violation; Gradle wrapper checksum pinned (S-12).

**Never:** No business logic, endpoints, OpenAPI generation, CI workflow, or Dockerfile (later stories, C-1). No change to the `sdlc-commands` block in `AGENTS.md` (ADR 0004). No real retailer domain in code or tests.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Allowed arrow | `lookup` class uses `cache` type | Arch rules pass | — |
| Forbidden arrow | `cache` class uses `lookup`, or `retailer.a` uses `retailer.b` / `registry` | Rule fails | test asserts `AssertionError` |
| HTTP outside fetch | non-`fetch` class uses `java.net.http.HttpClient` or `URL#openConnection` | Rule fails | asserted |
| HTTP inside fetch | `fetch` class uses `HttpClient` | Rule passes | — |
| Domain literal outside retailer | `"www.example-shop.ro"` in `lookup` source | Text scan reports file | asserted |
| Selector literal outside retailer | `"div.price > span"` or `"[itemprop=price]"` | Text scan reports file | asserted |
| Same literals in `retailer/<id>/` | — | Not reported | — |

</intent-contract>

## Code Map

- `backend/` -- new Gradle project (absent today). Root `.gitignore` ignores `*.jar`, so `backend/.gitignore` must re-include `gradle/wrapper/gradle-wrapper.jar`.
- `_bmad-output/planning-artifacts/architecture/architecture-price-checker-2026-11-17/ARCHITECTURE-SPINE.md:31-55` -- paradigm arrows (source of truth for allowed deps); `:63` AD-1 rule text; `:202-221` Stack table to pin; `:174` AD-14 Tomcat access log off; `:196` Clock convention.
- `docs/adr/0014-backend-build-and-gate-commands.md` -- gate command `backend/gradlew -p backend check`; add implementation note with chosen versions.
- Versions (checked Maven Central / Gradle 2026-11-17): Gradle 9.9.1 (sha256 d8a18a7a…ba228), Spring Boot 4.1.2, ArchUnit 1.5.1, Spotless plugin 8.11.0, google-java-format 2.0.0, foojay-resolver-convention 1.0.0; JDK 21 toolchain.

## Tasks & Acceptance

**Execution:**
- `backend/settings.gradle.kts`, `backend/build.gradle.kts`, `backend/gradle/wrapper/*`, `backend/gradlew*`, `backend/.gitignore` -- Gradle wrapper with checksum, Spring Boot plugin, toolchain 21, `-Xlint:all -Werror`, Spotless, JUnit 5 -- AC 1.1.1.
- `backend/src/main/java/ro/pricechecker/PriceCheckerApplication.java`, `ClockConfig.java`, `<feature>/package-info.java` for each feature package and `retailer` -- skeleton and `Clock` bean -- AC 1.1.2, 1.1.4.
- `backend/src/main/resources/application.yaml` -- `server.tomcat.accesslog.enabled: false` -- AC 1.1.4.
- `backend/src/test/java/ro/pricechecker/arch/ArchitectureRules.java` + `ArchitectureTest.java` -- rules parameterized by root package; run on `ro.pricechecker` and on violation fixtures under test package `ro.pricechecker.archfixtures` -- AC 1.1.2.
- `backend/src/test/java/ro/pricechecker/arch/RetailerTextScan.java` + `RetailerTextScanTest.java` -- scan string literals of `src/main/java` outside `retailer/`; unit tests on in-memory sources -- AC 1.1.3.
- `backend/src/test/java/ro/pricechecker/AppConfigTest.java` -- Spring context starts; access log off; `Clock` bean present -- AC 1.1.4.
- Spine Stack table and ADR 0014 -- record exact versions -- AC 1.1.1.

**Acceptance Criteria:**
- Given a clean checkout, when `backend/gradlew -p backend check` runs from the root, then compile, tests and `spotlessCheck` pass on Java 21 / Spring Boot 4.1.2 and the spine lists the exact versions.
- Given the feature packages, when `ArchitectureTest` runs, then the real code passes and each fixture violation (forbidden arrow, retailer-to-retailer, HTTP client outside `fetch`) fails.
- Given a domain or selector literal outside `retailer.<id>`, when the text scan runs, then it fails naming the file.
- Given the app config, when the context starts, then Tomcat access logging is off and a `java.time.Clock` bean exists.

## Spec Change Log

## Review Triage Log

### 2026-11-17 — Review pass
- verdicts: 24 findings — high 1, medium 9, low 9, false 3, maybe-false 2
- findings:
  - `[low]` `[patch]` AppConfigTest field injection (C-B2) — constructor injection now.
  - `[medium]` `[defer]` access-log test is a property echo — deferred (RANDOM_PORT valve test).
  - `[medium]` `[patch]` isRetailerPath exempts retailer root and nested `retailer/` dirs — now only `ro/pricechecker/retailer/<id>/`; test added.
  - `[medium]` `[defer]` text scan blind spots (text blocks, descendant selectors, TLDs, resources) — deferred.
  - `[medium]` `[defer]` BARE_SELECTOR/TAG_SELECTOR false positives on ".yaml", "i.e." — deferred with the scan item.
  - `[medium]` `[patch]` HTTP ban gaps: openConnection(Proxy), getContent, javax.net, spring http.client — matched by name, packages added.
  - `[medium]` `[patch]` httpoutside fixture masks openConnection — split into `urloutside` fixture with its own test.
  - `[low]` `[reject]` assertViolation does not pin rule message — splitting fixtures covers the named case; message matching adds coupling.
  - `[low]` `[patch]` vacuous real-code run under allowEmptyShould — asserts PriceCheckerApplication imported.
  - `[medium]` `[patch]` unknown packages escape arrow rules — `onlyKnownPackages` rule + `unknownpackage` fixture.
  - `[low]` `[patch]` FEATURES/ALLOWED duplicated — FEATURES derived from ALLOWED.
  - `[medium]` `[defer]` wrapper jar / dependency verification — deferred to CI story.
  - `[false]` `[reject]` wrapper files missing from diff — excluded from the review diff only; committed with the change.
  - `[low]` `[patch]` foojay version missing from spine — row added.
  - `[low]` `[reject]` ADR 0014 still Proposed — status change is the Architect's/owner's, not this story's.
  - `[low]` `[reject]` Spotless not on *.gradle.kts — cosmetic, adds config.
  - `[false]` `[reject]` scan path depends on cwd — Gradle sets the test working dir to backend/ by default; IDE runs are not the gate.
  - `[maybe-false]` `[reject]` char literal '"' shifts literal boundaries — would only be low; settle with a source containing it.
  - `[high]` `[defer]` backend check not in tests gate/CI — fix edits AGENTS.md (ADR 0004) and CI.
  - `[low]` `[patch]` TAG/BARE selector untested — two cases added.
  - `[low]` `[patch]` Clock zone not asserted — asserts UTC.
  - `[low]` `[reject]` most ALLOWED arrows unexercised — table mirrors spine; per-arrow fixtures add bulk for little gain.
  - `[false]` `[reject]` spine also exempts config/ — config/ is repo-root YAML, outside backend/src/main/java.
  - `[maybe-false]` `[reject]` intent R2 "enforced from day one" at gate — same root as the deferred gate item.

## Auto Run Result

- **Summary:** Spring Boot 4.1.2 / Java 21 Gradle 9.9.1 backend in `backend/` with feature packages, ArchUnit AD-1 rules (arrows, known packages, retailer isolation, HTTP ban outside `fetch`), retailer text scan, access log off and a UTC `Clock` bean; versions in spine Stack table and ADR 0014.
- **Files:** `backend/` (build, wrapper, app, config, tests, fixtures); spine Stack table; ADR 0014 implementation note.
- **Review:** 11 patches applied by the parent session (budget; implementation subagent not re-engaged), 4 deferred, 9 rejected (reasons in triage log).
- **Follow-up review recommended:** true — 5 medium patches; unverified risk: new `onlyKnownPackages` and URL-name rules were checked only against the current fixtures.
- **Verification:** `backend/gradlew -p backend check` BUILD SUCCESSFUL, 20 tests (2+10+8), 0 skipped/failed; spotlessCheck clean.
- **Residual risks:** the gate/CI do not run backend check yet (deferred, high); text scan is heuristic.

## Design Notes

Root-package classes (`PriceCheckerApplication`, `ClockConfig`) are wiring and may depend on anything; feature packages may depend only on themselves, their allowed targets, and non-`ro.pricechecker` code. Fixture violations live in test sources with their own root so the production rule run imports main classes only (`ImportOption.DoNotIncludeTests`).

## Verification

**Commands:**
- `backend/gradlew -p backend check` -- expected: BUILD SUCCESSFUL
- `backend/gradlew -p backend spotlessCheck` -- expected: no violations
