# 0006. Build the backend as one ports-and-adapters Spring Boot process, packaged by feature

- **Status:** Proposed
- **Date:** 2026-11-17
- **Deciders:** Loredan (Architect)
- **Stage / story:** stage 4, run 20261117-174705-full
- **Rules touched:** C-5, C-12, C-B1, S-3, S-5; FR-2, FR-6, FR-11; spine AD-1, AD-2

## Context

Every retailer needs its own Adapter (PRD §3), but caching, rate limits, the compliance gate and the lowest-price rule must be the same for all (C-5, C-12, AC-N3.8). Stories will be built in parallel by different agents, so the dependency rules have to be checked by a test, not by convention. Cache, rate limiters and Block flags are in memory (AC-6.5, spec §10).

## Options considered

| Option | Pros | Cons |
|---|---|---|
| A. One process, ports and adapters, package by feature, ArchUnit rules | Retailer code cannot leak into the core; rules are tested; fits C-B1 | Needs an ArchUnit test kept up to date |
| B. Layered packages (controller/service/repository) | Familiar | Contradicts C-B1; retailer code spreads across layers |
| C. One service per retailer | Strong isolation | Many deployables; shared limits across processes need a store; far too much for a demo |

## Decision

Option A. Root package `ro.pricechecker`; feature packages `api`, `ean`, `adapter` (port types), `lookup`, `cache`, `registry`, `fetch`, `extract`, `health`, `retailer.<id>`, with the allowed dependencies drawn in the spine. Exactly one backend instance runs, because cache, limiter and Block state are in-process.

## Consequences

- A new retailer is a new `retailer.<id>` package plus config rows; no core change.
- An ArchUnit test fails on any forbidden dependency, any HTTP client use outside `fetch`, and any retailer domain or selector outside its adapter.
- Scaling to two instances needs a new ADR (shared limiter and cache).

## Compliance

Supports S-1 to S-6 by giving them one place to live (`fetch`, ADR 0008) and makes C-12 a tested rule rather than a convention.
