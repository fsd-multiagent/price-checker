# web/

React + TypeScript + Vite single-page app (ADR 0013, C-W1 to C-W4). Node 24 LTS (`.nvmrc`).

## Commands (from the repo root)

- Install: `npm --prefix web ci`
- Dev server: `npm --prefix web run dev`
- Build: `npm --prefix web run build` (output in `web/dist/`)
- All checks: `npm --prefix web run check` (ESLint, Prettier check, `tsc -b`, Vitest)
- Format: `npm --prefix web run format`
- Generate the API client: `npm --prefix web run generate` (from `../api/openapi.yaml` into `src/generated/`, `typescript-fetch`). `build` and `check` run it first (`prebuild`, `precheck`). It needs Java 11 or newer on PATH; the first run downloads the OpenAPI Generator jar pinned in `openapitools.json` into `node_modules`.

## Conventions

- Dependencies are pinned to exact versions and `package-lock.json` is committed (S-12). Add one with `npm --prefix web install --save-exact <pkg>@<version>`; `src/scaffold.test.ts` fails on `^` or `~`.
- Styles: plain CSS. Design tokens live only in `src/styles/tokens.css` as `--<group>-<name>` custom properties mirroring DESIGN.md frontmatter; `src/styles/tokens.test.ts` checks them against DESIGN.md. Use `var(--...)`, never raw colours or sizes. Page-level rules are in `src/styles/base.css`.
- No web fonts, `@import`, `@font-face` or third-party URL in CSS or `index.html` (AC-N5.4).
- No UI, state or routing library. The API client is generated into `src/generated/` (not committed, C-W1); never edit it, change `api/openapi.yaml` and regenerate (C-3). Import it from `./generated`. Generated code is type-checked by its own project, `tsconfig.generated.json` (looser flags, referenced from `tsconfig.app.json`), so the app's `noUnusedLocals` and `noUncheckedIndexedAccess` stay on for hand-written code; ESLint and Prettier ignore it.
- Create the client with `new LookupApi(new Configuration({ basePath: '' }))` so calls stay same-origin; the generated default `BASE_PATH` is `http://localhost` (story 1.7).
- The EAN rule lives only in `src/ean.ts` (`validateEan`, `eanErrorMessage`, `isEanErrorCode`; AD-13). `src/ean.test.ts` runs every case in `../api/ean-cases.json`. A server 400 maps its `code` to the same copy as the client check.
- URL state: a valid submit calls `history.replaceState` to `/?ean=<digits>`; there is no `pushState` or `popstate` handling. Validate on submit only; the error clears when the input changes.
- UI copy comes verbatim from EXPERIENCE.md.
- Lookup state lives in `App` as `idle | loading | results | stale | failed` (story 1.8). An invalid submit turns `results` into `stale` (table kept, no status line). A network error, 5xx or 400 without a known `EAN_*` code shows the request-failed notice (`role="alert"`), no table.
- Results: rows render in server order and `lowest` comes from the server; the web never sorts or compares prices (AD-4). Green `--colors-lowest-*` tokens are used only in `.row-lowest` and `.badge-lowest` rules; `src/ResultsTable.test.tsx` fails otherwise. Results never move focus.
- Price and time formatting live only in `src/format.ts` (`formatPrice`, `formatFetched`, `availabilityText`, `statusText`). Fetch times show in Europe/Bucharest. The clock is injected: `App` takes `now?: () => Date` and passes the value to `ResultsTable`; tests pass a fixed `now` (T-5).
- Unit tests: `src/**/*.test.{ts,tsx}`, Vitest + jsdom + Testing Library + axe-core; setup in `src/test/setup.ts`. Put the AC id in each test name (T-1). jsdom does not apply stylesheets, so CSS checks read the files as text. Stub the backend with `vi.stubGlobal('fetch', …)`. jsdom has no implicit form submission, so test Enter through the form's submit event.
- End-to-end tests (Playwright, T-4, story 2.14) are their own npm package in `e2e/`, with its own pinned `package.json` and lockfile (`@playwright/test` 1.65.1). `web/package.json` has no `e2e` script. Run them from the repo root:

  | Step | Command |
  |---|---|
  | Install | `npm --prefix web ci` and `npm --prefix web/e2e ci` |
  | Browser (once) | `npm --prefix web/e2e exec -- playwright install chromium` |
  | Run | `npm --prefix web/e2e test` |

  Playwright starts the e2e backend (`backend/gradlew -p backend bootTestRun`, port `E2E_BACKEND_PORT`, default 18080) and the Vite dev server with `e2e/vite.config.ts` (proxies `/api`, port `E2E_WEB_PORT`, default 5174). Outside CI it reuses servers already on those ports. Output goes to `e2e/node_modules/.e2e/`. Each test uses its own EAN and the AC id in its name (T-1, T-5); the EANs, stub pages and setup are listed in `e2e/README.md`. The suite is not in the orchestrator `tests` gate; CI runs it in the `backend` job. `npm --prefix web run check` lints and formats the `e2e/` files but does not type-check them (story 2.14 deferral).
