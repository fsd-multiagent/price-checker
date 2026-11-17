---
title: 'Extraction helpers for structured data, meta tags and prices'
type: 'feature'
created: '2026-11-17'
status: 'done'
baseline_revision: 'f41f5284992bc329e98a3e66c5c38333c29cb7b9'
review_loop_iteration: 0
followup_review_recommended: true
context:
  - '{project-root}/docs/standards/coding.md'
  - '{project-root}/docs/standards/testing.md'
warnings: [oversized]
deferred:
  - summary: >-
      An all-null ExtractedFacts is still returned as present, so an adapter chaining helpers with or() stops at step 1.
    evidence: |-
      fromJsonLd returns Optional.of when a Product has no offers; adapters 2.10/2.11 must check fields.
    location: >-
      backend/src/main/java/ro/pricechecker/extract/StructuredData.java
    severity: medium
  - summary: >-
      JSON-LD priceSpecification, mainEntity, ProductGroup, offer-level GTIN and product:sale_price are not read.
    evidence: |-
      Not in AC 2.1.1/2.1.2; revisit with recorded fixtures in stories 2.12/2.13.
    severity: low
  - summary: >-
      Extraction order is not composed inside extract; each adapter applies it.
    evidence: |-
      AD-7 puts the order in adapters; no shared chain exists yet.
    severity: low
  - summary: >-
      Microdata (C-6) is not read.
    evidence: |-
      AC 2.1.1 names JSON-LD only.
    severity: low
---

<intent-contract>

## Intent

**Problem:** Adapters (stories 2.10, 2.11) need one shared, network-free way to pull price, currency, availability and GTIN out of a product page, in the fixed order JSON-LD → meta tags → own selectors, and must never turn a bad price into 0 (AD-7, C-6, C-7).

**Approach:** Add three string-only helpers in `ro.pricechecker.extract`: a JSON-LD helper, a meta-tag helper (each returning an `ExtractedFacts` record) and a price-text parser returning integer bani or `null`. Parse HTML with jsoup (`Jsoup.parse(String)` only) and JSON with the Jackson 3 already on the classpath. Add an ArchUnit rule that `extract` has no network or file access.

## Boundaries & Constraints

**Always:** Inputs are `String` HTML/text; outputs are facts only: `priceMinor` (Long bani, >0, nullable), `currency` (3 upper-case letters, nullable), `availability` (`IN_STOCK`/`OUT_OF_STOCK`/`UNKNOWN`), `gtin` (8–14 digits, nullable) (S-7). An unparseable, zero, negative or over-precise price is `null`, never 0 (C-7). Each helper is a separate public function with its own tests named with the AC id (T-1). Pin jsoup to an exact version (S-12) and list it in the spine Stack table.

**Never:** No name, description, image or URL in the result. No CSS-selector or domain string literals in `extract` (the text scan forbids them; match meta tags by iterating elements and comparing attribute values). No `Jsoup.connect`, `java.net`, file or HTTP use. No retailer-specific logic, no Spring beans, no change to `adapter`, `lookup` or other packages' main code.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| JSON-LD Product with Offer | `@type: Product`, `gtin13`, `offers: {price: "1299.99", priceCurrency: "RON", availability: ".../InStock"}` | `ExtractedFacts(129999, "RON", IN_STOCK, "5901234123457")` | none |
| JSON-LD variants | array root, `@graph`, `@type` array, `offers` array, `AggregateOffer.lowPrice`, numeric `price` | first Product's first offer read the same way | none |
| No Product / bad JSON | no ld+json, non-Product types, malformed JSON block | `Optional.empty()` (bad block skipped, others still tried) | no exception |
| Meta tags only | `product:price:amount`/`og:price:amount`, `…:currency`, `product:availability`/`og:availability`, `product:ean` | same `ExtractedFacts` as JSON-LD case | none |
| No product meta tags | page with only `description` meta | `Optional.empty()` | none |
| Price text | `1.299,99 lei` → 129999; `1299.99` → 129999; `1 299,99 RON` → 129999; `1.299` → 129900; `49,9` → 4990 | integer bani | none |
| Bad price | `""`, `null`, `abc`, `0`, `0,00`, `-5`, `12.345.6`, `1,2,3`, `1299.999`, `12 EUR` | `null` | never throws |

</intent-contract>

## Code Map

- `backend/build.gradle.kts` -- add `implementation("org.jsoup:jsoup:1.24.1")` (latest on Maven Central 2026-11-17). Jackson 3 (`tools.jackson.databind.json.JsonMapper`) comes with `spring-boot-starter-webmvc`.
- `_bmad-output/planning-artifacts/architecture/architecture-price-checker-2026-11-17/ARCHITECTURE-SPINE.md` Stack table -- row `| jsoup | 1.x |` → `| jsoup | 1.24.1 (pinned in backend/build.gradle.kts, story 2.1) |`.
- `backend/src/main/java/ro/pricechecker/extract/package-info.java` -- existing; extract may depend on no other app package (`ArchitectureRules.ALLOWED` `"extract", Set.of()`).
- `backend/src/test/java/ro/pricechecker/arch/ArchitectureRules.java` -- `all(root)` list; `httpClientOnlyInFetch` already denies HTTP classes outside `fetch`; add `extractHasNoNetworkAccess(root)` (no dependency on `java.net..` except `java.net.URI`, `java.nio.file..`, `java.io.File*`, `org.jsoup.Connection`, `org.jsoup.helper..`; no call to `Jsoup.connect`).
- `backend/src/test/java/ro/pricechecker/arch/ArchitectureTest.java` -- pattern `assertViolation("<fixture>")`; fixtures live under `ro.pricechecker.archfixtures.<name>`.
- `backend/src/test/java/ro/pricechecker/arch/RetailerTextScan.java` -- forbids `[attr=…]`, `a > b`, `div.x`, `.x`, domain-like literals (`*.org`, `*.ro`…) outside `retailer/`. Schema.org URLs must be matched by suffix after the last `/`, never as a literal.

## Tasks & Acceptance

**Execution:**
- `backend/build.gradle.kts` + spine Stack row -- pin jsoup 1.24.1 -- S-12, BuildPinningTest convention.
- `backend/src/main/java/ro/pricechecker/extract/ExtractedFacts.java` -- record `(Long priceMinor, String currency, Availability availability, String gtin)` with nested enum `Availability {IN_STOCK, OUT_OF_STOCK, UNKNOWN}`; compact constructor normalizes invalid currency/gtin to null, null availability to UNKNOWN -- AC 2.1.1, 2.1.2.
- `backend/src/main/java/ro/pricechecker/extract/Prices.java` -- `static Long toMinor(String text)` and `static Long toMinor(BigDecimal amount)` -- AC 2.1.3.
- `backend/src/main/java/ro/pricechecker/extract/StructuredData.java` -- `static Optional<ExtractedFacts> fromJsonLd(String html)` -- AC 2.1.1.
- `backend/src/main/java/ro/pricechecker/extract/MetaTags.java` -- `static Optional<ExtractedFacts> fromMetaTags(String html)` -- AC 2.1.2.
- `backend/src/main/java/ro/pricechecker/extract/SchemaAvailability.java` (package-private) -- map availability text (schema.org URL suffix or OG words) to the enum, shared by both helpers.
- `backend/src/test/java/ro/pricechecker/extract/{PricesTest,StructuredDataTest,MetaTagsTest}.java` -- `@Tag("AC-2.1.x")`, methods `ac_2_1_x_…`; cover every I/O matrix row with inline synthetic HTML (no retailer pages).
- `ArchitectureRules.java`, `ArchitectureTest.java`, fixture `backend/src/test/java/ro/pricechecker/archfixtures/extractnetwork/extract/NetExtract.java` (calls `Jsoup.parse(java.io.File, String)` or uses `java.net.Socket`) -- new rule passes on real code and fails on fixture -- AC 2.1.4.

**Acceptance Criteria:**
- Given HTML whose JSON-LD Product also has `name`, `description` and `image`, when `fromJsonLd` runs, then the result holds only price, currency, availability and GTIN (record has no other components).
- Given the same product described once in JSON-LD and once in meta tags only, when each helper runs on its own page, then both return equal `ExtractedFacts`.
- Given `backend/gradlew -p backend check`, when it runs, then compile (`-Werror`), Spotless, ArchUnit, text scan and all new tests pass.

## Design Notes

Price text rule (deterministic, no locale guessing): trim; strip one leading/trailing `lei`/`ron` (case-insensitive) and all whitespace incl. NBSP; the rest must match `[0-9.,]+` else `null`. If both `.` and `,` occur, the last one is the decimal mark and the other the grouping mark. If only one kind occurs: once with 1–2 digits after it → decimal; otherwise it is grouping and every group after the first must be exactly 3 digits (else `null`). Grouping groups are always validated. Result via `BigDecimal`: scale > 2, ≤ 0, or overflow → `null`.

JSON-LD: numeric `price` goes through `toMinor(BigDecimal)`; textual through `toMinor(String)`. Type match: `@type` (string or array) whose text after the last `/` or `:` equals `Product`.

## Verification

**Commands:**
- `backend/gradlew -p backend check` -- expected: BUILD SUCCESSFUL.
- `backend/gradlew -p backend test --tests 'ro.pricechecker.extract.*' --tests 'ro.pricechecker.arch.*'` -- expected: all pass.

## Spec Change Log

## Review Triage Log

### 2026-11-18 — Review pass
- verdicts: 30 findings — high 0, medium 3, low 15, false 4, maybe-false 0 (8 descriptive/duplicate rows below counted in low/false)
- findings:
  - `[medium]` `[patch]` Whitespace grouping not validated (`12 99 lei` → 129900) (blind, edge, edge-claim) — added `spaceGroupsValid`; tests `12 99 lei`, `1299 999` → null.
  - `[false]` `[reject]` Numeric GTIN loses leading zeros — a JSON number with leading zeros is invalid JSON, so the block fails to parse.
  - `[medium]` `[defer]` All-null result is still `Optional.of`, so an `or()` chain stops at step 1 — spec defines presence by product/tag existence; adapters (2.10/2.11) decide fallback.
  - `[low]` `[reject]` Lower-case currency becomes null — spec requires 3 upper-case letters; upper-casing would turn `lei` into `LEI`; tried and reverted.
  - `[low]` `[patch]` `application/ld+json; charset=…` skipped — compare media type before `;`.
  - `[low]` `[defer]` priceSpecification, mainEntity, ProductGroup, sale_price, offer-level GTIN not read — not in AC; add when real fixtures (2.12/2.13) need them.
  - `[low]` `[reject]` Meta tag families can mix — rare; fix adds branches.
  - `[medium]` `[patch]` New extract rule only proven on `java.io.File` (blind, verification-gap, intent) — added fixture `extractnio` (`Files.readString`, `InetAddress`) and `ac_2_1_4_extractUsingNioOrNetFails`.
  - `[low]` `[patch]` BuildPinningTest jsoup test mis-tagged AC-2.1.4 — retagged `S-12`.
  - `[low]` `[reject]` Jackson not declared explicitly — BOM-managed via webmvc starter; no named breakage.
  - `[low]` `[reject]` Page parsed several times — microseconds against a 4000 ms deadline.
  - `[false]` `[reject]` jsoup version unverified — checked Maven Central metadata on 2026-11-17 (release 1.24.1).
  - `[low]` `[patch]` Zero-led group `0.999` read as 999 lei — leading group may not start with 0; test added.
  - `[low]` `[patch]` U+2009 thin space not accepted — added to whitespace class.
  - `[low]` `[patch]` Empty meta content hides og: fallback — blank content skipped.
  - `[low]` `[reject]` offers array with non-object first element — unlikely; fix adds a loop.
  - `[low]` `[reject]` availability URL with trailing slash — unlikely.
  - `[low]` `[patch]` Rule misses `java.nio.channels` — added; `RandomAccessFile` already matches `java.io.File*`? no — rejected as unlikely.
  - `[low]` `[defer]` Intent R2: ordering not composed inside `extract` — AD-7 puts the order in each adapter using the helpers.
  - `[low]` `[defer]` Intent R3: microdata not read — AC names JSON-LD only.
  - `[false]` `[reject]` Intent R4: spine/build edits outside "touches" — required by S-12 pinning and AC 2.1.4 tests.
  - `[false]` `[reject]` Intent: ExtractedFacts differs from PageFacts — `extract` may not depend on `adapter` (ALLOWED).

## Auto Run Result

- Summary: string-only helpers in `ro.pricechecker.extract` — `StructuredData.fromJsonLd`, `MetaTags.fromMetaTags`, `Prices.toMinor` (bani or null, never 0) — returning `ExtractedFacts`; ArchUnit rule `extractHasNoNetworkAccess`; jsoup 1.24.1 pinned.
- Files: `backend/build.gradle.kts` (jsoup), spine Stack row, `extract/{ExtractedFacts,Prices,StructuredData,MetaTags,SchemaAvailability}.java`, `arch/ArchitectureRules.java`, `arch/ArchitectureTest.java`, fixtures `extractnetwork`, `extractnio`, `BuildPinningTest.java`, tests `extract/{Prices,StructuredData,MetaTags}Test.java`.
- Review: 8 patches applied (3 medium, 5 low), 4 deferred, rest rejected with reasons above.
- Follow-up review recommended: true — two or more medium patches; risk: the whitespace-grouping rule in `Prices` is new and only covered by a few cases.
- Verification: `backend/gradlew -p backend check` — BUILD SUCCESSFUL after patches.
- Residual risks: real retailer pages may use JSON-LD shapes not handled (deferred list).
- Round 2 (tests gate fix): the r1 `tests` gate failed before running any command with "sdlc-commands differ from origin/main (AGENTS.md in the worktree)" — the branch was cut from `f41f528`, before origin/main changed the AGENTS.md `sdlc-commands` block (PR #12). Fixed by merging `origin/main` into the story branch; no story code changed. Re-verified from the worktree root: `uv run --directory orchestrator pytest -q` (277 passed, 2 skipped) and `backend/gradlew -p backend check --rerun-tasks` (BUILD SUCCESSFUL) (T-8).
