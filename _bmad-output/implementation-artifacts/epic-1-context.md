# Epic 1 Context: Walking skeleton — EAN in, comparison table out

<!-- Generated from planning artifacts. Regenerate with compile-epic-context if planning docs change. -->

## Goal

A Shopper can open the page, enter an EAN (8 or 13 digits), get it validated on both client and server, and see a comparison table with one row per Approved retailer, built from retailer config and the bundled source register, working end to end in the browser and in CI. No retailer is contacted in this epic; it lays the backend, web, contract, compliance gate and CI foundations that later epics fill with real fetching and deployment.

## Stories

- Story 1.1: Backend scaffold with enforced package structure
- Story 1.2: Web scaffold with design tokens
- Story 1.3: API contract and shared EAN cases
- Story 1.4: Server-side EAN validation and lookup logging
- Story 1.5: Source register gate and retailer config
- Story 1.6: Lookup skeleton with row order and lowest price
- Story 1.7: EAN form with client-side validation and URL state
- Story 1.8: Results table, status line and notices
- Story 1.9: CI for backend and web with secret and vulnerability scans

## Requirements & Constraints

- EAN valid only with correct format and GS1 check digit; leading/trailing whitespace trimmed; checked on the page and again on the server (400, no retailer contacted).
- One row per Approved retailer (Disabled included, none for non-Approved); failures stay in their row; the Lookup never returns 5xx because of a retailer.
- Lowest price highlighted (colour plus text) on every tied eligible row; never on non-Found, price-missing or out-of-stock rows.
- No retailer is ever contacted unless the register marks it Approved; no flag or env var can bypass this.
- Privacy: no accounts, cookies or third-party analytics/assets; logs never hold client IP, user agent or raw rejected input.
- Accessibility: WCAG 2.2 AA, keyboard-only journey, real table markup, axe with no serious/critical violations.
- Supply chain: all dependency versions pinned; no committed secrets; CI runs a secret scan and a dependency vulnerability scan that fail on high or critical findings.
- CI builds, lints, format-checks and tests backend and web on every PR alongside the existing orchestrator job, with no network access to any retailer (T-3).
- Tests carry the AC id in names or tags (T-1); time comes from an injected Clock (T-5).
- Stories do not edit the `sdlc-commands` block in `AGENTS.md`; gate commands change only through an owner PR to `main`.

## Technical Decisions

- Backend: Gradle Kotlin DSL wrapper in `backend/` (Gradle 9.9.1, checksum pinned), Spring Boot 4.1.2, Java 21 via toolchain (foojay resolver), ArchUnit 1.5.1, Spotless 8.11.0 + google-java-format 2.0.0, compiler `-Xlint:all -Werror`. Gate command from the repo root, no `cd`: `backend/gradlew -p backend check` (tests, Spotless, ArchUnit, text-scan, register test).
- Web: Vite + React + TypeScript on Node 24 LTS; Vitest, Testing Library, axe-core, Playwright (`web/e2e/`), ESLint, Prettier; pinned versions. Gate commands: `npm --prefix web ci` and `npm --prefix web run check` (`check` = lint, typecheck, unit tests).
- Scanner choice (for example gitleaks, OSV-Scanner) is Pacala's (DevOps) call; thresholds are fixed: high and critical fail.
- Package by feature under `ro.pricechecker`: `api`, `ean`, `adapter` (port types), `lookup`, `cache`, `registry`, `fetch`, `extract`, `health`, `retailer.<id>`. ArchUnit enforces allowed dependency arrows and forbids any HTTP client outside `fetch`; a text-scan test forbids retailer domains and CSS selectors outside `retailer.<id>` and `config/`.
- One backend instance; Tomcat access log off; injected `java.time.Clock` bean.
- `api/openapi.yaml` is the only contract (`GET /api/lookup?ean=`, `GET /api/status`); Spring interfaces and the `typescript-fetch` client are generated in the build (web into `web/src/generated/`), never committed. Generated DTOs live only in `api`; `lookup` returns domain types.
- Wire format: `priceMinor` integer bani (RON), UTC ISO instants, errors as problem+json with `code` in `EAN_EMPTY`, `EAN_NON_DIGIT`, `EAN_LENGTH`, `EAN_CHECK_DIGIT`. Non-FOUND rows carry only `retailerId`, `retailerName`, `status`, `lowest=false`.
- Server orders rows (FOUND by price ascending, FOUND without price, NOT_FOUND, UNAVAILABLE, DISABLED; ties by name) and sets `lowest`; the web app never sorts or compares prices.
- EAN rule implemented once in `ean.Ean` and once in `web/src/ean.ts`, both tested against shared `api/ean-cases.json`.
- Adapter port: `RetailerAdapter.fetchAndExtract(Ean, URI, RetailerHttp)` returns sealed `AdapterOutcome` (`Found`, `NotFound`, `Unavailable`); `PageFacts` holds only price, currency, availability, url, eanOnPage. Startup fails unless `retailers.yaml` ids and adapter beans match one-to-one.
- The bundled copy of `docs/source-register.md` is the sole approval authority; one `registry` parser; only `Approved YYYY-MM-DD` approves; parse errors approve nothing. Register test: enabled implies Approved, Approved implies an entry, valid `resolution`, limits not looser than the register.
- Config: `config/retailers.yaml` and `config/known-eans.yaml` (curated EAN to URL map), validated at start. Retailer id is the register Domain (`cel.ro`).
- `LookupLog`: JSON lines on stdout with allowlisted fields only (`ts`, `level`, `event`, `ean`, `retailer`, `outcome`, `durationMs`); rejected input logs `event=ean_rejected` plus `code`.
- Web: component state only, no state library or router; `/?ean=` via the History API; `Intl.NumberFormat('ro-RO')` for prices; Europe/Bucharest conversion only in the web app; plain CSS custom properties from DESIGN.md, system fonts only.

## UX & Interaction Patterns

- Single centred column (max 880px): header, EAN form, status line, results table, footer note; `lang="en"`.
- Form: label "EAN (8 or 13 digits)", hint, numeric input, autofocus, button "Check prices"; validate on submit only; inline error with "Error:" prefix, `role="alert"`, `aria-invalid`, `aria-describedby`; focus stays in input; server 400 shows the same copy; error clears on input change.
- States: Idle, Invalid input (previous table stays), Loading (previous table hidden), Results, All failed (notice plus full table), Request failed (notice, no table). Status line `aria-live="polite"`; spinner static under `prefers-reduced-motion`.
- Table: caption, `th scope="col"`, rows in server order; Found row price `1.299,99 lei` or "unavailable", Fetched with `<time datetime>`, link in new tab with `rel="noopener noreferrer"`; non-Found rows show muted status in Price cell and "—" elsewhere, never blank, never red.
- Lowest row: green surface, 4px left border, "Lowest price" pill; green used nowhere else.
- Focus ring 2px primary, 2px offset; under 600px the form stacks and the table sits in a focusable labelled scroll region.

## Cross-Story Dependencies

- 1.1 and 1.2 are independent roots (both merged); 1.3 needs both; 1.4 needs 1.3; 1.5 needs 1.1; 1.6 needs 1.4 and 1.5; 1.7 needs 1.3; 1.8 needs 1.7; 1.9 needs 1.1 and 1.2 and owns `.github/workflows/ci.yml`.
- Epic 2 builds fetching, adapters and cache on the `adapter`, `fetch`, `lookup` skeleton; Epic 3 adds image build, deploy and health-check workflows next to the CI workflow.
