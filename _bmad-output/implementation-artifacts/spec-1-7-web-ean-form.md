---
title: 'EAN form with client-side validation and URL state'
type: 'feature'
created: '2026-11-18'
status: 'done'
baseline_revision: '6c2cd020fc9a29d133ca0e33eb4de0af13e76315'
review_loop_iteration: 0
followup_review_recommended: false
context:
  - '{project-root}/web/CLAUDE.md'
  - '{project-root}/_bmad-output/planning-artifacts/ux-designs/ux-price-checker-2026-11-17/EXPERIENCE.md'
warnings: []
deferred: []
---

<intent-contract>

## Intent

**Problem:** The page has no EAN form: a Shopper cannot enter an EAN, typos are not caught before a Lookup, and a Lookup cannot be shared or reloaded by URL (story 1.7, UJ-1).

**Approach:** Add `web/src/ean.ts` (trim, GS1 validation, Voice-and-Tone error copy) tested against `api/ean-cases.json`; a controlled `EanForm` component; and `App` state that validates on submit, starts the Lookup through the generated `LookupApi`, maps a 400 problem+json `code` to the same copy, and syncs `/?ean=`.

## Boundaries & Constraints

**Always:** Copy verbatim from EXPERIENCE.md Voice and Tone. Validate on submit only; trim leading/trailing whitespace only. Error: `role="alert"`, input `aria-invalid="true"` and `aria-describedby` → error id; focus returns to the input after each submit. Error clears on input change. Backend calls only via `web/src/generated` (C-W1), same-origin (`basePath: ''`). Test names carry AC ids (T-1). Only touch `web/src/ean.ts`, `web/src/EanForm.tsx`, `web/src/App.tsx`, their tests, and `web/src/styles/base.css` for form layout using tokens.

**Never:** Rendering results, status line, loading copy or request-failed notices (story 1.8). No router/state library, no new dependencies, no editing generated code, no `popstate` handling.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Valid submit | `" 96385074 "` + Enter | `GET /api/lookup?ean=96385074`; URL `/?ean=96385074` | none |
| Empty/blank | `""`, `"   "` | "Error: enter an EAN." | no request |
| Non-digit | `4006 381333931` | "Error: an EAN contains only digits." | no request |
| Length | `1234567` | "Error: an EAN has 8 or 13 digits. You entered 7." | no request |
| Check digit | `4006381333932` | "Error: the last digit does not match. Check the EAN for a typo." | no request |
| Server 400 | problem+json `{"code":"EAN_CHECK_DIGIT"}` | same copy as client check for that code | shown under input |
| Load `/?ean=<valid>` | page opens | input filled, Lookup runs | none |
| Load `/?ean=<invalid>` | page opens | input filled, no Lookup, no error | none |

</intent-contract>

## Code Map

- `api/ean-cases.json` -- shared cases `{name,input,valid,ean?,code?}`; read in tests as in `web/src/contract.test.ts:21-25`.
- `web/src/generated` (built by `npm run generate`, not committed) -- `LookupApi.lookup({ean})`, `Configuration`, `ResponseError` (`.response`), `EanErrorCode` enum (`EAN_EMPTY|EAN_NON_DIGIT|EAN_LENGTH|EAN_CHECK_DIGIT`). Default `BASE_PATH` is `http://localhost`, so pass `new Configuration({ basePath: '' })`.
- `web/src/App.tsx` -- shell with empty `<main>`; form goes inside `<main>`. `App.test.tsx` checks landmarks order and axe; keep them green.
- `web/src/styles/base.css` -- tokens via `var(--...)` only (`base.test.ts`, `tokens.test.ts` guard this).
- `web/src/test/setup.ts` -- jest-dom + cleanup; stub `fetch` with `vi.stubGlobal` in tests.

## Tasks & Acceptance

**Execution:**
- `web/src/ean.ts` -- `validateEan(input)` returning `{valid:true, ean}` or `{valid:false, code}`; `eanErrorMessage(code, input)` returning the copy (`{n}` = trimmed length) -- one rule, AD-13.
- `web/src/ean.test.ts` -- every `ean-cases.json` case (1.7.1) plus copy per code.
- `web/src/EanForm.tsx` -- controlled form: label/hint/input (`inputmode="numeric"`, `autocomplete="off"`, `autoFocus`)/button; error `<p id role="alert">`; focuses input after submit and when an error appears -- UX-DR3/DR4.
- `web/src/App.tsx` -- state `value`, `error`; read `?ean=` on mount and run if valid; on valid submit `history.replaceState` to `/?ean=<digits>` and call lookup; ignore stale responses; on 400 with known `code` show its copy.
- `web/src/EanForm.test.tsx`, `web/src/App.test.tsx` -- 1.7.2–1.7.5 tests and axe.
- `web/src/styles/base.css` -- form layout and error/hint styles with tokens.

**Acceptance Criteria:**
- 1.7.1 Given every case in `api/ean-cases.json`, when `validateEan` runs, then validity, `ean` and `code` match.
- 1.7.2 Given the page, when rendered, then label "EAN (8 or 13 digits)", hint, numeric autofocused input and "Check prices" button exist, and Enter or button click submits.
- 1.7.3 Given invalid input, when submitted, then fetch is not called, the copy shows with `role="alert"`, `aria-invalid`, `aria-describedby`, focus is in the input, and typing clears it.
- 1.7.4 Given the server answers 400 problem+json with a code, when received, then the client copy for that code shows.
- 1.7.5 Given a valid submit, when the Lookup starts, then the URL is `/?ean=<digits>`; given the page opens at `/?ean=<valid>`, then the input is filled and fetch is called.

## Spec Change Log

## Review Triage Log

### 2026-11-18 — Review pass
- verdicts: 21 findings — high 0, medium 6, low 11, false 4, maybe-false 0
- findings:
  - `[medium]` `[patch]` Blind: typing does not cancel a pending Lookup, so a late 400 re-shows an error on the new text — added `editedSinceSubmit` ref in `App.tsx`; the catch skips `setError` after an edit; test "late 400 after typing".
  - `[low]` `[reject]` Blind: URL keeps the previous EAN after an invalid submit — UX-DR13 ties the URL to the last Lookup, which is what it holds; not a defect.
  - `[low]` `[reject]` Blind: StrictMode sends two Lookups on load — development only; fix needs AbortController plumbing.
  - `[low]` `[patch]` Blind: error not directly under the input (hint between) — moved the error above the hint in `EanForm.tsx` (DESIGN.md field-error).
  - `[false]` `[reject]` Blind: superseded-response test can give a false green — verification-gap mutation run shows deleting the guard fails it.
  - `[medium]` `[patch]` Blind: no test for invalid submit / typing with a Lookup in flight — grouped with the verification-gap finding; tests added.
  - `[low]` `[reject]` Blind: server-400 focus after focus moved to the button is untested — `EanForm` effect focuses on every new error; extra test not worth it.
  - `[false]` `[reject]` Blind: non-400 failures silently dropped — the request-failed notice belongs to story 1.8 (EXPERIENCE.md states table).
  - `[low]` `[reject]` Blind: 1px→2px invalid border shifts layout by 1px — cosmetic; fix adds rules.
  - `[low]` `[reject]` Blind: hardcoded element ids — the page has exactly one form.
  - `[false]` `[reject]` Blind: URL built for root path — the app is served at `/` (architecture: one Caddy site).
  - `[medium]` `[patch]` Edge: late 400 after typing (same root cause as row 1).
  - `[false]` `[reject]` Edge: non-400 failures swallowed (same as row 8).
  - `[low]` `[reject]` Edge: StrictMode double effect (same as row 3).
  - `[low]` `[reject]` Edge: repeated identical error not re-announced — needs a per-submit key; rare.
  - `[low]` `[reject]` Edge: URL after invalid submit (same as row 2).
  - `[low]` `[reject]` Edge: test helper `responses.shift()!` gives TypeError on extra fetch — test-only clarity.
  - `[medium]` `[patch]` Edge (claim): "ignore stale responses" misses edits — fixed with row 1.
  - `[medium]` `[patch]` Edge (claim): error returns after typing — fixed with row 1.
  - `[medium]` `[patch]` Verification gap: `latestRequest.current++` on invalid submit is untested — added test "late 400 after an invalid submit"; mutation confirmed it fails without the line.
  - `[low]` `[reject]` Intent: Enter tested via form submit, UX-DR3 visuals CSS-only — jsdom has no implicit submission and applies no CSS; the native form plus submit button covers Enter.

## Design Notes

`replaceState` (not `pushState`): without a `popstate` handler, pushed entries would change the URL on Back without updating the page; replace keeps reload/share working (UX-DR13) with no stale history. The successful response is discarded until story 1.8 renders it.

## Verification

**Commands:**
- `npm --prefix web run check` -- expected: lint, Prettier, typecheck and Vitest pass.

## Auto Run Result

Status: done

**Summary:** EAN form with client-side GS1 validation, Voice-and-Tone errors, server 400 mapping and `/?ean=` URL state (story 1.7). The successful response is not rendered yet (story 1.8).

**Files changed:**
- `web/src/ean.ts` -- `validateEan`, `eanErrorMessage`, `isEanErrorCode`.
- `web/src/EanForm.tsx` -- controlled, accessible form (label, hint, numeric autofocused input, alert error).
- `web/src/App.tsx` -- validates on submit, calls generated `LookupApi`, maps 400 codes, `history.replaceState`, runs `?ean=` on load, ignores stale or edited-over 400s.
- `web/src/styles/base.css` -- form layout with tokens.
- `web/src/ean.test.ts`, `web/src/EanForm.test.tsx`, `web/src/App.test.tsx` -- AC 1.7.1–1.7.5 tests and axe.

**Review:** 21 findings; patches applied for 3 root causes (2 medium, 1 low), applied by the parent session directly (budget); 0 deferred; rejections and reasons in the triage log.

**Follow-up review:** false (two medium findings patched, but they share one root cause and both new tests were mutation-checked: removing either guard fails them).

**Verification:** `npm --prefix web run check` -- ESLint, Prettier, tsc and Vitest pass (75 tests).

**Residual risks:** StrictMode sends two Lookups in development for `/?ean=`; non-400 failures are silent until story 1.8; raw `48px` and `600px` in CSS have no token in DESIGN.md frontmatter.

