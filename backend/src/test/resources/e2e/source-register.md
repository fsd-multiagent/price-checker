# Source register (end-to-end test only, story 2.14)

Not the real register: `E2eServer` loads this file instead of `docs/source-register.md`. The real
adapters' ids are fixed, so the two ids appear here; every request goes to the `FixtureHttp` stub.

## Retailers

| Retailer | Domain | Status | Limits | Notes |
|---|---|---|---|---|
| CEL.ro | `cel.ro` | Approved 2026-11-18 | 100 requests / 1 s, concurrency 4 | e2e stub |
| evoMAG | `evomag.ro` | Approved 2026-11-18 | 100 requests / 1 s, concurrency 4 | e2e stub |
