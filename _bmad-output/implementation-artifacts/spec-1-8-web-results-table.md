---
title: 'Results table, status line and notices'
type: 'feature'
created: '2026-11-18'
status: 'done'
baseline_revision: '1703d8dacfb3db53ff1440a199bff63eb41b1a4c'
review_loop_iteration: 0
followup_review_recommended: false
context:
  - '{project-root}/web/CLAUDE.md'
  - '{project-root}/_bmad-output/planning-artifacts/ux-designs/ux-price-checker-2026-11-17/EXPERIENCE.md'
  - '{project-root}/_bmad-output/planning-artifacts/ux-designs/ux-price-checker-2026-11-17/DESIGN.md'
warnings: ['oversized']
deferred: []
---

<intent-contract>

## Intent

**Problem:** A successful Lookup response is discarded and failures are silent: the Shopper never sees prices, the cheapest row or what went wrong (story 1.8, UJ-1, UJ-2).

**Approach:** Add `format.ts` (RON price, Bucharest fetch time with injected now), `ResultsTable`, `StatusLine` and `Notice` components, and a Lookup state machine in `App` (idle, loading, results, stale, failed) that renders them per EXPERIENCE.md State Patterns.

## Boundaries & Constraints

**Always:** Copy verbatim from EXPERIENCE.md Voice and Tone. Rows in server order, never sorted or compared by the web (AD-4); `lowest` comes from the server. Green tokens (`--colors-lowest-*`) only in the lowest-row and badge rules. Tokens via `var(--...)` only. Status line `aria-live="polite"`; spinner static under `prefers-reduced-motion`. Focus is never moved by results. Test names carry AC ids (T-1). Time is injected (T-5).

**Never:** Sorting rows, calling `/api/status`, new dependencies, a router or state library, editing generated code, red for retailer failures, skeleton rows, Playwright e2e (story 1.9/T-4 is separate).

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Found with price | `priceMinor: 129999`, `IN_STOCK`, fetched today 12:05Z | `1.299,99 lei`, "In stock", "today, 14:05" with `<time datetime=ISO>`, link "View at X" new tab | none |
| Found, no price | `priceMinor` absent | Price cell "unavailable" | none |
| Non-FOUND | `NOT_FOUND`/`UNAVAILABLE`/`DISABLED` | muted "Not found"/"Unavailable"/"Disabled"; other cells "—" + hidden "not applicable" | none |
| Loading | request in flight | status "Checking retailers…" + spinner; previous table hidden | none |
| Results | f FOUND rows with price of k rows | "Found prices at {f} of {k} retailers." + table | none |
| All failed | f = 0 | same status line + all-failed notice + full table | none |
| Request failed | network error, 5xx, or 400 without a known code | request-failed notice (`role="alert"`), no table, no status | — |
| Invalid submit after results | table shown | field error; table stays; status line hidden | — |

</intent-contract>

## Code Map

- `web/src/App.tsx` -- owns `value`, `error`, `latestRequest`, `editedSinceSubmit`; `startLookup` currently drops the 200 response (line 30). Add lookup state here; keep the stale/edited guards.
- `web/src/generated` -- `LookupResponse {ean, rows: ResultRow[]}`; `ResultRow.fetchedAt` is a `Date`; `ResultRowStatusEnum` (`FOUND|NOT_FOUND|UNAVAILABLE|DISABLED`), `ResultRowAvailabilityEnum` (`IN_STOCK|OUT_OF_STOCK|UNKNOWN`).
- `web/src/EanForm.tsx` -- focuses the input after submit; results must not steal focus.
- `web/src/App.test.tsx` -- existing 1.2.3/1.7 tests; `.page` children must stay header/main/footer; "400 without a known code" now also shows the request-failed notice.
- `web/src/styles/base.css`, `tokens.css`, `base.test.ts` -- every `var()` must be a defined token; no literal font-family.

## Tasks & Acceptance

**Execution:**
- `web/src/format.ts` -- `formatPrice(minor)` → `1.299,99 lei` (`Intl.NumberFormat('ro-RO')`); `formatFetched(date, now)` → `today, HH:mm` or `D Mon YYYY, HH:mm` in Europe/Bucharest; `availabilityText`, `statusText`.
- `web/src/ResultsTable.tsx` -- caption "Prices for EAN {ean}", five `th scope="col"`, rows in given order, lowest row class + "Lowest price" badge, inside `role="region" aria-label="Price results" tabIndex=0` scroll wrapper.
- `web/src/StatusLine.tsx` -- always-mounted `aria-live="polite"` paragraph; loading copy with spinner, or summary.
- `web/src/Notice.tsx` -- neutral box; optional `role`.
- `web/src/App.tsx` -- lookup state and rendering per State Patterns; `now` prop defaulting to `() => new Date()`.
- `web/src/styles/base.css` -- table, status line, spinner (+ reduced motion), notice, lowest row, badge, visually-hidden, scroll region.
- `web/src/format.test.ts`, `web/src/ResultsTable.test.tsx`, `web/src/App.results.test.tsx` -- AC 1.8.1–1.8.6 tests, axe in results state.

**Acceptance Criteria:**
- 1.8.1 Given a LookupResponse, when rendered, then a caption, `scope="col"` headers and rows in server order appear.
- 1.8.2 Given a FOUND row, when rendered, then price, availability, Bucharest fetch time in `<time datetime>` and a new-tab `noopener noreferrer` link appear.
- 1.8.3 Given a non-FOUND row, when rendered, then muted status text and "—"/"not applicable" cells appear; no cell is blank.
- 1.8.4 Given `lowest: true` rows, when rendered, then each has the lowest class and badge; green tokens appear only in those rules.
- 1.8.5 Given each Lookup state, when it occurs, then status line and notices match the matrix; spinner animation is off under reduced motion.
- 1.8.6 Given results arrive, then focus stays in the input; the scroll region is focusable and labelled; focus-visible rule exists; axe has no serious/critical violations in results state.

## Spec Change Log

## Review Triage Log

### 2026-11-18 — Review pass
- verdicts: 24 findings — high 0, medium 2, low 18, false 4, maybe-false 0
- findings:
  - `[low]` `[reject]` Blind: link `href` scheme unchecked — URLs come from the server's curated `config/known-eans.yaml`, and React 19 blocks `javascript:` URLs; a guard adds a branch for a case no code path produces.
  - `[low]` `[patch]` Blind: green row and badge use different conditions; `lowest` on a non-FOUND/unpriced row gives green without badge — `ResultsTable.tsx` now derives one `lowest = found && row.lowest && priceMinor defined` for both; test added.
  - `[low]` `[patch]` Blind: AC 1.8.4 tie fixture marks an Out-of-stock unpriced row lowest — fixture now ties two priced, in-stock FOUND rows (same root cause as the row above).
  - `[low]` `[reject]` Blind: empty `rows` shows "0 of 0" plus the all-failed notice — the server returns one row per Approved retailer (Disabled included); zero Approved retailers is not a demo state.
  - `[low]` `[patch]` Blind: badge `font-weight: 600` literal — now `var(--typography-label-font-weight)`. `2px` spinner/badge sizes stay literal (DESIGN.md gives them raw; existing CSS does the same).
  - `[low]` `[reject]` Blind: request-failed copy also used for 5xx and unknown 400 — EXPERIENCE.md has no other copy; the matrix chose the simplest single failure notice.
  - `[low]` `[reject]` Blind: missing race tests (two valid submits out of order; success after edit) — requestId guard is shared with the tested 400 path; results after an edit belong to the submitted Lookup (intended). Known-400 case patched below.
  - `[false]` `[reject]` Blind: sprint-status not updated — Zamolxe (Orchestrator) owns sprint tracking, not the dev step.
  - `[false]` `[reject]` Blind: spec logs empty / Verification omits `npm ci` — logs are filled at review; the fix edits this spec.
  - `[low]` `[reject]` Blind: CSS-text regex tests are brittle — jsdom applies no CSS (web/CLAUDE.md); brittleness fails loudly, not falsely green for current CSS.
  - `[low]` `[reject]` Blind: `foundWithPrice` computed twice — cosmetic; both calls read the same response.
  - `[low]` `[patch]` Blind: all-failed notice not announced, unused `role="status"` — all-failed notice now has `role="status"`; asserted in the all-failed test.
  - `[low]` `[reject]` Edge: invalid `fetchedAt` crashes render — the backend serialises a `java.time.Instant`; no path yields an invalid date.
  - `[low]` `[reject]` Edge: empty rows (same as the Blind row above).
  - `[low]` `[reject]` Edge: unknown enum renders blank — the contract fixes the enums; generated client and server share `api/openapi.yaml`.
  - `[low]` `[patch]` Edge: lowest on non-FOUND row (same root cause as the Blind lowest row; fixed there).
  - `[false]` `[reject]` Edge: duplicate `retailerId` keys — ids are register domains, validated unique at startup.
  - `[low]` `[reject]` Edge: link scheme (same as the Blind row).
  - `[false]` `[reject]` Edge (claim): caption EAN differs from edited input after a 200 — the caption names the EAN that was looked up, which is correct.
  - `[low]` `[patch]` Edge (claim): literal badge font-weight (same as the Blind row; fixed).
  - `[medium]` `[patch]` Verification gap: known-code 400 ending loading untested — added "a server EAN error ends loading" test (empty status line, no spinner, no table).
  - `[medium]` `[patch]` Verification gap: FOUND-without-price not counted, untested in the page — added a page test expecting "0 of 2" and the all-failed notice.
  - `[low]` `[reject]` Intent: request-failed state has no axe run — the notice is a single `<p role="alert">` with plain text; idle, field-error, results and all-failed axe runs pass.
  - `[low]` `[reject]` Intent: story 1.7 tests edited and 1.7 behaviour for unknown 400s changed — required by the 1.8 request-failed state (matrix row); test files sit beside the touched modules.

## Design Notes

`{k}` is unknown before the response, so loading copy is always "Checking retailers…" (EXPERIENCE.md fallback). `f` counts FOUND rows with a `priceMinor`. An invalid submit turns `results` into `stale` (table only, no status line) and `loading`/`failed` into `idle`. The scroll region is always focusable (simplest; one extra tab stop on wide screens).

## Verification

**Commands:**
- `npm --prefix web run check` -- expected: lint, Prettier, typecheck and Vitest pass.

## Auto Run Result

Status: done

**Summary:** Results table, status line and notices (story 1.8). `App` now tracks the Lookup as idle, loading, results, stale or failed. It renders a "Checking retailers…" status with a spinner that stops under reduced motion, a polite summary, the all-failed and request-failed notices, and an accessible table in server order with the Lowest price row highlighted.

**Files changed:**
- `web/src/format.ts`: RON price (`1.299,99 lei`), Bucharest fetch time with an injected now, availability and status copy.
- `web/src/ResultsTable.tsx`: caption, `scope="col"` headers, FOUND and non-FOUND rows, lowest row and badge, focusable labelled scroll region.
- `web/src/StatusLine.tsx`: always-mounted `aria-live="polite"` line.
- `web/src/Notice.tsx`: neutral notice box with an optional role.
- `web/src/App.tsx`: Lookup state machine and an injected `now` clock.
- `web/src/styles/base.css`: table, status line, spinner, notice, lowest row, badge and visually-hidden styles.
- `web/src/format.test.ts`, `web/src/ResultsTable.test.tsx`, `web/src/App.results.test.tsx`: tests for AC 1.8.1–1.8.6. `web/src/App.test.tsx` and `web/src/App.ean.test.tsx`: unknown-400, 5xx and non-JSON failures now expect the request-failed notice.

**Review:** 24 findings. Patches went to 5 root causes (2 medium, 3 low). The parent session applied them directly to stay within the run budget. Nothing deferred. Each rejection and its reason is in the triage log.

**Follow-up review:** false. The two patched medium findings were test gaps only. The code already behaved correctly, and the new tests pass, so no unverified risk is left to name.

**Verification:** `npm --prefix web run check` passes (ESLint, Prettier, tsc, Vitest: 134 tests).

**Residual risks:**
- The request-failed copy ("Could not reach…") also covers 5xx responses and 400s with an unknown code.
- The scroll region is a tab stop at every width.
- Green-only, reduced-motion and under-600px behaviour are checked as CSS text, not in a browser. Playwright end-to-end tests belong to T-4.
- An empty `rows` response would read "0 of 0".
