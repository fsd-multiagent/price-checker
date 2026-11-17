# Epic 1 Context: Walking skeleton — EAN in, comparison table out

<!-- Generated from planning artifacts. Regenerate with compile-epic-context if planning docs change. -->

## Goal

A Shopper can open the page, enter an EAN (8 or 13 digits), get it validated on both client and server, and see a comparison table with one row per Approved retailer, built from retailer config and the bundled source register. No retailer is contacted in this epic; it lays the backend, web, contract, compliance gate and CI foundations that later epics fill with real fetching.

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

- EAN valid only with correct format and GS1 check digit; checked on the page and again on the server (400, no retailer contacted).
- One row per Approved retailer (Disabled included, none for non-Approved); failures stay in their row; the Lookup never returns 5xx because of a retailer.
- Lowest price is highlighted (colour plus text) on every tied eligible row; never on non-Found, price-missing or out-of-stock rows.
- No retailer is ever contacted unless the register marks it Approved; no flag or env var can bypass this.
- Privacy: no accounts, cookies or third-party analytics; logs never hold client IP, user agent or raw input.
- Accessibility: WCAG 2.2 AA, keyboard-only journey, real table markup, axe with no serious/critical violations.
- Supply chain: pinned dependencies, CI secret scan and vulnerability scan failing on high/critical.
- Tests carry the AC id in names or tags (T-1); time comes from an injected Clock (T-5); no network to retailers in tests (T-3).
- Gate commands for backend/web are not edited into the `AGENTS.md` sdlc-commands block by stories here.

## Technical Decisions

- Greenfield: Gradle (Kotlin DSL) Spring Boot 4.x on Java 21 in `backend/`; Vite + React + TypeScript on Node 24 LTS in `web/`. The scaffold story pins exact GA versions after a web check (including a Spring Boot 4 + OpenAPI Generator compatibility spike) and updates the spine Stack table.
- Package by feature under `ro.pricechecker`: `api`, `ean`, `adapter` (port types), `lookup`, `cache`, `registry`, `fetch`, `extract`, `health`, `retailer.<id>`. ArchUnit enforces the allowed dependency arrows from the spine diagram and forbids any HTTP client outside `fetch`; a text-scan test forbids retailer domains and CSS selectors outside `retailer.<id>` and `config/`.
- One backend instance; Tomcat access log off; injected `java.time.Clock` bean.
- `api/openapi.yaml` is the only contract (`GET /api/lookup?ean=`, `GET /api/status`); Spring interfaces and the TS client are generated in the build, never committed. Generated DTOs live only in `api`; `lookup` returns domain types.
- Wire format: `priceMinor` integer bani (RON), UTC ISO instants, errors as problem+json with `code` in `EAN_EMPTY`, `EAN_NON_DIGIT`, `EAN_LENGTH`, `EAN_CHECK_DIGIT`. Non-FOUND rows carry only `retailerId`, `retailerName`, `status`, `lowest=false`.
- Server orders rows (FOUND by price ascending, FOUND without price, NOT_FOUND, UNAVAILABLE, DISABLED; ties by name) and sets `lowest`; the web app never sorts or compares prices.
- EAN rule (trim, then empty, non-digit, length, check digit) implemented once in `ean.Ean` and once in `web/src/ean.ts`, both tested against shared `api/ean-cases.json`.
- Adapter port: `RetailerAdapter.fetchAndExtract(Ean, URI, RetailerHttp)` returns sealed `AdapterOutcome` (`Found`, `NotFound`, `Unavailable`); `PageFacts` holds only price, currency, availability, url, eanOnPage. Startup fails unless `retailers.yaml` ids and adapter beans match one-to-one.
- The bundled copy of `docs/source-register.md` is the sole approval authority; one `registry` parser; only `Approved YYYY-MM-DD` approves, parse errors approve nothing. A register test checks enabled implies Approved, Approved implies an entry, valid `resolution`, limits not looser than the register.
- Config: `config/retailers.yaml` and `config/known-eans.yaml` (curated EAN to URL map), Spring `@ConfigurationProperties` validated at start. Retailer id is the register Domain (`cel.ro`); Java package is the domain without dots.
- `LookupLog`: JSON lines on stdout with allowlisted fields only (`ts`, `level`, `event`, `ean`, `retailer`, `outcome`, `durationMs`); a rejected input logs `event=ean_rejected` plus `code`.
- Web: component state only, no state library or router; `/?ean=` via the History API; Intl.NumberFormat('ro-RO') for prices; Europe/Bucharest conversion only in the web app; plain CSS custom properties from DESIGN.md, system fonts only.
- Each story's yaml block (key, depends_on, touches, sources) is parsed by the orchestrator.

## UX & Interaction Patterns

- Single centred column (max 880px) with header, EAN form, status line, results table, footer note; `lang="en"`.
- Form: visible label "EAN (8 or 13 digits)", hint, numeric input, autofocus, button "Check prices"; validate on submit only; inline error with "Error:" prefix, `role="alert"`, `aria-invalid`, `aria-describedby`; focus stays in input; server 400 shows the same copy; error clears on input change.
- States: Idle, Invalid input (previous table stays), Loading (previous table hidden), Results, All failed (notice plus full table), Request failed (notice, no table). Status line `aria-live="polite"`, spinner static under `prefers-reduced-motion`.
- Table: caption, `th scope="col"`, rows in server order; Found row price `1.299,99 lei` or "unavailable", Fetched time with `<time datetime>`, link opens in new tab with `rel="noopener noreferrer"`; non-Found rows show muted status text in Price cell and "—" elsewhere, never blank, never red.
- Lowest row: green surface, 4px left border, "Lowest price" pill; green used nowhere else.
- Focus ring 2px primary, 2px offset; under 600px the form stacks and the table sits in a focusable labelled scroll region.

## Cross-Story Dependencies

- 1.1 and 1.2 are independent roots; 1.3 needs both (contract generation wired into both builds).
- 1.4 needs 1.3; 1.5 needs 1.1; 1.6 needs 1.4 and 1.5; 1.7 needs 1.3; 1.8 needs 1.7; 1.9 needs 1.1 and 1.2.
- Later epics build on this: Epic 2 adds the fetch client, adapters, cache and real prices on the `adapter`, `fetch`, `lookup` skeleton; Epic 3 consumes the CI and gate commands (ADR 0014).
