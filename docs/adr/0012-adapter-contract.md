# 0012. Give every retailer Adapter one interface with a sealed outcome and a facts-only result

- **Status:** Proposed
- **Date:** 2026-11-17
- **Deciders:** Loredan (Architect)
- **Stage / story:** stage 4, run 20261117-174705-full
- **Rules touched:** C-5, C-6, C-7, S-7, T-2, T-6; FR-3, FR-8, FR-10, AC-3.4, AC-3.6, AC-8.4; spine AD-7

## Context

Adapters are built by different stories and will be repaired by the self-healing loop (SM-3). They must all return the same shape, extract in the C-6 order, never guess a price (C-7) and never keep a name, description or image (S-7).

## Options considered

| Option | Pros | Cons |
|---|---|---|
| A. `RetailerAdapter` interface, sealed `AdapterOutcome` (`Found(PageFacts)`, `NotFound`, `Unavailable(reason)`), shared `extract` helpers | One shape; facts-only type; shared JSON-LD/microdata/meta parsing | Adapters cannot return extra data (by design) |
| B. Adapters return a free-form map | Flexible | Shape drifts; S-7 not enforced by type |
| C. Declarative selector config per retailer, one generic adapter | Little code | Real pages need code; harder to test the C-6 steps separately |

## Decision

Option A. `RetailerAdapter { String retailerId(); AdapterOutcome fetchAndExtract(Ean ean, URI productUrl, RetailerHttp http); }`; `lookup` resolves the product URL (ADR 0010) and passes it in. `Unavailable` carries an enum reason, never free text. `PageFacts` = `priceMinor` (nullable), `currency`, `availability`, `url`, `eanOnPage` (nullable). Extraction: structured data, then meta tags, then the adapter's selectors, each a separate tested function. Adapters do not handle deadlines, cache, log or read config. The resolution method is declared only in `retailers.yaml`; the application fails to start unless `retailers.yaml` ids and adapter beans match one-to-one.

## Consequences

- A new adapter is tested against `fixtures/<retailer-id>/` only (T-2); a simulated layout change fixture makes its test fail (AC-8.4).
- The `lookup` core applies RON-only (AC-3.5) and EAN mismatch (AC-N2.3) using `PageFacts`.

## Compliance

S-7 is enforced by the type: `PageFacts` has no field for anything else.
