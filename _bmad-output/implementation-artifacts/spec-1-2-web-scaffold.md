---
title: 'Story 1.2: Web scaffold with design tokens'
type: 'feature'
created: '2026-11-17'
status: 'done'
baseline_revision: 'f7ec8cf8dad712430554513604a3b1d589d674f9'
review_loop_iteration: 0
followup_review_recommended: false
context:
  - '{project-root}/docs/standards/coding.md'
  - '{project-root}/docs/standards/testing.md'
  - '{project-root}/_bmad-output/planning-artifacts/ux-designs/ux-price-checker-2026-11-17/DESIGN.md'
warnings: []
deferred:
  - summary: >-
      web/.npmrc (save-exact=true, engine-strict=true) is missing, so npm only warns on a non-24 Node and a plain install writes ^ ranges.
    evidence: |-
      The harness blocked writing .npmrc as a sensitive file. scaffold.test.ts still fails on any loose version; engines >=24 <25 is advisory only. The owner should add the file.
    location: >-
      web/.npmrc
    severity: medium
  - summary: >-
      No CI job or sdlc-commands gate runs npm --prefix web ci, check and build, so the web AC tests run only by hand.
    evidence: |-
      .github/workflows/ci.yml has no Node step; the AGENTS.md sdlc-commands block lists only orchestrator pytest (TODO stage 4). Story touches only web/; story 1.9 (CI) and a stage 9 PR (ADR 0004) own the fix (T-8).
    location: >-
      AGENTS.md sdlc-commands, .github/workflows/ci.yml
    severity: medium
  - summary: >-
      npm audit reports 12 high findings in dev dependencies via picomatch (GHSA-fc5q-39xc-vjpf); every picomatch version is affected, no fix exists.
    evidence: |-
      npm audit lists picomatch '*' through fdir, tinyglobby and typescript-eslint; production deps report 0. Story 1.9 adds the CI vulnerability scan that fails on high (AC-N7.3) and must decide on this (S-12).
    location: >-
      web/package-lock.json
    severity: medium
  - summary: >-
      tokens.test.ts reads DESIGN.md from outside web/, so the suite fails with ENOENT in a build context that holds only web/.
    evidence: |-
      Unverified: depends on whether story 3.2 runs Vitest inside a web-only Docker context. Check the web Dockerfile once it exists.
    location: >-
      web/src/styles/tokens.test.ts:7
    severity: medium (unverified)
---

<intent-contract>

## Intent

**Problem:** There is no web app yet. UI stories 1.7 and 1.8 need a Vite + React + TypeScript base with lint, format, unit tests, axe and the DESIGN.md tokens (ADR 0013, C-W4).

**Approach:** Create `web/` by hand (no starter template). Pin every dependency to an exact version on Node 24 LTS. Put the DESIGN.md tokens in one CSS file of custom properties. Render a page shell (header, empty main, footer note) and add Vitest tests for each AC, tagged with the AC id (T-1).

## Boundaries & Constraints

**Always:** exact versions in `package.json` (no `^`/`~`) and a committed `package-lock.json` (S-12); system fonts only; no third-party URL in `index.html` or CSS (AC-N5.4); copy taken verbatim from EXPERIENCE.md; `lang="en"`; `npm --prefix web run check` runs lint, format check, typecheck and unit tests (C-W4, ADR 0013).

**Never:** EAN form, status line, table, API client or OpenAPI generation (stories 1.3, 1.7, 1.8); Playwright setup (no e2e test in this story); a UI or state library; editing files outside `web/` (story `touches: web/`); editing the `sdlc-commands` block in `AGENTS.md` (ADR 0004).

</intent-contract>

## Code Map

- `_bmad-output/planning-artifacts/ux-designs/ux-price-checker-2026-11-17/DESIGN.md` -- read-only token source (frontmatter `colors`, `typography`, `rounded`, `spacing`).
- `_bmad-output/planning-artifacts/ux-designs/ux-price-checker-2026-11-17/EXPERIENCE.md:30,34` -- header line and footer note copy.
- `docs/adr/0013-web-stack.md` -- stack and `check` script contract.
- No `web/` directory exists yet; this is greenfield.

Pinned versions (npm registry, 2026-11-17): react/react-dom 19.3.1, @types/react(-dom) 19.3.0, vite 8.3.8, @vitejs/plugin-react 6.3.0, typescript 6.0.3 (typescript-eslint 8.73 requires `<6.1`), vitest 5.1.0, jsdom 31.0.1, @testing-library/react 16.3.3, @testing-library/jest-dom 7.0.1, axe-core 4.14.1, eslint 10.15.0, @eslint/js 10.0.1, typescript-eslint 8.73.0, eslint-plugin-react-hooks 7.1.1, eslint-plugin-react-refresh 0.5.8, globals 17.14.0, prettier 3.10.1, @types/node 24.19.2.

## Tasks & Acceptance

**Execution:**
- `web/package.json`, `web/package-lock.json`, `web/.npmrc`, `web/.nvmrc` -- exact pins, `engines.node` `>=24 <25`, scripts `dev`, `build`, `lint`, `format`, `format:check`, `typecheck`, `test`, `check` -- AC 1.2.1.
- `web/tsconfig*.json`, `web/vite.config.ts`, `web/eslint.config.js`, `web/.prettierrc.json`, `web/.prettierignore`, `web/.gitignore` -- tool config; Vitest uses jsdom and a setup file.
- `web/index.html` -- `lang="en"`, title, no external resource.
- `web/src/styles/tokens.css` -- every DESIGN.md color, typography, rounded and spacing token as `--<group>-<name>` custom properties -- AC 1.2.2.
- `web/src/styles/base.css` -- body font, colors, 880px centred column with `xl` top padding, focus indicator.
- `web/src/main.tsx`, `web/src/App.tsx` -- page shell: header (`h1` "Price Checker" + explanation line), `main`, footer note -- AC 1.2.3.
- `web/src/test/setup.ts`, `web/src/App.test.tsx`, `web/src/styles/tokens.test.ts`, `web/src/scaffold.test.ts` -- tests for each AC.
- `web/CLAUDE.md` -- commands and folder conventions for later stories (C-11).

**Acceptance Criteria:**
- AC 1.2.1: Given a clean checkout, when `npm --prefix web ci` and then `npm --prefix web run build` and `npm --prefix web run check` run on Node 24, then all pass; a test fails if any dependency version is not exact or `engines.node` is not 24.
- AC 1.2.2: Given `tokens.css`, when the test reads it, then every DESIGN.md token is defined with the DESIGN.md value, font stacks are system fonts, and neither CSS nor `index.html` contains `@font-face`, `@import` or an `http(s)://` URL.
- AC 1.2.3: Given `<App />` rendered with Testing Library, then it shows the heading "Price Checker", the explanation line and the footer note; `index.html` has `lang="en"`; the column rule uses `--spacing-content-max-width` (880px) with auto margins; axe reports no serious or critical violations.

## Spec Change Log

## Review Triage Log

### 2026-11-17 — Review pass
- verdicts: 28 findings — high 0, medium 12, low 11, false 4, maybe-false 1
- findings:
  - `[medium]` `[defer]` (blind) web/.npmrc missing — harness blocks the file; deferred to owner.
  - `[low]` `[reject]` (blind) base.css focus outline uses raw 2px — DESIGN.md components.focus-indicator specifies 2px literally; no token exists for it.
  - `[medium]` `[patch]` (blind) token check one-directional — added test that tokens.css defines no token absent from DESIGN.md.
  - `[medium]` `[patch]` (blind) external-load scan non-recursive and misses protocol-relative URLs — now scans all `src/**/*.css` and rejects `url(//`, `src=//`, `href=//`.
  - `[low]` `[reject]` (blind) axe in jsdom skips contrast — jsdom cannot render; DESIGN.md documents ratios; contrast is covered by UI stories' checks, fix adds a contrast calculator.
  - `[maybe-false]` `[defer]` (blind) DESIGN.md path outside web/ — would be medium if a web-only test context exists; settle with story 3.2 Dockerfile.
  - `[low]` `[reject]` (blind) lockfile root entry not compared — `npm ci` itself fails on a stale root; verified `npm ci` passes.
  - `[medium]` `[defer]` (blind) build not in check / no gate — grouped with CI-gate gap below.
  - `[medium]` `[defer]` (blind) T-8 gap not recorded — recorded as deferred item.
  - `[low]` `[reject]` (blind) app tsconfig includes node types — fix requires a third tsconfig; ESLint already restricts node globals to tests.
  - `[low]` `[patch]` (blind) `__dirname` in ESM tests — replaced with `import.meta.dirname`.
  - `[low]` `[reject]` (blind) no favicon — dev console noise only; no AC.
  - `[medium]` `[patch]` (edge) protocol-relative URL — same patch as above.
  - `[medium]` `[patch]` (edge) CSS outside src/styles unscanned — same patch.
  - `[medium]` `[patch]` (edge) extra tokens in tokens.css — same patch as reverse check.
  - `[low]` `[reject]` (edge) CRLF DESIGN.md — repo files are LF; would fail loudly, not pass silently.
  - `[low]` `[reject]` (edge) quoted/tab/deep keys skipped — DESIGN.md has none; adding a parser error branch is extra complexity.
  - `[low]` `[reject]` (edge) unterminated quote — not present in DESIGN.md; YAML would be invalid.
  - `[low]` `[reject]` (edge) case-insensitive compare — font names are case-insensitive in CSS; no harm.
  - `[low]` `[reject]` (edge) lockfileVersion 1 — npm 11 writes v3; test fails loudly anyway.
  - `[medium]` `[defer]` (edge) engines not enforced — same root cause as .npmrc.
  - `[medium]` `[defer]` (edge) .npmrc in spec but absent — same root cause as .npmrc.
  - `[medium]` `[defer]` (verification-gap) web check/build not in CI or sdlc-commands gate — outside touches; story 1.9 and stage 9 PR.
  - `[medium]` `[defer]` (verification-gap other) .npmrc missing — same as above.
  - `[false]` `[reject]` (intent) 1.2.1 build/lint not executed by tests — verified by running `build` and `check` (which runs ESLint, Prettier, tsc, Vitest); gate part grouped with CI deferral.
  - `[false]` `[reject]` (intent) 1.2.2/1.2.3 checked in jsdom, not a loaded page — AC 1.2.3 asks for "an axe test in Vitest"; Playwright is a later story (T-4); `lang` is checked on index.html, the shipped document.
  - `[false]` `[reject]` (intent) changes not committed / `_bmad-output` files outside touches — commit happens at finalize; spec and epic context are workflow artifacts.
  - `[false]` `[reject]` (intent) — duplicate of the pipeline-reading divergence; covered by the CI deferral.

## Design Notes

jsdom does not apply stylesheets, so the 880px and token checks read the CSS files as text rather than computed styles. The `lang` check reads `index.html` because the Vitest render mounts into jsdom's document, not `index.html`.

## Verification

**Commands:**
- `npm --prefix web ci` -- expected: installs from the lockfile.
- `npm --prefix web run build` -- expected: exit 0, `web/dist/` produced.
- `npm --prefix web run check` -- expected: exit 0 (lint, prettier, tsc, vitest).

## Auto Run Result

Status: done

**Summary:** New `web/` app: Vite 8 + React 19.3 + TypeScript 6.0 on Node 24, with ESLint 10, Prettier 3, Vitest 5, Testing Library and axe-core. Every dependency is pinned to an exact version and the lockfile is committed. The DESIGN.md tokens are CSS custom properties. The page shell has the header, an empty main and the footer note. Each AC has tests named with its id (T-1).

**Files:** `web/package.json` and `package-lock.json` (exact pins, scripts, engines); `web/.nvmrc`; tool configs (`tsconfig*.json`, `vite.config.ts`, `eslint.config.js`, Prettier, `.gitignore`); `web/index.html` (`lang="en"`); `web/src/styles/tokens.css` (tokens); `web/src/styles/base.css` (880px column, focus ring); `web/src/App.tsx` and `main.tsx` (shell); `web/src/test/setup.ts`; tests `scaffold.test.ts` (1.2.1), `styles/tokens.test.ts` (1.2.2), `App.test.tsx` (1.2.3); `web/CLAUDE.md` (C-11).

**Review:**
- 28 findings.
- Patched (applied by Marghioala (Developer) directly to save budget): 5 medium and 1 low, grouped into 3 fixes: a reverse token-drift check, a recursive CSS scan that also rejects protocol-relative URLs, and `import.meta.dirname`.
- Deferred: 4 items (frontmatter): `.npmrc` blocked by the harness; no CI or gate for web; picomatch advisory with no fix; DESIGN.md path outside `web/` (unverified).
- Rejected: 10 low and 4 false, with reasons in the triage log.

**Follow-up review recommended:** false. No high was patched. The two medium fixes are test-only and were re-run.

**Verification:** `npm --prefix web ci` exits 0; `npm --prefix web run build` exits 0; `npm --prefix web run check` exits 0 (13 of 13 tests pass, lint, Prettier and tsc clean).

**Residual risks:** the deferred items above. TypeScript is held at 6.0.3 because typescript-eslint 8.73 supports only TypeScript below 6.1.
