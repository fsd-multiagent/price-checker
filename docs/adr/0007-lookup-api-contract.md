# 0007. Serve one Lookup endpoint whose response is already decided: rows ordered, lowest flagged, money in bani, times in UTC

- **Status:** Proposed
- **Date:** 2026-11-17
- **Deciders:** Loredan (Architect)
- **Stage / story:** stage 4, run 20261117-174705-full
- **Rules touched:** C-3, C-7, C-W1, C-W3, S-11, T-7; FR-1 to FR-5, AC-3.5, AC-N2.3; spine AD-3, AD-4, AD-5, AD-13

## Context

The API is contract-first (C-3) with a generated client (C-W1). The lowest-price rules (AC-4.1 to 4.5), the row order in EXPERIENCE.md and the EAN error messages could each be built twice, once in Java and once in React, and drift. Prices must never become 0 or a float rounding error (C-7).

## Options considered

| Option | Pros | Cons |
|---|---|---|
| A. Server computes order and `lowest`; client only formats | One tested rule; thin client | Server knows a UX ordering rule |
| B. Server returns raw rows; client sorts and highlights | Server stays generic | Rules live in TypeScript, away from the Java tests that cover AC-4 |
| Money as decimal number | Simple | Float errors |
| Money as integer bani (`priceMinor`) | Exact | Client divides by 100 to format |

## Decision

Option A with integer bani. `GET /api/lookup?ean=` returns `LookupResponse { ean, rows[] }`; each `ResultRow` has `retailerId`, `retailerName`, `status`, `priceMinor?`, `availability?`, `url?`, `fetchedAt?` (ISO-8601 UTC), `lowest`. `GET /api/status` lists each Approved retailer with two booleans, `enabled` and `blocked`, an optional `notBefore` (ISO-8601 UTC; the time until which a `Retry-After` holds requests, ADR 0008), and no prices. Generated DTOs stay in `api`; `lookup` returns domain types. Validation errors are RFC 9457 problem+json with `code` ∈ {`EAN_EMPTY`, `EAN_NON_DIGIT`, `EAN_LENGTH`, `EAN_CHECK_DIGIT`}. The same validation rule is implemented in `ean.Ean` and `web/src/ean.ts`, and both run the shared cases in `api/ean-cases.json` (T-7).

## Consequences

- The web app formats `1.299,99 lei` and Europe/Bucharest time, and never re-sorts or compares prices.
- The loading copy "Checking {k} retailers…" cannot know `k` before the response; the web app uses "Checking retailers…" (allowed by EXPERIENCE.md).
- Resolves the shape the stage 5 stories share; every API change edits `api/openapi.yaml` first (C-3).

## Compliance

S-11: the server validates every EAN before any retailer work and returns 400 without contacting a retailer (AC-1.5). S-7: rows carry only the allowed facts. `/api/status` holds no personal data.
