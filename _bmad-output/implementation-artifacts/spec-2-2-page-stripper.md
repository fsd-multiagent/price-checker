---
title: 'Story 2.2: Page stripper for fixtures and reports'
type: 'feature'
created: '2026-11-17'
status: 'done'
baseline_revision: '96968683f8001dd7d6f7feddbb2dfa38e2b2b133'
review_loop_iteration: 0
followup_review_recommended: false
context:
  - '{project-root}/docs/standards/coding.md'
  - '{project-root}/docs/standards/testing.md'
  - '{project-root}/_bmad-output/implementation-artifacts/epic-2-context.md'
warnings: []
deferred:
  - summary: >-
      Kept JSON-LD keeps non-fact fields (review, author, image, aggregateRating, sibling @graph nodes); only description is cut.
    evidence: |-
      PageStripper.cutDescriptions only touches "description"; S-7 says facts only. Which fields to keep is a policy call for Veronel (Security & Compliance).
    location: >-
      backend/src/main/java/ro/pricechecker/fetch/PageStripper.java
    severity: medium
  - summary: >-
      Other media and inline code survive: video, audio, object, embed, canvas, link rel=icon/preload, on* and style attributes, data-* blobs.
    evidence: |-
      REMOVED_TAGS and attribute removal cover only the AD-16 list (scripts, styles, images, srcset, iframes, comments).
    location: >-
      backend/src/main/java/ro/pricechecker/fetch/PageStripper.java
    severity: medium
  - summary: >-
      The 200-char cut applies to each text node, so a long description split over many short nodes (several <p> or <li>) survives in full.
    evidence: |-
      r4 review CR-2. AD-16/C-5 give no retailer-agnostic way to find "the description" element; whether S-7 needs a per-element text budget is a policy call for Veronel (Security & Compliance), whose r4 review raised no finding.
    location: >-
      backend/src/main/java/ro/pricechecker/fetch/PageStripper.java
    severity: medium
  - summary: >-
      JSON-LD re-serialization may change number text (19.90 becomes 19.9) and drops blocks with raw control characters or CDATA wrappers.
    evidence: |-
      JsonMapper defaults parse floats as double and reject unescaped control chars. The value is unchanged but the text form is not; unverified on real retailer pages.
    location: >-
      backend/src/main/java/ro/pricechecker/fetch/PageStripper.java
    severity: medium (unverified)
---

<intent-contract>

## Intent

**Problem:** Fixtures (T-2) and Health check reports (AD-16) would store whole retailer pages, including scripts, images and long descriptions, which S-7 forbids. There is no single function that strips a page before it is saved anywhere.

**Approach:** Add `ro.pricechecker.fetch.PageStripper`, a stateless, retailer-agnostic function `static String strip(String html)` that parses with `Jsoup.parse(String)` only. It keeps JSON-LD product data and the product facts, removes everything else listed in AD-16, and cuts description text to 200 characters.

## Boundaries & Constraints

**Always:**
- Remove: every `<script>` except `type="application/ld+json"` whose JSON contains `"@type"` equal to (or a list containing) `"Product"`; `<style>`; `<link rel="stylesheet">`; `<img>`, `<picture>`, `<svg>`; `<iframe>`; `<noscript>`; HTML comments; `srcset` and `sizes` attributes on any element.
- Cut to 200 characters: every text node longer than 200 characters (trimmed text), the `content` attribute of `<meta name|property` ending in `description`, and every `"description"` string value inside a kept JSON-LD block. Cutting must not leave a broken JSON escape sequence, and the JSON-LD must remain valid JSON.
- Keep untouched: `<meta>` price/currency/availability tags, the JSON-LD `offers`, `gtin*` and `@type` values, and the document's `<head>`/`<body>` structure.
- Pin jsoup to an exact version (S-12) and add it to the spine Stack table row `| jsoup |` with the exact version.
- Test names/tags carry AC IDs (T-1): `@Tag("AC-2.2.1")`, `@Tag("AC-2.2.2")`.

**Never:**
- No `Jsoup.connect` or any network access (AD-1, C-12); no retailer name, domain or CSS-selector string literal in `src/main/java` (the text-scan test rejects `[attr=value]`, `a > b`, `div.x`, `.x`/`#x` literals). Walk elements and compare tag names and attributes in code instead.
- No retailer-specific rules (C-5). No changes to the `extract` package (story 2.1 owns it). No callers yet (fixture recording is 2.12/2.13, Health check is 3.6).

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Full page | scripts (inline JS, external src, JSON-LD Product, JSON-LD BreadcrumbList), style, stylesheet link, img with srcset, picture/source, svg, iframe, comment, 1000-char description paragraph | only the JSON-LD Product script remains; none of the other elements, comments or `srcset`/`sizes` remain; description paragraph text is 200 chars | No error expected |
| JSON-LD long description | JSON-LD Product with 1000-char `description` containing escaped quotes | `description` value ≤ 200 chars, block parses as JSON, `offers` unchanged | No error expected |
| `@graph` / list `@type` | JSON-LD with `"@type": ["Product"]` or Product inside `@graph` | block kept | No error expected |
| Meta description | `<meta name="description" content="…1000 chars…">` | content cut to 200 chars; `og:price:amount`/`product:price:*` metas unchanged | No error expected |
| Short text | text nodes ≤ 200 chars | unchanged | No error expected |
| Empty / null input | `""` / `null` | `""` returns a stripped empty document; `null` throws `NullPointerException` | Fail fast |

</intent-contract>

## Code Map

- `backend/src/main/java/ro/pricechecker/fetch/package-info.java` -- `fetch` feature package; PageStripper lives here (AD-16).
- `backend/build.gradle.kts` -- add `implementation("org.jsoup:jsoup:1.24.1")` (exact pin; story 2.1 already pinned it on main, r4 merge took that pin). Jackson (via `spring-boot-starter-webmvc`) is already on the classpath for JSON parsing in tests if needed.
- `backend/src/test/java/ro/pricechecker/arch/ArchitectureRules.java:31-61,136-167` -- `Jsoup#connect`/`org.jsoup.Connection` forbidden outside `fetch`; `fetch` may depend only on `adapter`, `registry` (jsoup is external, allowed).
- `backend/src/test/java/ro/pricechecker/arch/RetailerTextScan.java` -- scans `src/main/java` string literals for domains and CSS selectors; keep PageStripper literals to tag names, attribute names and JSON keys.
- `backend/src/test/java/ro/pricechecker/BuildPinningTest.java` -- forbids dynamic versions.
- `_bmad-output/planning-artifacts/architecture/architecture-price-checker-2026-11-17/ARCHITECTURE-SPINE.md:213` -- Stack row `| jsoup | 1.x |` → exact version.
- `backend/src/main/java/ro/pricechecker/extract/` -- story 2.1 helpers (`StructuredData.fromJsonLd`, `MetaTags.fromMetaTags`), on this branch since the round 4 merge; AC-2.2.2 uses them.

## Tasks & Acceptance

**Execution:**
- `backend/build.gradle.kts` -- add pinned jsoup dependency -- parser for stripping.
- `ARCHITECTURE-SPINE.md` (path above) -- jsoup Stack version is `1.24.1` (from main) -- S-12 pin listed in spine.
- `backend/src/test/resources/fetch/full-page.html` -- synthetic page covering the matrix "Full page" row; use `example.test` URLs, no retailer names.
- `backend/src/test/java/ro/pricechecker/fetch/PageStripperTest.java` -- write first (C-2); cover every matrix row and both ACs.
- `backend/src/main/java/ro/pricechecker/fetch/PageStripper.java` -- final class, private constructor, `public static String strip(String html)` with Javadoc citing S-7, T-2, AD-16.

**Acceptance Criteria:**
- AC-2.2.1: Given the full synthetic page, when `PageStripper.strip` runs, then only the JSON-LD Product block survives among scripts, styles, images, `srcset`, iframes and comments are gone, and description text is cut to 200 characters.
- AC-2.2.2: Given the stripped output, when its product facts are read the way the 2.1 helpers read them (JSON-LD `offers` price, priceCurrency, availability parsed as JSON, and the price/currency/availability `<meta>` tags), then they equal those read from the original page. The test reads them with the 2.1 helpers `StructuredData.fromJsonLd` and `MetaTags.fromMetaTags`.

## Spec Change Log

- 2026-11-18 (round 5): AC-2.2.2 no longer allows a test-local stand-in: the 2.1 helpers are on this branch, so the test must use them (r4 review CR-1).

## Review Triage Log

### 2026-11-18 — Review pass
- verdicts: 30 findings — high 0, medium 9, low 13, false 2, maybe-false 6
- findings:
  - `[medium]` `defer` Blind: JSON-LD keeps review/author/image/@graph siblings — policy beyond AD-16 list; deferred to Veronel (Security & Compliance).
  - `[medium]` `defer` Blind: attributes (on*, style, data-*, hidden inputs) kept — same root as media list; deferred.
  - `[medium]` `defer` Blind: video/audio/object/embed/icon links survive — AD-16 names only images/iframes; deferred.
  - `[medium]` `patch` Blind: Product detection too narrow (schema URL types, MIME params) — isProduct accepts `…/Product`, `…:Product`; type split on `;`; test added.
  - `[low]` `patch` Blind: `</` escape untested — added `ac_2_2_1_jsonLdScriptCloseSequenceStaysInsideScript`.
  - `[low]` `reject` Blind: idempotence, top-level array, non-string description untested — not met in everyday use; extra tests only.
  - `[medium]` `defer` Blind: AC-2.2.2 test reader weaker than 2.1 helpers — grouped with intent finding below.
  - `[low]` `patch` Blind: long-text cut drops surrounding whitespace — now keeps one leading/trailing space and raw inner whitespace.
  - `[low]` `reject` Blind: no input size limit / JSON depth — Jackson 3 StreamReadConstraints apply; callers pass one fetched page.
  - `[low]` `reject` Blind: text-scan workaround — documented in a comment; changing the scan is outside this story.
  - `[low]` `reject` Blind: no dependency verification metadata — no project-wide lockfile exists yet (pre-existing).
  - `[maybe-false]` `defer` Edge: raw control chars/CDATA in JSON-LD drop the block — need real pages to settle; deferred.
  - `[medium]` `patch` Edge: `application/ld+json; charset=utf-8` rejected — fixed with type split.
  - `[medium]` `patch` Edge: `schema:Product`/URL types missed — fixed (same patch as above).
  - `[low]` `patch` Edge: whitespace merge on cut — same patch as above.
  - `[low]` `patch` Edge: microdata `itemprop=description` meta not cut — added itemprop check.
  - `[maybe-false]` `defer` Edge: other media/link rels kept — grouped with media deferral.
  - `[maybe-false]` `defer` Edge: inline style/on* attributes kept — grouped with attribute deferral.
  - `[maybe-false]` `defer` Edge: non-string description / reviewBody uncut — grouped with JSON-LD fields deferral.
  - `[medium]` `patch` Edge: `<!--` in JSON string could misparse script — serializer now escapes every `<` as `\u003c`.
  - `[low]` `patch` Edge: description meta without content gains `content=""` — guarded with hasAttr.
  - `[maybe-false]` `defer` Edge: float re-serialization (19.90 → 19.9) — value equal, text differs; deferred.
  - `[low]` `reject` Edge: Offer description gets cut, contrary to "offers untouched" — the spec's Always rule cuts every description; intended.
  - `[low]` `patch` Gap: `</` escaping untested — test added (same as Blind row).
  - `[low]` `patch` Gap: multi-token/mixed-case stylesheet rel untested — fixture gained `rel="alternate Stylesheet"`.
  - `[false]` `reject` Gap: backend tests never run in the gate — origin/main's sdlc-commands now run `backend/gradlew -p backend check`.
  - `[low]` `reject` Gap: traversal does wasted work on removed subtrees — no output effect.
  - `[medium]` `defer` Intent: AC-2.2.2 oracle is a stand-in for the 2.1 helpers — 2.1 not on this branch; recorded as open question.
  - `[maybe-false]` `reject` Intent: every long text node is cut, not just descriptions — AD-16/C-5 leave no retailer-agnostic way to find "the description"; the facts live in JSON-LD and meta tags, which are unchanged.
  - `[false]` `reject` Intent: spine and build edits outside touches — S-12 requires the pin in build and spine; touches lists the main file only.

## Design Notes

JSON-LD description cut: parse the block with Jackson (`tools.jackson.databind.ObjectMapper` or `com.fasterxml.jackson.databind.ObjectMapper`, whichever the Boot 4 classpath provides), cut every `description` text value recursively, re-serialize. If the block does not parse as JSON, drop it (it cannot be used as product data). Truncation cuts at 200 UTF-16 chars without splitting a surrogate pair.

## Verification

**Commands:**
- `backend/gradlew -p backend check` -- expected: BUILD SUCCESSFUL (Spotless, `-Werror` compile, all tests incl. ArchUnit and text scan).
- `uv run --directory orchestrator pytest -q` -- expected: pass (unchanged gate).

## Auto Run Result

Status: done

- **Change:** `fetch.PageStripper.strip(String)` removes non-product scripts, styles, stylesheet links, images, picture, svg, iframes, noscript, comments, `srcset`/`sizes`, and cuts long text, description metas and JSON-LD descriptions to 200 chars (S-7, T-2, AD-16).
- **Files:** `backend/build.gradle.kts` (jsoup 1.24.1, from main); `ARCHITECTURE-SPINE.md` (jsoup pin); `PageStripper.java` (new); `PageStripperTest.java` (10 tests, AC-2.2.1/2.2.2); `src/test/resources/fetch/full-page.html` (synthetic page).
- **Review:** 9 patch rows applied directly by the parent (MIME params, schema-URL types, `<` escaping, whitespace, itemprop, empty content, 2 tests, fixture); 4 deferred items (frontmatter); rejections with reasons in the triage log. Patched by verdict: medium 4 entries in 2 groups, low 5.
- **Follow-up review:** false. No high patched; patched mediums are covered by new tests.
- **Verification:** `backend/gradlew -p backend check` BUILD SUCCESSFUL (PageStripperTest 10/10); `uv run --directory orchestrator pytest -q` 228 passed, 2 skipped.
- **Residual risks:** deferred items above.

### Round 2 (2026-11-18) — tests gate fix

- **Cause:** the r1 `tests` gate failed before running any command: "sdlc-commands differ from origin/main (AGENTS.md in the worktree)". The branch was based on `9696868`; `origin/main` has since changed the `sdlc-commands` block (added `backend/gradlew -p backend check` and `terraform -chdir=infra test`, ADR 0021). No code defect.
- **Fix:** merged `origin/main` (`c94e11d`) into `story/2-2-page-stripper` with no conflicts; `AGENTS.md` now matches `origin/main` (T-8).
- **Verification:** `uv run --directory orchestrator pytest -q` 286 passed, 2 skipped; `backend/gradlew -p backend check --rerun-tasks` BUILD SUCCESSFUL.


### Round 3 (2026-11-18) — tests gate fix

- **Cause:** the r2 `tests` gate failed again with exit code 1: `origin/main` moved after round 2 (`a573868`, #17 added `npm --prefix web ci` and `npm --prefix web run check` to the `sdlc-commands` block), so the branch's `AGENTS.md` no longer matched `origin/main`. No code defect.
- **Fix:** merged `origin/main` (`a573868`) into `story/2-2-page-stripper` with no conflicts; `AGENTS.md` matches `origin/main` again (T-8). No story code changed, so no new review pass.
- **Verification:** `uv run --directory orchestrator pytest -q` 286 passed, 2 skipped; `backend/gradlew -p backend check --rerun-tasks` exit 0; `npm --prefix web ci` and `npm --prefix web run check` exit 0 (Vitest 22/22).

### Round 4 (2026-11-18) — merge_pr fix

- **Cause:** the r1 `ci_green` gate failed at `merge_pr` (exit 1): PR #22 was `CONFLICTING` with `origin/main` after story 2.1 (#20) merged. Both stories pinned jsoup (2.2: `1.23.2`, 2.1: `1.24.1`) in `backend/build.gradle.kts` and the spine Stack table, and both wrote `epic-2-context.md`. All CI checks were green; no code defect.
- **Fix:** merged `origin/main` (`d2ac9d2a6f3cc32fbc18ba00957c864aaa6389a0`) into `story/2-2-page-stripper`; took `main`'s side for all three conflicts (jsoup `1.24.1`, S-12 pin unchanged in kind; `main`'s epic-2 context). Updated this spec's jsoup references.
- **Verification:** `backend/gradlew -p backend check` BUILD SUCCESSFUL (PageStripperTest passes on jsoup 1.24.1 alongside the 2.1 extract helpers); `uv run --directory orchestrator pytest -q` 325 passed, 2 skipped.

### Round 5 (2026-11-18) — review fix

- **Cause:** the r4 `review_verdicts` gate failed on blocker CR-1 (AC-2.2.2, T-1): the test still read facts with its own jsoup + Jackson code although the round 4 merge brought the 2.1 helpers onto this branch.
- **Fix:** `ac_2_2_2_strippedPageYieldsTheSameProductFacts` now asserts that `StructuredData.fromJsonLd` and `MetaTags.fromMetaTags` find price 129999 bani, RON and `IN_STOCK` on the original page and return equal `ExtractedFacts` on the stripped page; the test-local reader is removed (CR-1). Stale "2.1 not on this branch" text removed (CR-3). CR-2 (per-node cut) deferred to Veronel (Security & Compliance) as an S-7 policy question (frontmatter `deferred`). ArchUnit imports main classes only, so a `fetch` test may use `extract`.
- **Verification:** `backend/gradlew -p backend check` BUILD SUCCESSFUL (PageStripperTest 10/10).

### Round 6 (2026-11-18) — rebase fix

- **Cause:** the r3 `ci_green` gate failed at `rebase` (exit 1), so `open_pr`, `ci_green` and `merge_pr` did not run. `origin/main` moved on (#29–#37), and `git rebase origin/main` replays the original story commit `577a54c` without the round 4 merge commit, so it re-hit the old add/add and content conflicts in `epic-2-context.md`, the spine Stack table and `backend/build.gradle.kts`. No code defect.
- **Fix:** rebuilt `story/2-2-page-stripper` as one linear commit on `origin/main` (`c4da808`): the branch's net change is five new files only (this spec, `PageStripper.java`, `PageStripperTest.java`, `PageStripperCoverageTest.java`, `fetch/full-page.html`). `main` already pins jsoup `1.24.1` (S-12) and holds the epic-2 context, so no other file changes. The later rebase is now a fast-forward.
- **Verification:** `backend/gradlew -p backend check` exit 0 on top of `origin/main` `c4da808`.
