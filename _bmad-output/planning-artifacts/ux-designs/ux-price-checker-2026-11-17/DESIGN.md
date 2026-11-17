---
name: Price Checker
description: One-page EAN price comparison across Approved Romanian retailers. Plain, neutral, factual; no UI library assumed. Visual identity contract (how it looks); behavior lives in EXPERIENCE.md.
status: final
created: 2026-11-17
updated: 2026-11-17
author: Safta (UX Designer), headless, run 20261117-174705-full, stage design, step ux, round 1
sources:
  - _bmad-output/planning-artifacts/prds/prd-price-checker-2026-11-17/prd.md
  - docs/standards/coding.md (C-W2, C-W3)
colors:
  # [ASSUMPTION] No brand was supplied. Neutral palette chosen only to meet
  # WCAG 2.2 AA (AC-N4.3). Ratios are against {colors.background} unless noted.
  background: '#FFFFFF'
  surface: '#F9FAFB'
  foreground: '#1F2937'        # 14.7:1
  muted-foreground: '#4B5563'  # 7.6:1
  border: '#D1D5DB'
  primary: '#1D4ED8'           # 6.7:1; white on primary 6.7:1
  primary-foreground: '#FFFFFF'
  focus-ring: '#1D4ED8'        # >= 3:1 non-text contrast
  error: '#B91C1C'             # 6.5:1
  error-surface: '#FEF2F2'
  lowest-surface: '#DCFCE7'
  lowest-foreground: '#14532D' # 9.6:1 on lowest-surface
  lowest-border: '#15803D'
  lowest-badge: '#15803D'      # white text on it 5.0:1
  lowest-badge-foreground: '#FFFFFF'
typography:
  # [ASSUMPTION] System font stack: no web-font download, nothing third-party (AC-N5.4).
  body:
    fontFamily: 'system-ui, -apple-system, "Segoe UI", Roboto, sans-serif'
    fontSize: 16px
    fontWeight: '400'
    lineHeight: '1.5'
  title:
    fontFamily: '{typography.body.fontFamily}'
    fontSize: 28px
    fontWeight: '700'
    lineHeight: '1.2'
  label:
    fontFamily: '{typography.body.fontFamily}'
    fontSize: 16px
    fontWeight: '600'
    lineHeight: '1.4'
  input:
    fontFamily: 'ui-monospace, "SF Mono", Menlo, Consolas, monospace'
    fontSize: 20px
    fontWeight: '400'
    letterSpacing: 0.05em
  price:
    fontFamily: '{typography.body.fontFamily}'
    fontSize: 18px
    fontWeight: '600'
    fontVariantNumeric: tabular-nums
  small:
    fontFamily: '{typography.body.fontFamily}'
    fontSize: 14px
    fontWeight: '400'
    lineHeight: '1.4'
rounded:
  sm: 4px
  md: 6px
  pill: 999px
spacing:
  xs: 4px
  sm: 8px
  md: 16px
  lg: 24px
  xl: 40px
  content-max-width: 880px
components:
  ean-input:
    font: '{typography.input}'
    background: '{colors.background}'
    border: '1px solid {colors.border}'
    border-error: '2px solid {colors.error}'
    radius: '{rounded.md}'
    padding: '{spacing.sm} {spacing.md}'
    minHeight: 48px
  button-primary:
    background: '{colors.primary}'
    foreground: '{colors.primary-foreground}'
    font: '{typography.label}'
    radius: '{rounded.md}'
    minHeight: 48px
    padding: '0 {spacing.lg}'
  field-error:
    foreground: '{colors.error}'
    font: '{typography.small}'
  focus-indicator:
    outline: '2px solid {colors.focus-ring}'
    outlineOffset: 2px
  results-table:
    headerBackground: '{colors.surface}'
    rowBorder: '1px solid {colors.border}'
    cellPadding: '{spacing.sm} {spacing.md}'
  row-lowest:
    background: '{colors.lowest-surface}'
    foreground: '{colors.lowest-foreground}'
    borderLeft: '4px solid {colors.lowest-border}'
  badge-lowest:
    background: '{colors.lowest-badge}'
    foreground: '{colors.lowest-badge-foreground}'
    font: '{typography.small}'
    fontWeight: '600'
    radius: '{rounded.pill}'
    padding: '2px {spacing.sm}'
  row-status-text:
    foreground: '{colors.muted-foreground}'
    font: '{typography.body}'
  notice:
    background: '{colors.surface}'
    border: '1px solid {colors.border}'
    radius: '{rounded.md}'
    padding: '{spacing.md}'
---

## Brand & Style

Price Checker answers one question: what does this EAN cost at each Approved retailer right now? The visual job is to make that answer readable at a glance by a shopper on a laptop and by an audience watching a projector. The style is plain and factual: white page, one input, one table, one green highlight. Nothing decorates the data. Trust comes from showing every fact (retailer, price, availability, fetch time) and every gap in words.

No brand, logo or UI library was supplied [ASSUMPTION]. These tokens are a neutral baseline that meets WCAG 2.2 AA (AC-N4.3). If Loredan (Architect) picks a component library, it inherits these tokens; only the components below are product-specific.

## Colors

- **Foreground / muted** carry all text. Muted is for Fetch time, column captions and non-`Found` row statuses.
- **Primary blue** is used only for the Check prices button, links and the focus ring.
- **Lowest green** (`lowest-*`) is used only for the Lowest price row and its badge. It never appears anywhere else, so green means "cheapest" and nothing else. The highlight always comes with the text badge "Lowest price" (AC-4.1); colour is never the only signal.
- **Error red** is used only for EAN validation errors next to the input. Row statuses `Unavailable`, `Not found` and `Disabled` are not errors to the shopper and are shown in muted text, not red, so a failing retailer does not look like a broken page (FR-5).

## Typography

System fonts only, so the page loads nothing from third parties (AC-N5.4). The EAN input uses a monospace face at 20px so each digit is easy to check against the barcode. Prices use tabular numerals so amounts line up in the column. Minimum body size is 16px; nothing smaller than 14px. On a projector, the title and price sizes keep the table readable from the back of a room.

## Layout & Spacing

One centred column, `content-max-width` 880px, `xl` top padding. Order top to bottom: title, one-line explanation, EAN form (label, input and button on one row on wide screens, stacked below 600px), status line, results table, footer note. The table never scrolls horizontally on a laptop; below 600px it may scroll horizontally inside its own container (see EXPERIENCE.md Responsive).

## Elevation & Depth

Flat. No shadows. Separation comes from borders and the `surface` table header.

## Shapes

Small radii (`sm`, `md`) on inputs, buttons and notices. The Lowest price badge is a pill. Table cells are square.

## Components

- **ean-input** — 48px tall, monospace, red 2px border when invalid.
- **button-primary** — "Check prices"; same height as the input.
- **field-error** — red small text directly under the input, with a leading "Error:" word so it does not rely on colour.
- **results-table** — columns: Retailer · Price · Availability · Fetched · Link. Header row on `surface`.
- **row-lowest** — green surface plus a 4px left border, and **badge-lowest** ("Lowest price") inside the Price cell.
- **row-status-text** — for non-`Found` rows the Price cell spans the status in muted text: "Not found", "Unavailable" or "Disabled". Never an empty cell (C-W3).
- **notice** — neutral box for the all-failed message (AC-5.3).
- **focus-indicator** — 2px primary outline with 2px offset on every focusable element (AC-N4.2).

## Do's and Don'ts

- Do show price as `1.299,99 lei` (Romanian grouping, comma decimal, "lei" suffix) (AC-3.1).
- Do show the word "unavailable" where a price is missing; never `0`, `—` or blank (AC-5.5, C-W3).
- Do pair the green highlight with the "Lowest price" text label.
- Don't show a product name, image or description (S-7, PRD §0).
- Don't use red for retailer failures; red is only for the shopper's own input error.
- Don't load web fonts, icon fonts, analytics or any third-party asset.
