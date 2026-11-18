---
status: draft
stepsCompleted: [1, 2, 3, 4]
author: Onofrei (PM), headless, run 20261117-174705-full, stage breakdown, step epics, round 1
inputDocuments:
  - _bmad-output/planning-artifacts/prds/prd-price-checker-2026-11-17/prd.md
  - _bmad-output/planning-artifacts/ux-designs/ux-price-checker-2026-11-17/DESIGN.md
  - _bmad-output/planning-artifacts/ux-designs/ux-price-checker-2026-11-17/EXPERIENCE.md
  - _bmad-output/planning-artifacts/architecture/architecture-price-checker-2026-11-17/ARCHITECTURE-SPINE.md
  - docs/source-register.md
---

# price-checker - Epic Breakdown

## Overview

This document breaks the PRD, the UX design contract (DESIGN.md + EXPERIENCE.md) and the architecture spine into epics and stories for Marghioala (Developer), Pacala (DevOps) and Eufrosina (QA).

Conventions:

- Each story has exactly one fenced `yaml` block (key, title, depends_on, touches, sources) that Zamolxe (Orchestrator) parses (spine AD-15 of the orchestrator). `depends_on` only names keys in this file. `sources` lists the retailer domains the story fetches from while it is built, exactly as in the Domain column of `docs/source-register.md`; a story whose sources are not Approved stays blocked.
- Story acceptance criteria are numbered `<story>.<n>` (for example `1.4.2`) and cite the PRD AC they verify in brackets (T-1: tests carry the PRD AC id where one is cited, otherwise the story AC id).
- Adapters are built and tested only from saved fixture pages (T-2, T-3, AC-9.4). Recording real pages and curating real product URLs is a separate story per retailer, with that retailer in `sources`, so it waits for the owner's approval (S-1).
- `[ASSUMPTION]` marks a breakdown choice made headless; all are listed in the final section.

## Requirements Inventory

### Functional Requirements

FR-1: The Shopper enters an EAN-8 or EAN-13; a Lookup starts only when it is valid (format and GS1 check digit), checked on the page and again on the server (400, no Retailer contacted). Enter or button submits; whitespace is trimmed. (AC-1.1 to 1.6)
FR-2: A Lookup queries every Approved retailer whose Kill switch is on, in parallel, one Result row per Approved retailer (Disabled included, none for others), per-Retailer timeout, at most one product-page fetch plus one resolution request per Retailer per EAN, retries capped at 2. (AC-2.1 to 2.4)
FR-3: Each Result row shows Retailer, Price in RON with VAT (`1.299,99 lei`), Availability, product link (new tab) and Fetch time (Europe/Bucharest); extraction order structured data → meta → selectors; non-RON price shown as unavailable; no product name, description or image kept anywhere. (AC-3.1 to 3.6)
FR-4: Every row tied for the Lowest price is highlighted with colour and the text "Lowest price"; never non-Found, unavailable-price or Out of stock rows; nothing when none is eligible. (AC-4.1 to 4.5)
FR-5: Failures stay in their row: `Not found` vs `Unavailable`; all-failed still renders the table with a message; never a 5xx for the Lookup; missing price shown as "unavailable", never 0 or blank. (AC-5.1 to 5.5)
FR-6: Result rows are cached in memory (15 min, configurable) keyed by Retailer and EAN; cached rows keep their original Fetch time; only `Found` and `Not found` are cached; no database. (AC-6.1 to 6.5)
FR-7: A per-Retailer Kill switch in a config file reloaded without deploy; off → `Disabled` and no requests within 60 s; on again within 60 s; cannot enable a non-Approved Retailer; unparseable file keeps last good settings. (AC-7.1 to 7.5)
FR-8: A scheduled (daily, and manual) Health check looks up each Retailer's Known EANs (≥ 2 each) and fails visibly (outside the VM) naming Retailer, EAN and missing fact; checks robots.txt; obeys Lookup rules; a simulated layout change fails both adapter tests and the Health check. (AC-8.1 to 8.7)
FR-9: Only Approved retailers are ever contacted; CI fails when an enabled Adapter's Retailer is not Approved; no flag or env var bypasses it; Adapters for non-Approved Retailers are built only from fixtures and ship disabled. (AC-9.1 to 9.4)
FR-10: Each Adapter declares a resolution method (search, catalog, curated) checked in CI; no robots-disallowed path; unresolvable EAN → `Not found`; every Known EAN resolves; search EANs are URL-encoded. (AC-10.1 to 10.5)
FR-11: On a Block (403, challenge page, robots.txt disallow) the system stops contacting that Retailer until an operator clears it, logs at WARN, fails the next Health check, never evades; 429 is not a Block: honor `Retry-After`. (AC-11.1 to 11.4)

### NonFunctional Requirements

NFR-1: Responsiveness — uncached Lookup renders within 5 s (per-Retailer deadline ~4 s); fully cached Lookup within 1 s at the server; loading state shown. (AC-N1.1 to N1.3)
NFR-2: Accuracy — never show a Price not extracted for that EAN; missing price is "unavailable" and never Lowest; EAN mismatch on page → `Not found`. (AC-N2.1 to N2.3)
NFR-3: Polite fetching — public pages only, logged out; no crawling; per-Retailer rate and concurrency limits from the register; `Retry-After` and capped exponential backoff; one honest user agent with contact URL and none of bot/robot/crawl/spider; no evasion; only S-7 facts kept; fixtures stripped; all enforced in one shared fetch client. (AC-N3.1 to N3.8; S-2 to S-7)
NFR-4: Accessibility — visible label, announced errors, keyboard-only journey with visible focus, WCAG 2.2 AA contrast, real table markup, axe with no serious or critical violations. (AC-N4.1 to N4.5)
NFR-5: Privacy — no accounts or identifying cookies; logs hold the EAN, never client IP or user agent; no personal data; no third-party analytics. (AC-N5.1 to N5.4; S-9, C-9)
NFR-6: Demo reliability — Known EANs include one EAN at two or more Approved retailers (tested); end-to-end test covers UJ-1 and UJ-2 against stubbed Retailers incl. a timeout and a cached repeat. (AC-N6.1, N6.2; T-4)
NFR-7: Transport and supply chain — HTTPS only, port 80 redirects; no committed secrets, CI secret scan; pinned dependencies and a CI vulnerability scan failing on high/critical. (AC-N7.1 to N7.3; S-10, S-12, S-13)

### Additional Requirements

From the architecture spine (ADRs 0006 to 0017):

- **No starter template.** Greenfield: Gradle (Kotlin DSL) Spring Boot 4.x on Java 21 under `backend/`; Vite + React + TypeScript under `web/`. The scaffold story pins exact GA versions after a web check, including a Spring Boot 4 + OpenAPI Generator compatibility spike, and updates the spine's Stack table (Deferred: Exact versions).
- AD-1: packages `api`, `ean`, `adapter`, `lookup`, `cache`, `registry`, `fetch`, `extract`, `health`, `retailer.<id>` under `ro.pricechecker`; ArchUnit enforces the dependency arrows and forbids any HTTP client outside `fetch`; a text-scan test forbids retailer domains and CSS selectors outside `retailer.<id>` and `config/`.
- AD-2: exactly one backend instance in production.
- AD-3: `api/openapi.yaml` is the only contract (`GET /api/lookup`, `GET /api/status`); Spring interfaces and the TypeScript client are generated in the build, never committed.
- AD-4: the server orders rows and sets `lowest`; the web app never sorts or compares prices.
- AD-5: wire format — `priceMinor` integer bani, UTC instants, problem+json errors with codes `EAN_EMPTY`, `EAN_NON_DIGIT`, `EAN_LENGTH`, `EAN_CHECK_DIGIT`.
- AD-6: shared fetch client — `RetailerHttp` bound to a retailer; six pre-request checks (approval and adapter enabled; fetching allowed and no Block; https on the register domain; robots.txt; `notBefore`; rate/concurrency permit); fixed honest user agent checked at start; retries only timeouts and 5xx, max 2; 429 → `notBefore`; 403/challenge → Block; outcome mapping table.
- AD-7: adapter contract — `RetailerAdapter.fetchAndExtract(Ean, URI, RetailerHttp)` → sealed `AdapterOutcome`; `PageFacts` only S-7 facts plus `eanOnPage`; shared `extract` helpers in order; startup fails unless `retailers.yaml` ids and adapter beans match one-to-one.
- AD-8: bundled copy of `docs/source-register.md` is the only compliance authority; register test (enabled ⇒ Approved, Approved ⇒ entry, resolution valid, limits not looser than register).
- AD-9: three config files with one writer each: `config/retailers.yaml`, `config/known-eans.yaml`, VM `runtime.yaml` (kill-switch workflow, polled every 15 s), VM `blocks.json` (BlockStore only); generation counters for clear-block.
- AD-10: row status precedence: DISABLED → Block (UNAVAILABLE) → not in known-eans (NOT_FOUND, no request) → cache → load + normalize.
- AD-11: one virtual thread per retailer per Lookup, `deadlineMs` default 4000, Caffeine async single-flight cache, TTL 15 min.
- AD-12: every retailer uses `resolution: curated`; `config/known-eans.yaml` is the curated map, the Health check list and the demo list.
- AD-13: one EAN rule implemented in `ean.Ean` and `web/src/ean.ts`, both tested against `api/ean-cases.json`.
- AD-14: `LookupLog` JSON lines with an allowlist of fields; Tomcat access log off; Caddy without access log or client IPs; no NSG flow logs.
- AD-15: one `prod` Azure Linux VM (EU), Docker Compose `proxy` (Caddy), `web`, `backend`; images on GHCR tagged by SHA; only GitHub Actions SSH (`deploy`, `kill-switch`); SSH restriction decided by Pacala (DevOps) in an ADR at stage 6.
- AD-16: Health check on a GitHub runner: backend in `health` profile, reads production `/api/status`, runner `runtime.yaml`, report + stripped pages, hand-off job calls `kill-switch` `runner-block`/`runner-retry-after`, opens `adapter-broken` issues; `fetch.PageStripper` strips fixtures.
- Gate commands for backend and web (ADR 0014) enter the `sdlc-commands` block in `AGENTS.md` only via a stage 9 PR (ADR 0004); no story here edits that block.

### UX Design Requirements

UX-DR1: Design tokens from DESIGN.md (colors, typography, rounded, spacing) implemented as plain CSS custom properties; system fonts only; no web fonts, icon fonts or third-party assets.
UX-DR2: Page layout: one centred column (max 880px, `xl` top padding) with header ("Price Checker" + one-line explanation), EAN form, status line, results table, footer note ("Prices include VAT. Times are Romanian local time. Only approved retailers are checked."); `lang="en"`.
UX-DR3: EAN form: `<form>` with `<label for>` "EAN (8 or 13 digits)", hint "Find it under the barcode on the box.", input `inputmode="numeric"`, `autocomplete="off"`, autofocused, monospace 20px, 48px tall; button "Check prices"; Enter submits; validation on submit only; trim only leading/trailing whitespace.
UX-DR4: Inline field error: red small text with leading "Error:", `aria-invalid`, `aria-describedby`, `role="alert"`; exact copy per error code (Voice and Tone); server 400 maps to the same copy; error clears when the input changes; focus stays in the input.
UX-DR5: Status line with `aria-live="polite"`: "Checking {k} retailers…" (or "Checking retailers…" when k unknown) with a spinner that stops under `prefers-reduced-motion`; then "Found prices at {f} of {k} retailers."
UX-DR6: Results table: `<table>`, `<caption>` "Prices for EAN {ean}", `<th scope="col">` Retailer · Price · Availability · Fetched · Link; rows rendered in server order; whole table appears at once.
UX-DR7: Found row: price `1.299,99 lei` via `Intl.NumberFormat('ro-RO')` in tabular numerals; "unavailable" when price missing; Availability text; Fetched "today, 14:05" or "17 Nov 2026, 14:05" in Europe/Bucharest with `<time datetime>`; link "View at {Retailer}" with `target="_blank" rel="noopener noreferrer"` and accessible name "(opens in a new tab)".
UX-DR8: Non-Found row: Row status text ("Not found", "Unavailable", "Disabled") in muted text in the Price cell; other cells "—" with visually hidden "not applicable"; never a blank cell; never red.
UX-DR9: Lowest price row: green surface + 4px left border + pill badge "Lowest price" in the Price cell, applied to every row the server marks `lowest`; green used nowhere else.
UX-DR10: Notice component: all-failed notice above the table ("No prices found for this EAN. …"), request-failed notice with no table ("Could not reach Price Checker. …").
UX-DR11: State machine: Idle, Invalid input (previous table stays), Loading (previous table hidden), Results, All failed, Request failed; a new submit replaces the running Lookup.
UX-DR12: Focus indicator: 2px primary outline, 2px offset on every focusable element; tab order input → button → row links; focus stays in the input after results.
UX-DR13: URL state: after a Lookup the URL becomes `/?ean=<digits>` (History API); loading `/?ean=…` fills the input and runs the Lookup if valid.
UX-DR14: Responsive: ≥ 600px form on one row; < 600px stacked with full-width button and the table inside a focusable scroll region (`role="region"`, `aria-label="Price results"`, `tabindex="0"`).
UX-DR15: Accessibility floor: axe reports no serious or critical violations in idle, error and results states; contrast per DESIGN.md tokens.

### FR Coverage Map

| Requirement | Epic | Stories |
|---|---|---|
| FR-1 | Epic 1 | 1.4, 1.7 |
| FR-2 | Epic 1, 2 | 1.6, 2.8 |
| FR-3 | Epic 1, 2 | 1.8, 2.1, 2.10, 2.11 |
| FR-4 | Epic 1 | 1.6, 1.8 |
| FR-5 | Epic 1, 2 | 1.6, 1.8, 2.8 |
| FR-6 | Epic 2 | 2.8 |
| FR-7 | Epic 2, 3 | 2.3, 3.4 |
| FR-8 | Epic 3, 4 | 3.5, 3.6, 4.2 |
| FR-9 | Epic 1, 2, 4 | 1.5, 2.4, 4.1 |
| FR-10 | Epic 1, 2 | 1.5, 2.8, 2.12, 2.13 |
| FR-11 | Epic 2, 3 | 2.5, 2.6, 2.7, 3.4, 3.6 |
| NFR-1 | Epic 1, 2 | 1.8, 2.8 |
| NFR-2 | Epic 2 | 2.1, 2.8 |
| NFR-3 | Epic 2 | 2.2, 2.4, 2.5, 2.6, 2.7 |
| NFR-4 | Epic 1 | 1.2, 1.7, 1.8 |
| NFR-5 | Epic 1, 3 | 1.4, 1.8, 3.2 |
| NFR-6 | Epic 2, 4 | 2.14, 4.3 |
| NFR-7 | Epic 1, 3 | 1.9, 3.2 |
| UX-DR1–15 | Epic 1 | 1.2, 1.7, 1.8 |

## Epic List

1. **Epic 1: Walking skeleton — EAN in, comparison table out.** A Shopper can open the page, enter an EAN, get it validated on both sides, and see the comparison table built from the retailer config and the source register (rows for Approved retailers only; no live fetching yet). Covers FR-1, FR-4, FR-5 (shape), FR-9 (register gate), FR-10 (declared resolution), NFR-4, NFR-5 (logs), NFR-7 (CI scans), all UX-DRs.
2. **Epic 2: Real prices, fetched politely.** The Lookup fetches each Approved retailer's product page through the one shared fetch client, extracts facts, caches them, respects Kill switch, robots.txt, rate limits and Blocks, and shows real Prices for cel.ro and evoMAG once the owner approves them. Covers FR-2, FR-3, FR-5, FR-6, FR-7 (app side), FR-10, FR-11 (app side), NFR-1, NFR-2, NFR-3, NFR-6 (e2e).
3. **Epic 3: Live on the VM and operable without SSH.** The site runs over HTTPS on the Azure VM, deploys on merge, and an operator can stop a retailer, clear a Block, and see a daily Health check fail visibly. Covers FR-7 (workflow), FR-8, FR-11 (hand-off), NFR-5 (proxy logs), NFR-7.
4. **Epic 4: Demo readiness and the compliance gate.** The presenter can show a disabled Altex Adapter waiting behind the gate, a simulated layout change caught by tests and the Health check, and a checked demo EAN list. Covers FR-8 (AC-8.4), FR-9 (AC-9.4), NFR-6 (AC-N6.1).

## Epic 1: Walking skeleton — EAN in, comparison table out

A Shopper can enter an EAN, see validation errors or the comparison table for Approved retailers, built from config and the register, end to end in the browser and in CI. No retailer is contacted in this epic.

### Story 1.1: Backend scaffold with enforced package structure

```yaml
key: 1-1-backend-scaffold
title: Backend scaffold with enforced package structure
depends_on: []
touches:
  - backend/
  - docs/adr/0014-backend-build-and-gate-commands.md
  - _bmad-output/planning-artifacts/architecture/architecture-price-checker-2026-11-17/ARCHITECTURE-SPINE.md
sources: []
```

As Marghioala (Developer),
I want a Spring Boot backend skeleton with the feature packages and architecture tests in place,
So that every later story lands in the right package and the dependency rules are enforced from day one.

**Acceptance Criteria:**

- **1.1.1** **Given** a clean checkout **When** the backend build runs with the Gradle wrapper **Then** it compiles, runs tests and Spotless checks with Java 21 and Spring Boot 4.x, all versions pinned (S-12), and the spine Stack table lists the exact versions chosen.
- **1.1.2** **Given** the packages `api`, `ean`, `adapter`, `lookup`, `cache`, `registry`, `fetch`, `extract`, `health` under `ro.pricechecker` **When** the ArchUnit test runs **Then** it allows only the arrows in the spine paradigm diagram and fails on any HTTP client use outside `fetch` (AD-1).
- **1.1.3** **Given** a retailer domain or CSS selector string placed in a class outside `retailer.<id>` **When** the text-scan test runs **Then** it fails (AD-1).
- **1.1.4** **Given** the app config **When** it starts **Then** Tomcat access logging is off and an injected `java.time.Clock` bean exists (AD-14, T-5).

### Story 1.2: Web scaffold with design tokens

```yaml
key: 1-2-web-scaffold
title: Web scaffold with design tokens
depends_on: []
touches:
  - web/
sources: []
```

As Marghioala (Developer),
I want a Vite + React + TypeScript app with lint, format, unit tests, axe and the DESIGN.md tokens,
So that the UI stories build on one consistent visual base.

**Acceptance Criteria:**

- **1.2.1** **Given** a clean checkout **When** the web build, ESLint, Prettier check and Vitest run **Then** they pass with pinned dependency versions on Node 24 LTS (S-12).
- **1.2.2** **Given** DESIGN.md tokens **When** the app loads **Then** they exist as CSS custom properties and the page uses system fonts only, loading no third-party asset (UX-DR1, AC-N5.4).
- **1.2.3** **Given** the page shell **When** rendered **Then** it shows the header, the footer note and `lang="en"` in an 880px centred column (UX-DR2), and an axe test in Vitest reports no serious or critical violations (AC-N4.5).

### Story 1.3: API contract and shared EAN cases

```yaml
key: 1-3-api-contract
title: API contract and shared EAN cases
depends_on:
  - 1-1-backend-scaffold
  - 1-2-web-scaffold
touches:
  - api/openapi.yaml
  - api/ean-cases.json
  - backend/build.gradle.kts
  - web/package.json
sources: []
```

As Marghioala (Developer),
I want `api/openapi.yaml` with both endpoints and generated server and client code,
So that backend and web cannot drift from one contract (AD-3, C-3).

**Acceptance Criteria:**

- **1.3.1** **Given** `api/openapi.yaml` **When** read **Then** it defines `GET /api/lookup?ean=` → `LookupResponse { ean, rows }` and `GET /api/status` → `StatusResponse` with exactly the fields of AD-3 and AD-5 (`priceMinor` integer, `fetchedAt` UTC instant, status and availability enums, `lowest`), and 400 as `application/problem+json` with the four `EAN_*` codes.
- **1.3.2** **Given** the backend and web builds **When** they run **Then** Spring interfaces and the TypeScript client are generated from the contract, and no generated file is committed.
- **1.3.3** **Given** `api/ean-cases.json` **When** read **Then** it lists valid EAN-8 and EAN-13 cases and one case per error code, including whitespace-padded input (AC-1.1 to 1.4).

### Story 1.4: Server-side EAN validation and lookup logging

```yaml
key: 1-4-server-ean-validation
title: Server-side EAN validation and lookup logging
depends_on:
  - 1-3-api-contract
touches:
  - backend/src/main/java/ro/pricechecker/ean/
  - backend/src/main/java/ro/pricechecker/api/
  - backend/src/main/java/ro/pricechecker/lookup/LookupLog.java
sources: []
```

As a Shopper,
I want the server to reject an invalid EAN with a clear code,
So that a bypassed page check never reaches a Retailer (S-11).

**Acceptance Criteria:**

- **1.4.1** **Given** every case in `api/ean-cases.json` **When** `ean.Ean` parses it **Then** the result matches the expected value or error code (AC-1.1 to 1.4, AD-13).
- **1.4.2** **Given** `GET /api/lookup?ean=<invalid>` **When** called **Then** the response is 400 problem+json with the matching `code` and no adapter or fetch call is made (AC-1.5).
- **1.4.3** **Given** a rejected input **When** it is logged **Then** `LookupLog` writes a JSON line with `event=ean_rejected` and the `code`, never the input; `LookupLog` accepts only the allowlisted fields (`ts`, `level`, `event`, `ean`, `retailer`, `outcome`, `durationMs`) and never logs client IP or user agent (AD-14, AC-N5.2).

### Story 1.5: Source register gate and retailer config

```yaml
key: 1-5-register-and-retailer-config
title: Source register gate and retailer config
depends_on:
  - 1-1-backend-scaffold
touches:
  - backend/src/main/java/ro/pricechecker/registry/
  - backend/build.gradle.kts
  - config/retailers.yaml
  - config/known-eans.yaml
sources: []
```

As the owner,
I want the backend to read Approved status only from the bundled source register and to fail CI when config disagrees with it,
So that no code path can contact a Retailer I have not approved (FR-9).

**Acceptance Criteria:**

- **1.5.1** **Given** the build **When** it packages the backend **Then** `docs/source-register.md` is copied into the artifact and one `registry` parser reads `Domain` and `Status`; only `Approved YYYY-MM-DD` approves; any parse error approves nothing (AD-8).
- **1.5.2** **Given** `config/retailers.yaml` entries for `cel.ro` and `evomag.ro` (`adapter: disabled`, `resolution: curated`, limits from the register, `deadlineMs: 4000`) **When** the register test runs **Then** it fails if an `adapter: enabled` entry is not Approved, an Approved retailer has no entry, `resolution` is missing or not in {search, catalog, curated}, or a rate limit or concurrency is looser than, or the register Limits value cannot be parsed for an entry that is `adapter: enabled` or Approved (ADR 0024) (AC-9.1, AC-10.1, AC-9.3).
- **1.5.3** **Given** `config/known-eans.yaml` (per retailer id, list of `{ ean, url }`) **When** loaded **Then** each EAN is valid, each URL is https on that retailer's domain, and each Approved retailer has at least two entries (AC-8.6) [ASSUMPTION: the file starts empty; the per-retailer check applies only to Approved retailers].
- **1.5.4** **Given** a test environment variable or flag that tries to mark a retailer Approved **When** the app starts **Then** Approved status is unchanged (AC-9.3).

### Story 1.6: Lookup skeleton with row order and lowest price

```yaml
key: 1-6-lookup-skeleton
title: Lookup skeleton with row order and lowest price
depends_on:
  - 1-4-server-ean-validation
  - 1-5-register-and-retailer-config
touches:
  - backend/src/main/java/ro/pricechecker/adapter/
  - backend/src/main/java/ro/pricechecker/lookup/
  - backend/src/main/java/ro/pricechecker/api/
sources: []
```

As a Shopper,
I want `GET /api/lookup` to return one row per Approved retailer in a fixed order with the Lowest price marked,
So that the page can show the table without deciding anything itself (AD-4).

**Acceptance Criteria:**

- **1.6.1** **Given** the port types `RetailerAdapter`, sealed `AdapterOutcome` (`Found`, `NotFound`, `Unavailable(UnavailableReason)`), `PageFacts` (only `priceMinor`, `currency`, `availability`, `url`, `eanOnPage`) and `RetailerHttp` in `adapter` **When** the app starts **Then** it fails unless `retailers.yaml` ids and adapter beans match one-to-one (AD-7) [ASSUMPTION: this story adds a no-op test adapter bean per configured retailer only in tests].
- **1.6.2** **Given** a test register with Approved and non-Approved retailers **When** a valid EAN is looked up **Then** there is exactly one row per Approved retailer, `DISABLED` for a disabled adapter, and no row for others (AC-2.1, AC-9.2).
- **1.6.3** **Given** rows with mixed statuses and prices **When** the response is built **Then** rows are ordered FOUND by price ascending, FOUND without price, NOT_FOUND, UNAVAILABLE, DISABLED, ties by `retailerName`, and `lowest: true` is set on every row tied for the lowest price among FOUND, price-present, not OUT_OF_STOCK rows, and on none when none is eligible (AC-4.2 to 4.5, AD-4).
- **1.6.4** **Given** any adapter outcome, including an exception **When** the Lookup runs **Then** the response is 2xx and non-FOUND rows carry only `retailerId`, `retailerName`, `status`, `lowest=false` (AC-5.4, AD-5).

### Story 1.7: EAN form with client-side validation and URL state

```yaml
key: 1-7-web-ean-form
title: EAN form with client-side validation and URL state
depends_on:
  - 1-3-api-contract
touches:
  - web/src/ean.ts
  - web/src/EanForm.tsx
  - web/src/App.tsx
sources: []
```

As a Shopper,
I want to paste an EAN and press Enter, and see a precise error if I made a typo,
So that I never wait for a Lookup that cannot work (UJ-1).

**Acceptance Criteria:**

- **1.7.1** **Given** every case in `api/ean-cases.json` **When** `web/src/ean.ts` validates it **Then** the result matches (AD-13, AC-1.1 to 1.4).
- **1.7.2** **Given** the form **When** rendered **Then** it matches UX-DR3 (label, hint, numeric input, autofocus, "Check prices") and submits with Enter or the button (AC-1.6).
- **1.7.3** **Given** an invalid input **When** submitted **Then** no request is made, the exact Voice-and-Tone copy appears under the input with `aria-invalid`, `aria-describedby` and `role="alert"`, focus stays in the input, and the error clears when the input changes (AC-1.2, AC-1.3, AC-N4.1, UX-DR4).
- **1.7.4** **Given** a server 400 problem+json **When** received **Then** the same copy as the client check for that `code` is shown (AD-5).
- **1.7.5** **Given** a valid submit **When** the Lookup starts **Then** the URL becomes `/?ean=<digits>`; **and given** the page is opened at `/?ean=<valid>` **Then** the input is filled and the Lookup runs (UX-DR13).

### Story 1.8: Results table, status line and notices

```yaml
key: 1-8-web-results-table
title: Results table, status line and notices
depends_on:
  - 1-7-web-ean-form
touches:
  - web/src/ResultsTable.tsx
  - web/src/StatusLine.tsx
  - web/src/Notice.tsx
  - web/src/format.ts
  - web/src/App.tsx
  - web/src/styles/
sources: []
```

As a Shopper,
I want one accessible table with each Retailer's facts and the cheapest row highlighted,
So that I can see at a glance where to buy (UJ-1, UJ-2).

**Acceptance Criteria:**

- **1.8.1** **Given** a `LookupResponse` **When** rendered **Then** the table has a caption, column headers with `scope="col"` and the rows in server order, never re-sorted (AC-N4.4, UX-DR6, AD-4).
- **1.8.2** **Given** a FOUND row **When** rendered **Then** it shows `1.299,99 lei` (or "unavailable" when `priceMinor` is absent), Availability text, Fetched in Europe/Bucharest time with `<time datetime>`, and "View at {Retailer}" opening in a new tab with `rel="noopener noreferrer"` (AC-3.1 to 3.3, AC-5.5, UX-DR7).
- **1.8.3** **Given** a non-FOUND row **When** rendered **Then** the Price cell shows "Not found", "Unavailable" or "Disabled" in muted text and other cells "—" with hidden "not applicable"; no cell is blank (AC-5.1, AC-5.2, UX-DR8).
- **1.8.4** **Given** rows with `lowest: true` **When** rendered **Then** each has the green row style and the "Lowest price" text badge; no other element uses green (AC-4.1, AC-4.2, UX-DR9).
- **1.8.5** **Given** the Lookup states **When** they occur **Then** the status line and notices follow UX-DR5, UX-DR10 and UX-DR11, including the all-failed notice with the full table (AC-5.3) and the loading copy (AC-N1.3), with the spinner static under `prefers-reduced-motion`.
- **1.8.6** **Given** keyboard-only use and a viewport under 600px **When** tested **Then** focus is visible on every focusable element, focus stays in the input after results, and the table scrolls inside a labelled focusable region (AC-N4.2, UX-DR12, UX-DR14); axe reports no serious or critical violations in idle, error and results states (AC-N4.5, AC-N4.3).

### Story 1.9: CI for backend and web with secret and vulnerability scans

```yaml
key: 1-9-ci-backend-web
title: CI for backend and web with secret and vulnerability scans
depends_on:
  - 1-1-backend-scaffold
  - 1-2-web-scaffold
touches:
  - .github/workflows/ci.yml
sources: []
```

As the owner,
I want every PR to build, lint and test backend and web and to scan for secrets and vulnerable dependencies,
So that a broken or unsafe change cannot merge (T-8, S-10, S-12).

**Acceptance Criteria:**

- **1.9.1** **Given** a PR **When** CI runs **Then** it runs the backend and web build, lint, format check and tests proposed in ADR 0014, alongside the existing orchestrator job, without network access to any retailer (T-3).
- **1.9.2** **Given** a committed secret in a test branch **When** CI runs **Then** the secret scan fails (AC-N7.2).
- **1.9.3** **Given** a dependency with a high or critical advisory **When** CI runs **Then** the vulnerability scan fails (AC-N7.3).

## Epic 2: Real prices, fetched politely

The Lookup fetches real product pages through one shared fetch client that enforces S-1 to S-7, extracts facts, caches them and contains every failure in its row. Live prices appear once the owner approves cel.ro and evoMAG.

### Story 2.1: Extraction helpers for structured data, meta tags and prices

```yaml
key: 2-1-extract-helpers
title: Extraction helpers for structured data, meta tags and prices
depends_on:
  - 1-1-backend-scaffold
touches:
  - backend/src/main/java/ro/pricechecker/extract/
sources: []
```

As Marghioala (Developer),
I want shared, string-only extraction helpers,
So that every Adapter extracts facts in the same order and never guesses a price (AD-7).

**Acceptance Criteria:**

- **2.1.1** **Given** HTML with JSON-LD `Product`/`Offer` **When** the structured-data helper runs **Then** it returns price, currency, availability and GTIN when present, and nothing else (AC-3.4, S-7).
- **2.1.2** **Given** HTML with only product meta tags **When** the meta helper runs **Then** it returns the same facts; the helpers are separate tested functions (AC-3.4).
- **2.1.3** **Given** price text such as `1.299,99 lei`, `1299.99`, empty or garbage **When** parsed **Then** it returns integer bani or `null`, never 0 for garbage (AC-5.5, AC-N2.2).
- **2.1.4** **Given** the `extract` package **When** ArchUnit runs **Then** it has no network access (AD-1).

### Story 2.2: Page stripper for fixtures and reports

```yaml
key: 2-2-page-stripper
title: Page stripper for fixtures and reports
depends_on:
  - 1-1-backend-scaffold
touches:
  - backend/src/main/java/ro/pricechecker/fetch/PageStripper.java
sources: []
```

As Veronel (Security & Compliance),
I want one function that strips a fetched page before it is saved anywhere,
So that fixtures and Health check reports keep no images or long descriptions (T-2, S-7).

**Acceptance Criteria:**

- **2.2.1** **Given** a page with scripts, JSON-LD product data, styles, images, `srcset`, iframes, comments and a long description **When** `PageStripper` runs **Then** only JSON-LD product data survives among scripts, the rest are removed, and description text is cut to 200 characters (AD-16).
- **2.2.2** **Given** the stripped output **When** passed to the 2.1 helpers **Then** the same price, currency and availability are extracted as from the original.

### Story 2.3: Runtime kill-switch config read by the backend

```yaml
key: 2-3-runtime-kill-switch
title: Runtime kill-switch config read by the backend
depends_on:
  - 1-6-lookup-skeleton
touches:
  - backend/src/main/java/ro/pricechecker/registry/
  - backend/src/main/java/ro/pricechecker/lookup/
sources: []
```

As an operator,
I want the backend to poll `runtime.yaml` and show a stopped retailer as `Disabled`,
So that I can turn a retailer off within a minute without a deploy (FR-7).

**Acceptance Criteria:**

- **2.3.1** **Given** `runtime.yaml` with `fetching: stopped` for a retailer **When** the next poll (every 15 s, injected Clock) runs **Then** that retailer's rows are `DISABLED` within 60 s and turning it back to `allowed` restores it within 60 s (AC-7.1 to 7.3).
- **2.3.2** **Given** a retailer missing from `runtime.yaml`, or no parseable file since start **When** a Lookup runs **Then** that retailer is stopped (AD-9).
- **2.3.3** **Given** an unparseable `runtime.yaml` after a good one **When** polled **Then** the last good settings stay in force and an error is logged (AC-7.5).
- **2.3.4** **Given** `fetching: allowed` for a non-Approved retailer **When** a Lookup runs **Then** it gets no row and no request (AC-7.4).

### Story 2.4: Shared fetch client with the approval gate and limits

```yaml
key: 2-4-fetch-client
title: Shared fetch client with the approval gate and limits
depends_on:
  - 1-5-register-and-retailer-config
  - 2-3-runtime-kill-switch
touches:
  - backend/src/main/java/ro/pricechecker/fetch/
sources: []
```

As Veronel (Security & Compliance),
I want every outbound request to go through one client that refuses anything the rules forbid,
So that polite fetching does not depend on each Adapter (AC-N3.8, AD-6).

**Acceptance Criteria:**

- **2.4.1** **Given** a `RetailerHttp` bound to a retailer **When** asked for a URL that is not https on the register domain or `www.` of it, or a redirect to another host **Then** it refuses without sending (AD-6 check 3).
- **2.4.2** **Given** a retailer that is not Approved, has its adapter disabled or has `fetching: stopped` **When** a request is made **Then** nothing is sent (AC-9.2, AC-9.3, AD-6 checks 1–2).
- **2.4.3** **Given** the configured rate limit and concurrency **When** requests would exceed them before the deadline **Then** the request is not sent and the outcome is `Unavailable` (AC-N3.3).
- **2.4.4** **Given** a stub server returning timeouts or 5xx **When** fetched **Then** the client retries at most 2 times with exponential backoff inside the deadline; 4xx other than 429 are not retried (AC-2.4, AC-N3.4).
- **2.4.5** **Given** startup **When** the user agent is built **Then** it contains the configured contact URL, and the app refuses to start if the URL is missing or the string contains `bot`, `robot`, `crawl` or `spider` in any case; requests carry no cookies and no login (AC-N3.1, AC-N3.5, AC-N3.6).
- **2.4.6** **Given** a 404 or 410 **When** fetched **Then** the outcome maps to `NotFound`; `fetchedAt` comes from the injected Clock (AD-6 outcome table).

### Story 2.5: robots.txt enforcement in the fetch client

```yaml
key: 2-5-robots-txt
title: robots.txt enforcement in the fetch client
depends_on:
  - 2-4-fetch-client
touches:
  - backend/src/main/java/ro/pricechecker/fetch/
sources: []
```

As Veronel (Security & Compliance),
I want the client to check robots.txt before every request,
So that no Adapter requests a disallowed path (S-4).

**Acceptance Criteria:**

- **2.5.1** **Given** a stub robots.txt disallowing a path for our user agent **When** that path is requested **Then** nothing is sent to it and the outcome is a Block candidate (AC-10.2, AC-8.7).
- **2.5.2** **Given** concurrent first requests **When** robots.txt is needed **Then** it is loaded once (single-flight), cached 24 h, and its fetch takes a rate-limit permit (AD-6).
- **2.5.3** **Given** a robots.txt answering 5xx or unparseable **When** a request is checked **Then** everything is disallowed (RFC 9309).

### Story 2.6: BlockStore and Block handling

```yaml
key: 2-6-block-store
title: BlockStore and Block handling
depends_on:
  - 2-5-robots-txt
touches:
  - backend/src/main/java/ro/pricechecker/registry/BlockStore.java
  - backend/src/main/java/ro/pricechecker/fetch/
sources: []
```

As an operator,
I want a Block to stop all traffic to that retailer and survive restarts until I clear it,
So that we never keep hitting a retailer that refused us (FR-11, S-6).

**Acceptance Criteria:**

- **2.6.1** **Given** a 403, a recognized challenge page or a robots.txt disallow **When** fetched **Then** `BlockStore.record` writes `blocks.json` atomically, the request is not retried, and a WARN log line names the retailer and time (AC-11.1, AC-11.2, AC-11.3).
- **2.6.2** **Given** an active Block (`blockGeneration >= clearGeneration` or a `runnerBlock` in `runtime.yaml`) **When** any request is made **Then** nothing is sent and `isBlocked` is true; after a restart the Block is still active (AD-9).
- **2.6.3** **Given** a `clearGeneration` increment in `runtime.yaml` **When** polled **Then** the Block ends; **and given** a new Block recorded before that poll **Then** it is not cancelled (AD-9 generation rule).
- **2.6.4** **Given** the codebase **When** searched **Then** no code path retries through another IP, proxy, user agent or altered headers (AC-11.3, AC-N3.6).

### Story 2.7: 429 and Retry-After handling

```yaml
key: 2-7-retry-after
title: 429 and Retry-After handling
depends_on:
  - 2-6-block-store
touches:
  - backend/src/main/java/ro/pricechecker/fetch/
  - backend/src/main/java/ro/pricechecker/registry/BlockStore.java
sources: []
```

As Veronel (Security & Compliance),
I want the client to honor `Retry-After` across Lookups and restarts,
So that a rate-limited retailer gets the pause it asked for (AC-11.4, S-5).

**Acceptance Criteria:**

- **2.7.1** **Given** a 429 with `Retry-After` as delta-seconds, as HTTP-date, or missing (→ 60 s) **When** received **Then** `BlockStore.recordRetryAfter` sets `notBefore`, which never moves earlier, and the 429 is not a Block (AC-11.4).
- **2.7.2** **Given** `notBefore` inside the request deadline **When** handled **Then** the client waits and retries once; otherwise the outcome is `Unavailable` (AC-N3.4).
- **2.7.3** **Given** a long `Retry-After` **When** a second Lookup runs before it passes **Then** nothing is sent and the row is `UNAVAILABLE` and not cached; `notBefore` from `runtime.yaml` is honored too (AD-6 check 5).

### Story 2.8: Parallel Lookup execution, normalization and cache

```yaml
key: 2-8-lookup-execution-cache
title: Parallel Lookup execution, normalization and cache
depends_on:
  - 2-6-block-store
  - 2-1-extract-helpers
touches:
  - backend/src/main/java/ro/pricechecker/lookup/
  - backend/src/main/java/ro/pricechecker/cache/
sources: []
```

As a Shopper,
I want all retailers queried at once, with repeated Lookups answered from a short cache,
So that I get the table within 5 seconds and the presenter's repeat is instant (FR-2, FR-6, NFR-1).

**Acceptance Criteria:**

- **2.8.1** **Given** two stub adapters each taking 2 s **When** a Lookup runs **Then** it completes in under 3 s, one virtual thread per retailer (AC-2.2).
- **2.8.2** **Given** an adapter exceeding its `deadlineMs` (default 4000) **When** a Lookup runs **Then** only that row is `UNAVAILABLE` and the response renders within 5 s (AC-2.3, AC-N1.1).
- **2.8.3** **Given** the AD-10 precedence **When** each retailer is processed **Then** DISABLED, then active Block → UNAVAILABLE, then EAN not in `known-eans.yaml` → NOT_FOUND with no request, then cache, then load; the curated URL from `known-eans.yaml` is passed to the adapter (AC-10.3, AD-12).
- **2.8.4** **Given** an outcome with `eanOnPage` different from the request **When** normalized **Then** the row is NOT_FOUND; non-RON or null price → FOUND without price (AC-N2.3, AC-3.5, AC-N2.1).
- **2.8.5** **Given** a FOUND or NOT_FOUND verdict **When** the same EAN is looked up within the TTL (15 min, configurable) **Then** no adapter call is made, `fetchedAt` is the original, and the cached Lookup returns within 1 s; UNAVAILABLE is not cached; after the TTL it fetches again; concurrent misses share one load (AC-6.1 to 6.5, AC-N1.2, AC-2.4).
- **2.8.6** **Given** a cached entry **When** inspected **Then** it holds only status, `priceMinor`, currency/availability, `url` and `fetchedAt`; each Lookup logs one line per retailer with only allowlisted fields (AC-3.6, AC-N3.7).

### Story 2.9: Status endpoint

```yaml
key: 2-9-status-endpoint
title: Status endpoint
depends_on:
  - 2-7-retry-after
touches:
  - backend/src/main/java/ro/pricechecker/api/
sources: []
```

As Pacala (DevOps),
I want `GET /api/status` to report per Approved retailer whether it is enabled, blocked and waiting,
So that the Health check can skip blocked retailers and copy `notBefore` (AD-3, AD-16).

**Acceptance Criteria:**

- **2.9.1** **Given** Approved retailers in various states **When** `/api/status` is called **Then** each has `retailerId`, `retailerName`, `enabled`, `blocked` and optional `notBefore`, no prices, and no non-Approved retailer appears.
- **2.9.2** **Given** an active Block or a future `notBefore` **When** called **Then** `blocked` and `notBefore` match `BlockStore` (AD-6, AD-9).

### Story 2.10: cel.ro Adapter from synthetic fixtures

```yaml
key: 2-10-adapter-celro
title: cel.ro Adapter from synthetic fixtures
depends_on:
  - 1-6-lookup-skeleton
  - 2-1-extract-helpers
touches:
  - backend/src/main/java/ro/pricechecker/retailer/celro/
  - fixtures/cel.ro/
sources: []
```

As a Shopper,
I want cel.ro product pages turned into price, availability and link,
So that cel.ro can appear in the table once it is Approved.

**Acceptance Criteria:**

- **2.10.1** **Given** hand-written fixture pages under `fixtures/cel.ro/` modelled on the register's notes (JSON-LD Product and Offer) [ASSUMPTION: synthetic until 2.12 records real ones] **When** `fetchAndExtract` runs **Then** it returns `Found(PageFacts)` with price in bani, currency, availability and URL, using structured data → meta → own selectors (AC-3.4, AD-7).
- **2.10.2** **Given** a fixture with no recognizable product facts **When** extracted **Then** the outcome is `Unavailable(UNPARSEABLE)`; with an unparseable price **Then** `priceMinor` is null (AC-5.5).
- **2.10.3** **Given** the adapter tests **When** they run **Then** they read only `fixtures/cel.ro/` and make no network call (T-2, T-3); `retailers.yaml` keeps `adapter: disabled` (AC-9.4).

### Story 2.11: evoMAG Adapter from synthetic fixtures

```yaml
key: 2-11-adapter-evomagro
title: evoMAG Adapter from synthetic fixtures
depends_on:
  - 1-6-lookup-skeleton
  - 2-1-extract-helpers
touches:
  - backend/src/main/java/ro/pricechecker/retailer/evomagro/
  - fixtures/evomag.ro/
sources: []
```

As a Shopper,
I want evoMAG product pages turned into price, availability and link,
So that evoMAG can appear in the table once it is Approved.

**Acceptance Criteria:**

- **2.11.1** **Given** hand-written fixture pages under `fixtures/evomag.ro/` [ASSUMPTION: synthetic until 2.13 records real ones] **When** `fetchAndExtract` runs **Then** it returns `Found(PageFacts)` using structured data → meta → own selectors (AC-3.4, AD-7).
- **2.11.2** **Given** a fixture with no recognizable product facts **When** extracted **Then** the outcome is `Unavailable(UNPARSEABLE)`; an unparseable price gives `priceMinor` null (AC-5.5).
- **2.11.3** **Given** the adapter **When** inspected **Then** it requests only the curated product URL and never a search page (AC-10.2); tests read only `fixtures/evomag.ro/` (T-2, T-3); `adapter: disabled` (AC-9.4).

### Story 2.12: Record cel.ro fixtures, curate Known EANs and enable the Adapter

```yaml
key: 2-12-celro-live-enable
title: Record cel.ro fixtures, curate Known EANs and enable the Adapter
depends_on:
  - 2-2-page-stripper
  - 2-7-retry-after
  - 2-8-lookup-execution-cache
  - 2-10-adapter-celro
touches:
  - fixtures/cel.ro/
  - config/known-eans.yaml
  - config/retailers.yaml
  - backend/src/main/java/ro/pricechecker/retailer/celro/
sources:
  - cel.ro
```

As a Shopper,
I want real cel.ro prices for the Known EANs,
So that the live demo shows a real comparison (RC-2).

**Acceptance Criteria:**

- **2.12.1** **Given** cel.ro is Approved in the source register **When** real product pages are fetched through the shared fetch client and stripped by `PageStripper` **Then** they replace the synthetic fixtures and the adapter tests pass against them (T-2, AC-N3.7).
- **2.12.2** **Given** `config/known-eans.yaml` **When** updated **Then** cel.ro has at least two `{ ean, url }` entries, each resolving to a FOUND page with price, availability and link (AC-8.6, AC-10.4).
- **2.12.3** **Given** `retailers.yaml` sets cel.ro to `adapter: enabled` **When** the register test runs **Then** it passes only because cel.ro is Approved (AC-9.1).

### Story 2.13: Record evoMAG fixtures, curate Known EANs and enable the Adapter

```yaml
key: 2-13-evomagro-live-enable
title: Record evoMAG fixtures, curate Known EANs and enable the Adapter
depends_on:
  - 2-2-page-stripper
  - 2-7-retry-after
  - 2-8-lookup-execution-cache
  - 2-11-adapter-evomagro
touches:
  - fixtures/evomag.ro/
  - config/known-eans.yaml
  - config/retailers.yaml
  - backend/src/main/java/ro/pricechecker/retailer/evomagro/
sources:
  - evomag.ro
```

As a Shopper,
I want real evoMAG prices for the Known EANs,
So that the live demo compares at least two retailers (RC-2).

**Acceptance Criteria:**

- **2.13.1** **Given** evoMAG is Approved **When** real product pages are fetched through the shared fetch client and stripped **Then** they replace the synthetic fixtures and the adapter tests pass (T-2, AC-N3.7).
- **2.13.2** **Given** `config/known-eans.yaml` **When** updated **Then** evoMAG has at least two entries resolving to FOUND pages, found without any search request (AC-8.6, AC-10.2, AC-10.4).
- **2.13.3** **Given** `adapter: enabled` for evoMAG **When** the register test runs **Then** it passes only because evoMAG is Approved (AC-9.1).

### Story 2.14: End-to-end test of UJ-1 and UJ-2 against stubbed retailers

```yaml
key: 2-14-e2e-uj1-uj2
title: End-to-end test of UJ-1 and UJ-2 against stubbed retailers
depends_on:
  - 1-8-web-results-table
  - 2-8-lookup-execution-cache
  - 2-10-adapter-celro
  - 2-11-adapter-evomagro
touches:
  - web/e2e/
  - backend/src/test/
  - .github/workflows/ci.yml
sources: []
```

As the presenter,
I want an automated run of the two demo journeys against stub retailers,
So that I know the demo works before I go on stage (NFR-6, T-4).

**Acceptance Criteria:**

- **2.14.1** **Given** the backend in a test profile with a test register approving two stub retailers served from fixtures **When** Playwright types a valid EAN with trailing whitespace and presses Enter **Then** the table shows both rows, the cheapest marked "Lowest price" (AC-N6.2, UJ-1).
- **2.14.2** **Given** one stub retailer timing out **When** the Lookup runs **Then** that row reads "Unavailable", the other is Lowest, and the status line says "Found prices at 1 of 2 retailers." (AC-N6.2, UJ-2).
- **2.14.3** **Given** the same EAN looked up again **When** the table returns **Then** the Fetched time is unchanged and the stub saw no second request (AC-6.1, AC-6.2).
- **2.14.4** **Given** CI **When** it runs **Then** this e2e suite runs with no network access to real retailers (T-3).

## Epic 3: Live on the VM and operable without SSH

The site runs on the Azure VM over HTTPS, deploys from `main` through GitHub Actions only, and the operator controls retailers and sees Health check failures without logging in to the VM. Infra stories are owned by Pacala (DevOps); `terraform apply` is an owner action.

### Story 3.1: Terraform for the production VM

```yaml
key: 3-1-terraform-vm
title: Terraform for the production VM
depends_on: []
touches:
  - infra/
  - .github/workflows/terraform-plan.yml
  - docs/adr/
  - docs/adr/README.md
sources: []
```

As Pacala (DevOps),
I want the VM, network and DNS defined in Terraform with a plan in CI,
So that production is reproducible and changed only by reviewed code (I-1 to I-9).

**Acceptance Criteria:**

- **3.1.1** **Given** `infra/` **When** `terraform plan` runs in CI **Then** it plans one Linux VM in an EU region, a DNS label Caddy can get a certificate for, and an NSG allowing 80 and 443 and SSH only as the stage-6 ADR decides (AD-15).
- **3.1.2** **Given** the plan **When** reviewed **Then** no NSG flow logs or network diagnostics that could hold client IPs are enabled (AD-14).
- **3.1.3** **Given** the SSH restriction choice **When** made **Then** it is recorded as a new ADR and indexed in `docs/adr/README.md` (D-2).

### Story 3.2: Container images, Compose and Caddy

```yaml
key: 3-2-containers-compose-caddy
title: Container images, Compose and Caddy
depends_on:
  - 1-8-web-results-table
  - 2-8-lookup-execution-cache
touches:
  - backend/Dockerfile
  - web/Dockerfile
  - deploy/compose.yaml
  - deploy/Caddyfile
sources: []
```

As a Shopper,
I want the site served only over HTTPS from one origin,
So that my connection is safe and nothing logs my IP (NFR-5, NFR-7).

**Acceptance Criteria:**

- **3.2.1** **Given** `deploy/compose.yaml` **When** started **Then** it runs `proxy` (Caddy 2), `web` and exactly one `backend`, with `runtime.yaml` and `blocks.json` on VM volumes and base images pinned by digest or exact tag (AD-2, AD-9, S-12).
- **3.2.2** **Given** the Caddyfile **When** a request arrives on port 80 **Then** it redirects to HTTPS; `/api/*` goes to the backend and everything else to `web` (AC-N7.1).
- **3.2.3** **Given** Caddy's config **When** inspected by a test **Then** there is no access log and its logs drop `remote_ip`, headers and URI (AC-N5.2, AD-14).

### Story 3.3: Image build and deploy workflow

```yaml
key: 3-3-deploy-workflow
title: Image build and deploy workflow
depends_on:
  - 1-9-ci-backend-web
  - 3-1-terraform-vm
  - 3-2-containers-compose-caddy
touches:
  - .github/workflows/deploy.yml
  - deploy/scripts/
sources: []
```

As the owner,
I want a merge to `main` to build images, push them to GHCR and deploy them,
So that production always runs reviewed code with no hand changes (I-1, S-10).

**Acceptance Criteria:**

- **3.3.1** **Given** a merge to `main` **When** the workflow runs **Then** images tagged with the git SHA are pushed to GHCR and pulled on the VM with the short-lived `GITHUB_TOKEN`, then logged out (AD-15).
- **3.3.2** **Given** the deploy script **When** it runs **Then** it never writes `runtime.yaml` or `blocks.json` (AD-9).
- **3.3.3** **Given** the workflow **When** inspected **Then** no secret appears in the repo or in logs (AC-N7.2).

### Story 3.4: Kill-switch workflow

```yaml
key: 3-4-kill-switch-workflow
title: Kill-switch workflow
depends_on:
  - 2-7-retry-after
  - 3-3-deploy-workflow
touches:
  - .github/workflows/kill-switch.yml
  - deploy/scripts/
sources: []
```

As an operator,
I want to stop, allow or clear a Block for a retailer from GitHub Actions,
So that I control retailers without SSH (FR-7, FR-11, S-8).

**Acceptance Criteria:**

- **3.4.1** **Given** `workflow_dispatch` with a retailer and `allow`, `stop` or `clear-block` **When** run **Then** the script, holding `flock`, changes only that retailer's field in `runtime.yaml` via temp file and `mv`, keeps every other field, and re-reads to verify (AC-7.1, AD-9).
- **3.4.2** **Given** `clear-block` **When** run **Then** `clearGeneration` increments and `runnerBlock` is removed (AC-11.1).
- **3.4.3** **Given** `workflow_call` with `runner-block` (reason) or `runner-retry-after` (time) **When** run **Then** `runnerBlock` or `notBefore` is set for that retailer (AD-16).
- **3.4.4** **Given** two concurrent actions in the script tests **When** they run **Then** both changes survive (AD-9).

### Story 3.5: Backend health profile

```yaml
key: 3-5-health-profile
title: Backend health profile
depends_on:
  - 2-2-page-stripper
  - 2-8-lookup-execution-cache
touches:
  - backend/src/main/java/ro/pricechecker/health/
sources: []
```

As the presenter,
I want the backend to run a Health check of every Known EAN with the same rules as a Lookup,
So that a broken Adapter is caught before the audience sees it (FR-8, UJ-4).

**Acceptance Criteria:**

- **3.5.1** **Given** the `health` profile **When** started **Then** it runs no web server, uses no cache (story 3.5: `lookup.KnownEanLoad` loads without `ResultCache`) and a fresh `blocks.json`, doubles each rate interval and uses concurrency 1, and contacts only Approved retailers allowed by its `runtime.yaml` (AC-8.5, AD-16).
- **3.5.2** **Given** stub retailers **When** it runs **Then** it checks every Known EAN is FOUND with price, availability and URL, and robots.txt allows every adapter path (AC-8.2, AC-8.7).
- **3.5.3** **Given** any failure **When** the run ends **Then** it exits non-zero and writes a report naming retailer, EAN, missing fact and `blocked` flag, plus the page stripped by `PageStripper` (AC-8.3, T-2).

### Story 3.6: Health-check workflow and hand-off

```yaml
key: 3-6-health-check-workflow
title: Health-check workflow and hand-off
depends_on:
  - 2-9-status-endpoint
  - 3-4-kill-switch-workflow
  - 3-5-health-profile
touches:
  - .github/workflows/health-check.yml
sources: []
```

As the owner,
I want a daily Health check on a GitHub runner whose failure I can see without the VM,
So that layout changes and Blocks surface as issues (FR-8, FR-11).

**Acceptance Criteria:**

- **3.6.1** **Given** the workflow **When** triggered by cron (04:00 Europe/Bucharest) or by hand **Then** it reads production `/api/status` with `curl` and fails if unreachable (AC-8.1).
- **3.6.2** **Given** a blocked retailer or an open `adapter-broken` issue labelled `blocked` **When** it runs **Then** that retailer fails without fetching; others run with `notBefore` copied and are reported as skipped while waiting (AD-16, AC-11.2).
- **3.6.3** **Given** the runner's `blocks.json` with a Block or a future `notBefore` **When** the hand-off job (`needs`, `if: always()`) runs **Then** it calls `kill-switch` with `runner-block` or `runner-retry-after` (AC-11.1, AC-11.4).
- **3.6.4** **Given** a failing retailer **When** the run ends **Then** one `adapter-broken` issue per retailer is opened or updated, labelled `blocked` for a Block, the job fails visibly, and a missing or unparseable report or `blocks.json` also fails and opens an issue (AC-8.3).

## Epic 4: Demo readiness and the compliance gate

The presenter can show the compliance gate with a disabled Altex Adapter, a simulated layout change caught by tests and the Health check, and a verified demo EAN list.

### Story 4.1: Disabled Altex Adapter from synthetic fixtures

```yaml
key: 4-1-adapter-altexro-disabled
title: Disabled Altex Adapter from synthetic fixtures
depends_on:
  - 1-6-lookup-skeleton
  - 2-1-extract-helpers
touches:
  - backend/src/main/java/ro/pricechecker/retailer/altexro/
  - fixtures/altex.ro/
  - config/retailers.yaml
sources: []
```

As the presenter,
I want an Altex Adapter that exists, is tested offline and ships disabled,
So that I can show it waiting behind the compliance gate (UJ-3, SM-2).

**Acceptance Criteria:**

- **4.1.1** **Given** hand-written fixture pages under `fixtures/altex.ro/` (no page is fetched from altex.ro) **When** the adapter tests run **Then** extraction works in the AD-7 order and no network call is made (AC-9.4, T-2, T-3).
- **4.1.2** **Given** `retailers.yaml` with Altex `adapter: disabled` **When** a Lookup runs **Then** Altex has no row while it is Not checked (AC-9.2).
- **4.1.3** **Given** a test copy of `retailers.yaml` with Altex `adapter: enabled` **When** the register test runs **Then** it fails (AC-9.1, AC-9.4).

### Story 4.2: Simulated layout change fails tests and the Health check

```yaml
key: 4-2-simulated-layout-change
title: Simulated layout change fails tests and the Health check
depends_on:
  - 2-10-adapter-celro
  - 2-11-adapter-evomagro
  - 3-5-health-profile
touches:
  - fixtures/
  - backend/src/test/
sources: []
```

As the presenter,
I want a saved product page with its structured data and selectors altered,
So that I can show the failure that starts the self-healing story (UJ-4, SM-3).

**Acceptance Criteria:**

- **4.2.1** **Given** an altered fixture per enabled-capable Adapter **When** the adapter test runs against it **Then** it fails with a message naming the retailer and the missing fact (AC-8.4).
- **4.2.2** **Given** the health profile pointed at a stub serving the altered page **When** it runs **Then** it exits non-zero with a report naming the retailer, the EAN and the missing fact (AC-8.4, AC-8.3).
- **4.2.3** **Given** normal CI **When** it runs **Then** the altered fixtures are used only by tests that expect failure, so CI stays green [ASSUMPTION].

### Story 4.3: Demo EAN list check

```yaml
key: 4-3-demo-ean-list-check
title: Demo EAN list check
depends_on:
  - 2-12-celro-live-enable
  - 2-13-evomagro-live-enable
touches:
  - backend/src/test/
  - config/known-eans.yaml
sources: []
```

As the presenter,
I want a test that proves the demo EAN list compares at least two retailers,
So that the live demo always has an EAN that both stock (NFR-6).

**Acceptance Criteria:**

- **4.3.1** **Given** `config/known-eans.yaml` **When** the test runs **Then** at least one EAN is listed for two or more Approved retailers (AC-N6.1).
- **4.3.2** **Given** the list **When** the test runs **Then** every Approved retailer has at least two Known EANs (AC-8.6).

## Validation Notes

- **Coverage:** every FR-1 to FR-11 and NFR-1 to NFR-7 AC maps to at least one story AC (FR Coverage Map); RC-1 to RC-5 are owner checks, not stories (PRD §7.1).
- **Starter template:** none in the architecture; stories 1.1 and 1.2 scaffold from scratch.
- **Dependencies:** every `depends_on` key exists in this file and points to an earlier story; no story depends on a later story in its own epic. Stories with empty `depends_on` (1.1, 1.2, 3.1) can start in parallel.
- **Contract changes:** only story 1.3 changes `api/openapi.yaml`; all API stories depend on it.
- **Sources:** only 2.12 (`cel.ro`) and 2.13 (`evomag.ro`) fetch from a retailer; both are `Proposed` today, so both stay blocked until the owner approves them (S-1). Story 4.3 depends on them and waits too. No story fetches from `altex.ro`.

## Assumptions

- Headless run: the step menus were not shown; the extracted requirements, epic list and stories were accepted as drafted.
- Adapters are built first from hand-written (synthetic) fixtures so they need no retailer contact; recording real fixtures and curating Known EAN URLs is a separate story per retailer with that domain in `sources`.
- `config/known-eans.yaml` starts empty; the "≥ 2 per Approved retailer" check (AC-8.6) applies only to Approved retailers, so it passes while none is Approved.
- Story 1.6 introduces the adapter port types; tests use stub adapter beans so the one-to-one startup check can pass before real adapters exist.
- The runtime kill-switch reader (2.3), Blocks (2.6) and Retry-After (2.7) are in Epic 2 because the fetch client must enforce them before any live fetch; Epic 3 adds only the workflows that write `runtime.yaml`.
- Altered (layout-change) fixtures are used only by tests that expect failure, so CI stays green.
- Backend and web gate commands are not added to the `AGENTS.md` `sdlc-commands` block by any story; that is a stage 9 PR (ADR 0004).
