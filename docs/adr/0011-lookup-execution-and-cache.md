# 0011. Run each retailer on its own virtual thread with a deadline, and cache Found/Not found rows in one single-flight cache

- **Status:** Proposed
- **Date:** 2026-11-17
- **Deciders:** Loredan (Architect)
- **Stage / story:** stage 4, run 20261117-174705-full
- **Rules touched:** C-8, S-3, S-5, S-7; FR-2, FR-5, FR-6, FR-7, FR-11, NFR-1, AC-2.2 to 2.4, AC-6.1 to 6.5; spine AD-10, AD-11

## Context

Retailers must run in parallel with their own timeouts (AC-2.2, 2.3) and the table must render within 5 s (AC-N1.1). Two Shoppers asking for the same EAN at once must not double the requests (S-3, cel.ro's 1 request per 2 s). The cache is in memory only (AC-6.5) and holds only S-7 facts.

## Options considered

| Option | Pros | Cons |
|---|---|---|
| A. Java 21 virtual threads, per-retailer deadline; Caffeine async cache with shared in-flight loads | Simple blocking adapter code; single-flight built in; TTL built in | One more dependency |
| B. Reactive (WebFlux) | Non-blocking | Harder adapters and tests for a tiny load |
| C. Hand-written `ConcurrentHashMap` cache | No dependency | TTL and single-flight to write and test by hand |

## Decision

Option A. Status precedence per Approved retailer: adapter disabled or fetching stopped → `DISABLED`; active Block → `UNAVAILABLE`; EAN not in the curated list → `NOT_FOUND` with no request; cache hit → cached verdict; else load. `cache` is generic (no dependency on adapters); `lookup` supplies the loader: the adapter call followed by `normalize` (EAN mismatch → `NOT_FOUND`; non-RON or null price → `FOUND` without price). The cache stores the normalized verdict: status, `priceMinor`, availability, URL, `fetchedAt`. Only `FOUND` and `NOT_FOUND` are cached, keyed `(retailerId, ean)`, TTL 15 min configurable. One `deadlineMs` per retailer (default 4000); an in-flight load owns its deadline and is not cancelled when a waiting caller gives up.

## Consequences

- A cached row keeps its original `fetchedAt` (AC-6.2).
- Kill switch and Block are checked before the cache, so a disabled retailer never shows a cached price.
- A failing load is not cached, so the next Lookup retries (AC-6.3).

## Compliance

S-3 and S-5 are helped by single-flight; S-7 by the cache value type, which has no field for anything else (AC-3.6 test).
