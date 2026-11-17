---
title: "PRD Addendum: Price Checker"
created: 2026-11-17
updated: 2026-11-17
---

# Addendum: Price Checker PRD

Depth that supports the PRD but belongs to the architecture, the source register or the UX spec.

## Technical decisions already made (spec §10, not reopened)

- Frontend: React single-page app.
- Backend: Java 21, Spring Boot, contract-first REST API in `api/openapi.yaml`.
- Hosting: one Azure Linux VM in an EU region, Docker Compose with frontend, backend and a TLS reverse proxy.
- Data: no database. Cache in memory; Kill switches in a config file the app reloads.
- Infrastructure: Terraform; plan on every PR, apply only after human approval.
- CI/CD: GitHub Actions deploys to the VM on merge to `main`.
- Repository: one monorepo.

## EAN resolution options (for Loredan's ADR, FR-10)

| Option | Shopper promise | Constraints |
|---|---|---|
| Retailer search | Any EAN | Only where robots.txt allows search. evoMAG disallows it (`/*?q=`, `/*?sn.q=`); cel.ro's search route is unchecked |
| External EAN catalog | Any EAN | Third-party source; needs its own source-register review |
| Curated EAN-to-URL list | Known EANs only | Simplest and most reliable on stage; weakens "any EAN" |

Facts that constrain the choice:

- evoMAG pages carry schema.org microdata but no EAN, so a curated list or a catalog is needed for evoMAG; AC-N2.3 (EAN mismatch check) cannot apply there.
- cel.ro pages carry JSON-LD Product and Offer data and show the EAN.

PM view: a curated list for the Known EANs, plus search only where robots.txt allows it, is probably the simplest option that keeps the demo working. The Known EANs list then serves three uses: demo EANs, Health check EANs and the curated mapping.

## Notes for the architecture

- AC-2.4, AC-N3.3 and NFR-1 interact: with cel.ro at 1 request per 2 s and concurrency 1, two Shoppers at once can push a row past the timeout. The PRD prefers `Unavailable` over breaking the rate limit (SM-C3).
- AC-9.1 needs a machine-readable link between the app's retailer configuration and the Source register's Status column; the CI check must read the register, not a copy.
- AC-11.1 needs a Block flag that survives until an operator clears it; whether that lives in the Kill switch config file is an architecture choice.
- AC-N3.8: the shared fetch client is also required by `docs/standards/coding.md` C-12.

## Notes for UX

- Row statuses need distinct text, not only colour: `Found`, `Not found`, `Unavailable`, `Disabled`.
- Lowest price highlight needs a text label (AC-4.1) and AA contrast (AC-N4.3).
- Romanian price format, for example `1.299,99 lei`. Interface language is not specified; Romanian or English is a UX decision.
