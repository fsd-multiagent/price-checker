---
title: "Price Checker — Experience"
status: final
created: 2026-11-17
updated: 2026-11-17
author: Safta (UX Designer), headless, run 20261117-174705-full, stage design, step ux, round 1
design: DESIGN.md
sources:
  - _bmad-output/planning-artifacts/prds/prd-price-checker-2026-11-17/prd.md
  - docs/standards/coding.md (C-W2, C-W3)
---

# Price Checker — Experience

Terms (EAN, Approved retailer, Lookup, Result row, Row status, Price, Availability, Fetch time, Lowest price, Cache, Kill switch) are used exactly as defined in the PRD §3 Glossary. Visual tokens are referenced as `{path.to.token}` from DESIGN.md. Where this file and any mock disagree, this file and DESIGN.md win.

## Foundation

- **Form factor:** responsive web page, desktop/laptop first (UJ-1: Ioana on her laptop; UJ-2/UJ-3: presenter on a projector). Must still work on a phone browser, but no mobile app (PRD §6).
- **UI system:** none assumed [ASSUMPTION]; plain semantic HTML in React. Framework and tooling are Loredan's (Architect) call (C-W4). DESIGN.md is the visual identity reference.
- **Language:** UI copy in English [ASSUMPTION], matching the PRD's terms ("Lowest price", "Unavailable"); numbers, currency and time in Romanian format (`1.299,99 lei`, Europe/Bucharest time, AC-3.3).
- **No accounts, no cookies that identify a user, no analytics** (NFR-5).

## Information Architecture

One page, one route (`/`). No navigation, no other screens.

| Region | Purpose | Requirements |
|---|---|---|
| Header | Title "Price Checker" and one line: "Enter a product's EAN to compare prices at approved Romanian retailers." | — |
| EAN form | Label, input, "Check prices" button, inline error | FR-1, AC-N4.1 |
| Status line | Loading / result summary, announced to screen readers | AC-N1.3, C-W2 |
| Results table | One Result row per Approved retailer (including Disabled ones) | FR-2, FR-3, FR-4, FR-5 |
| Footer note | "Prices include VAT. Times are Romanian local time. Only approved retailers are checked." | AC-3.1, AC-3.3, FR-9 |

**Surface closure:** every PRD shopper need (enter EAN, see per-retailer facts, see cheapest, follow link, see failures) lands on this page. UJ-3's source register and config file, and UJ-4's Health check output, are not app screens: they are the repo file, the config file and the CI/job status (AC-8.3). The app has no admin UI [ASSUMPTION]; the only UJ-3 effect visible in the app is the `Disabled` row and the absence of non-Approved retailers.

**URL state:** after a Lookup the URL becomes `/?ean=<digits>` so the presenter can reload or share it; loading `/?ean=…` fills the input and runs the Lookup if valid; an invalid value only fills the input, with no error until the Shopper submits [ASSUMPTION]. The URL is replaced, not pushed, so Back leaves the page (story 1.7).

## Voice and Tone

Short, literal, no exclamation marks, no blame. Say what happened and what to do.

| Situation | Copy |
|---|---|
| Input label | "EAN (8 or 13 digits)" |
| Input hint (below, muted) | "Find it under the barcode on the box." |
| Button | "Check prices" |
| Empty input submitted | "Error: enter an EAN." |
| Non-digits (AC-1.2) | "Error: an EAN contains only digits." |
| Wrong length (AC-1.2) | "Error: an EAN has 8 or 13 digits. You entered {n}." |
| Bad check digit (AC-1.3) | "Error: the last digit does not match. Check the EAN for a typo." |
| Server 400 (AC-1.5) | Same text as the matching client error. |
| Loading (AC-N1.3) | "Checking {k} retailers…" |
| Done, some prices | "Found prices at {f} of {k} retailers." |
| Done, no prices (AC-5.3) | Notice: "No prices found for this EAN. Retailers that could not be reached are marked Unavailable; try again in a minute." |
| Lookup request itself failed (network) | Notice: "Could not reach Price Checker. Check your connection and try again." |
| Row: `Not found` | "Not found" |
| Row: `Unavailable` | "Unavailable" |
| Row: `Disabled` | "Disabled" |
| Found, price missing/unparseable/non-RON (AC-5.5, AC-3.5) | "unavailable" in the Price cell |
| Availability | "In stock" / "Out of stock" / "Unknown" |
| Fetched | "17 Nov 2026, 14:05" when not today, "today, 14:05" when today [ASSUMPTION]; full timestamp in a `<time datetime>` attribute |
| Link | "View at {Retailer}" (opens in new tab; accessible name adds "(opens in a new tab)") |
| Lowest badge | "Lowest price" |

## Component Patterns

Visual specs are in DESIGN.md Components; this is behavior only.

- **EAN form** (`{components.ean-input}`, `{components.button-primary}`): `<form>` with `<label for>`; input `inputmode="numeric"`, `autocomplete="off"`, autofocused on page load. Submit by Enter or button (AC-1.6). Trim leading/trailing whitespace before validating (AC-1.4); do not strip inner spaces or dashes. Validation runs on submit only, not while typing. Invalid: no request, `aria-invalid="true"`, error linked with `aria-describedby` and announced (AC-N4.1, C-W2), focus stays in the input. The error clears when the input changes. The button stays enabled during a Lookup; submitting again replaces the current Lookup.
- **Results table** (`{components.results-table}`): real `<table>` with `<caption>` "Prices for EAN {ean}" and `<th scope="col">` headers Retailer · Price · Availability · Fetched · Link (AC-N4.4). Row order: `Found` rows by Price ascending (Lowest first), then `Found` with unavailable price, then `Not found`, `Unavailable`, `Disabled`; ties by Retailer name [ASSUMPTION].
- **Found row:** all five cells filled (AC-3.1). Link `target="_blank" rel="noopener noreferrer"` (AC-3.2).
- **Non-Found row:** Retailer name, then the Row status text in the Price cell (`{components.row-status-text}`); Availability, Fetched and Link cells show "—" with visually hidden text "not applicable" [ASSUMPTION]. The Price cell is never blank (C-W3).
- **Lowest price row** (`{components.row-lowest}`, `{components.badge-lowest}`): applied to every eligible row tied for the Lowest price (AC-4.2); never to non-`Found`, unavailable-price or Out of stock rows (AC-4.3, AC-4.4); none when none is eligible (AC-4.5). The badge is real text, so screen readers read "Lowest price" in that row (AC-4.1).
- **Notice** (`{components.notice}`): shown above the table for the all-failed and request-failed cases. The table still renders in the all-failed case (AC-5.3).

## State Patterns

| State | Form | Status line | Table |
|---|---|---|---|
| Idle (first load) | empty, focused | hidden | hidden |
| Invalid input | error shown | hidden | previous table (if any) stays |
| Loading | input keeps value | "Checking {k} retailers…" with a spinner that respects reduced motion | previous table hidden; skeleton rows not required [ASSUMPTION] |
| Results | — | "Found prices at {f} of {k} retailers." | full table |
| All failed | — | "Found prices at 0 of {k} retailers." | notice + full table of status rows |
| Request failed | — | hidden | request-failed notice, no table |

- The whole table appears at once when the Lookup response arrives; rows do not stream in [ASSUMPTION; matches one API response, NFR-1 5 s budget].
- A cached Result row looks identical to a fresh one; only its Fetch time shows it is older (AC-6.2). No "cached" label [ASSUMPTION].
- `{k}` is the number of Approved retailers, which the page gets from the response; if the page cannot know it before the response, the loading copy is "Checking retailers…".

## Interaction Primitives

- Keyboard: Tab order is input → button → each row's link. Enter submits. No other shortcuts.
- Every focusable element shows `{components.focus-indicator}` (AC-N4.2).
- After results arrive, focus stays in the input (so the presenter can type the next EAN); the status line announces the summary through `aria-live="polite"`. Errors use the input's linked description plus `role="alert"`.
- Paste is first-class: pasting 13 digits plus whitespace then Enter works (UJ-1).
- No hover-only information. No tooltips.

## Accessibility Floor

WCAG 2.2 AA basics (NFR-4, C-W2): visible label; errors tied to the input and announced; keyboard-only journey with visible focus; contrast per DESIGN.md Colors; real table markup; Lowest price conveyed in text; `prefers-reduced-motion` disables the spinner animation (static "Checking…" text remains); page `lang="en"` with price and time values readable as plain text; axe reports no serious or critical violations (AC-N4.5).

## Responsive & Platform

- ≥ 600px: form on one row; table at full width.
- < 600px: label, input and button stacked, button full width; table scrolls horizontally inside a focusable, labelled region (`role="region"`, `aria-label="Price results"`, `tabindex="0"`) [ASSUMPTION; simplest option over a card layout].
- Projector: no special mode; the base sizes in DESIGN.md Typography are chosen to be readable on stage.

## Key Flows

### KF-1 (UJ-1). Ioana checks a headphone price before buying

1. Ioana opens the site on her laptop; the input already has focus.
2. She pastes the 13 digits from the box (with a trailing space) and presses Enter.
3. The status line says "Checking 2 retailers…".
4. **Climax:** within about 5 seconds the table appears; the top row is green with "Lowest price", 1.299,99 lei, In stock, fetched today, 14:05.
5. She activates "View at {Retailer}" and the retailer's page opens in a new tab.

Edge: she mistypes a digit; on Enter she sees "Error: the last digit does not match. Check the EAN for a typo." under the input, no Lookup runs, and the input keeps focus so she can fix it.

### KF-2 (UJ-2). The presenter runs the lookup while one retailer misbehaves

1. The presenter types a demo EAN and presses Enter.
2. One retailer answers; the other times out.
3. **Climax:** the table renders anyway: the answering row is highlighted as Lowest price; the other row reads "Unavailable" in muted text. The status line says "Found prices at 1 of 2 retailers."
4. The presenter presses Enter again; the table returns at once with the same Fetch time as before (from the Cache).

### KF-3 (UJ-3). The kill switch, seen in the app

1. The presenter turns a retailer's Kill switch off in the config file (outside the app).
2. Within a minute they run a Lookup again.
3. **Climax:** that retailer's row reads "Disabled"; Altex, Not checked in the source register, has no row at all.

UJ-4 (Health check) has no in-app surface; its output is the job's failure status (AC-8.3).

## Open items

- UI copy language: English assumed; if the owner wants Romanian copy, only the Voice and Tone table changes.
- No admin or status page in the app; the kill switch and Health check are shown from files and CI.
