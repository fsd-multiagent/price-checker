# Epic 2 Context: Real prices, fetched politely

<!-- Generated from planning artifacts. Regenerate with compile-epic-context if planning docs change. -->

## Goal

The Lookup fetches real product pages through one shared fetch client that enforces the politeness rules (S-1 to S-7), extracts facts, caches them and contains every failure inside its own row. Live prices appear once the owner approves cel.ro and evoMAG in the source register; until then adapters run on synthetic fixtures and stay `adapter: disabled`.

## Stories

- Story 2.1: Extraction helpers for structured data, meta tags and prices
- Story 2.2: Page stripper for fixtures and reports
- Story 2.3: Runtime kill-switch config read by the backend
- Story 2.4: Shared fetch client with the approval gate and limits
- Story 2.5: robots.txt enforcement in the fetch client
- Story 2.6: BlockStore and Block handling
- Story 2.7: 429 and Retry-After handling
- Story 2.8: Parallel Lookup execution, normalization and cache
- Story 2.9: Status endpoint
- Story 2.10: cel.ro Adapter from synthetic fixtures
- Story 2.11: evoMAG Adapter from synthetic fixtures
- Story 2.12: Record cel.ro fixtures, curate Known EANs and enable the Adapter
- Story 2.13: Record evoMAG fixtures, curate Known EANs and enable the Adapter
- Story 2.14: End-to-end test of UJ-1 and UJ-2 against stubbed retailers

## Requirements & Constraints

- A retailer is fetched only if Approved in the bundled source register; a runtime file, flag or env var can never enable a non-Approved one. Approval takes effect at the next deploy; the kill switch is the immediate stop.
- Polite fetching: fixed user agent containing a contact URL (startup refuses if missing or containing `bot`, `robot`, `crawl`, `spider`), no cookies or login, rate limit and concurrency within the register limits, at most 2 retries on timeouts/5xx only, robots.txt honored, never retry through another IP, proxy, user agent or altered headers.
- Lookup must render within 5 s (per-retailer deadline default 4000 ms); a repeat Lookup within the 15 min TTL returns within 1 s from cache.
- A failing retailer never makes the Lookup non-2xx; it only turns its own row `UNAVAILABLE`.
- Accuracy: an unparseable price is `null`, never 0; non-RON or null price gives FOUND without price; EAN on page differing from request gives NOT_FOUND.
- Privacy and storage: keep no product name, description or image (S-7); fixtures and reports pass through the page stripper (T-2); logs carry only allowlisted fields; cache holds only status, price, availability, url, fetchedAt.
- Tests must make no network call to retailers (T-3); test names or tags carry the AC id (T-1); time comes from an injected Clock (T-5).

## Technical Decisions

- Hexagonal modular monolith, root package `ro.pricechecker`; ArchUnit enforces allowed package arrows. No HTTP client outside `fetch`; `extract` parses strings only; retailer domains and CSS selectors appear only in `retailer.<id>` and `config/`. `retailer.<id>` packages never depend on each other, `lookup`, `cache`, `registry` or `api`.
- Adapter contract: one Spring bean per retailer implementing `fetchAndExtract(Ean, URI, RetailerHttp)` returning sealed `AdapterOutcome` (`Found(PageFacts)`, `NotFound`, `Unavailable(reason)`). `PageFacts` holds only priceMinor (nullable), currency, availability, url, eanOnPage. Extraction order: structured data, then meta tags, then the adapter's own selectors, each a separate tested function. Adapters do not handle deadlines, cache, logging or config.
- Money is integer bani (RON) everywhere; page-text price parsing lives only in `extract`.
- `RetailerHttp` is bound to a retailer and cannot name a host. Fetch client refuses, sending nothing, unless: Approved and adapter enabled; fetching allowed and no active Block; https on the register domain or `www.` of it (redirects included); robots.txt allows (single-flight, 24 h cache, 5xx/unparseable disallows all); `notBefore` not in the future; a rate/concurrency permit is free before the deadline.
- Outcome mapping: 404/410 gives NotFound; timeouts, 5xx after retries, refused permit give Unavailable; 403, challenge page or robots disallow gives a Block via `BlockStore.record`; 429 sets `notBefore` (Retry-After seconds, HTTP-date, or 60 s default; never moves earlier), waits and retries once only if inside the deadline, and is not a Block.
- Config files with one writer each: `config/retailers.yaml` and `config/known-eans.yaml` (PR, read at start); `runtime.yaml` (kill-switch workflow, polled every 15 s; missing retailer or no parseable file means stopped; unparseable keeps last good); `blocks.json` (only `BlockStore`, atomic whole-map write, survives restarts). A Block is active while `blockGeneration >= clearGeneration` or `runnerBlock` is present; `BlockStore.record` re-reads `runtime.yaml` so an unpolled clear cannot cancel a new Block.
- Row precedence per retailer: DISABLED, then active Block gives UNAVAILABLE, then EAN absent from known-eans gives NOT_FOUND with no request, then cache, then load plus normalize. Only FOUND and NOT_FOUND are cached.
- Execution: one virtual thread per retailer; generic Caffeine async cache keyed `(retailerId, ean)`, TTL 15 min configurable, loader supplied by `lookup`, concurrent misses share one load that is not cancelled when a caller gives up.
- Resolution is `curated` for all retailers: URLs come from `known-eans.yaml`; never issue a search request.
- `GET /api/status` lists only Approved retailers: `retailerId`, `retailerName`, `enabled`, `blocked`, optional `notBefore`; no prices.
- `PageStripper` (in `fetch`) keeps only JSON-LD product data among scripts, removes styles, images/srcset, iframes, comments, and cuts descriptions to 200 chars.
- Logs via `LookupLog` only: JSON lines with ts, level, event, ean, retailer, outcome, durationMs; exceptions log type not message; Blocks log at WARN.
- Config via validated `@ConfigurationProperties` records; the app must fail to start unless `retailers.yaml` ids and adapter beans match one-to-one.

## Cross-Story Dependencies

- Epic 2 builds on Epic 1: 1-1 backend scaffold (2.1, 2.2), 1-5 register and retailer config (2.4), 1-6 Lookup skeleton (2.3, 2.10, 2.11), 1-8 web results table (2.14).
- Chain: 2.3 kill-switch, then 2.4 fetch client, then 2.5 robots, then 2.6 BlockStore, then 2.7 Retry-After, then 2.9 status endpoint.
- 2.1 helpers feed 2.8 (Lookup execution/cache), 2.10 and 2.11 (adapters); 2.2 stripper feeds 2.12 and 2.13.
- 2.12 and 2.13 (record real fixtures, enable adapters) need 2.2, 2.7, 2.8 and their adapter story, and also need the owner to mark the retailer Approved in the register; agents never approve.
- 2.14 e2e needs 1.8, 2.8, 2.10 and 2.11.
- Epic 3's Health check and kill-switch workflows consume `/api/status`, `runtime.yaml`, `BlockStore` and `PageStripper` from this epic.
