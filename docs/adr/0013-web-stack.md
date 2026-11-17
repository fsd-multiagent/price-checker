# 0013. Build the web app with React, TypeScript and Vite, a generated client, and no state or routing library

- **Status:** Proposed
- **Date:** 2026-11-17
- **Deciders:** Loredan (Architect)
- **Stage / story:** stage 4, run 20261117-174705-full
- **Rules touched:** C-W1 to C-W4 (resolves the C-W4 TBD), T-1, T-4, T-5; FR-1, FR-3, FR-4, NFR-4, NFR-5, NFR-6; spine conventions "Web state", "Money", "Time"

## Context

C-W4 leaves language, build tool and test runner to the Architect. The app is one page, one route (EXPERIENCE.md), with no accounts and no analytics. The UX needs an accessibility check (AC-N4.5) and the end-to-end test uses Playwright (T-4).

## Options considered

| Option | Pros | Cons |
|---|---|---|
| A. TypeScript + Vite + React; Vitest, Testing Library, axe-core; Playwright; ESLint + Prettier | Types from the generated client; current mainstream starter; fast tests | A build step (needed anyway) |
| B. JavaScript + Vite | No types | Loses the generated types that keep client and API aligned |
| C. Next.js | Batteries included | Server rendering and routing not needed; more to deploy |

## Decision

Option A. The client is generated from `api/openapi.yaml` with OpenAPI Generator `typescript-fetch` at build time into `web/src/generated/` (not committed). Component state only; no router; `/?ean=` via the History API. Prices formatted with `Intl.NumberFormat('ro-RO')` from `priceMinor`; times with `Intl.DateTimeFormat('ro-RO', { timeZone: 'Europe/Bucharest' })`. Styles are plain CSS from DESIGN.md tokens; no UI library. The production build is static files served by the `web` container. Tests: Vitest + Testing Library + axe-core for components; Playwright in `web/e2e/` against a stubbed backend for UJ-1 and UJ-2 (T-4, AC-N6.2).

## Consequences

- `package.json` scripts: `check` runs lint, typecheck and unit tests; `e2e` runs Playwright. Versions are pinned (S-12) and recorded at the scaffold story.
- No cookies, no third-party scripts (AC-N5.1, AC-N5.4).

## Compliance

No compliance impact beyond NFR-5: the page sets no identifying cookies and loads no third-party script.
