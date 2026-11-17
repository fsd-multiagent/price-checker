# 0010. Resolve EANs through a curated EAN-to-URL list for every retailer in the first release

- **Status:** Proposed
- **Date:** 2026-11-17
- **Deciders:** Loredan (Architect)
- **Stage / story:** stage 4, run 20261117-174705-full (PRD §9 question 1, spec §11, source register D5, release checklist RC-5)
- **Rules touched:** S-1, S-3, S-4, S-11; FR-8, FR-10, AC-10.1 to 10.5, NFR-6; spine AD-12

## Context

evoMAG's robots.txt disallows search and its pages show no EAN; cel.ro's search route is unchecked against robots.txt (source register). An external EAN catalog would be a new source needing its own register review. The demo needs reliable results for the Known EANs (NFR-6). The evoMAG register row says to approve it only if the chosen route never calls the disallowed search.

## Options considered

| Option | Pros | Cons |
|---|---|---|
| A. Curated EAN-to-URL list (`config/known-eans.yaml`) for all retailers | Never calls search; one request per retailer; reliable on stage; one list serves demo, Health check and mapping | Only Known EANs resolve; "any EAN" is weakened |
| B. Retailer search where robots.txt allows it | Any EAN | Not allowed at evoMAG; unchecked at cel.ro; extra request |
| C. External EAN catalog | Any EAN | New source, needs register review; another dependency |

## Decision

Option A. Every adapter declares `resolution: curated`. An EAN not in the list for a retailer is `NOT_FOUND` and no request is sent. Search may be turned on for one retailer later only by a new ADR, after its register row records the search route as allowed; a catalog needs its own register row first.

## Consequences

- The Shopper gets Prices for Known EANs only; other EANs show `Not found` at every retailer. This matches the PRD's FR-10 note.
- AC-10.4 and AC-8.6 are checked by tests over `config/known-eans.yaml`.
- AC-N2.3 (EAN mismatch) applies only where the page shows an EAN (cel.ro); not evoMAG.
- Answers the condition on the evoMAG register row; the owner may now decide its approval.

## Compliance

S-4: no search page is ever requested. S-3: one product-page request per retailer per EAN, no resolution request. S-11: no user input enters a URL; URLs come from the reviewed list.
