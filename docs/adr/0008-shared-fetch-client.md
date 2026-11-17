# 0008. Send every retailer request through one fetch client that enforces S-1 to S-6 before each request

- **Status:** Proposed
- **Date:** 2026-11-17
- **Deciders:** Loredan (Architect)
- **Stage / story:** stage 4, run 20261117-174705-full
- **Rules touched:** C-12, S-1 to S-6, S-11; FR-9, FR-11, NFR-3 (AC-N3.1 to N3.8), AC-2.4, AC-8.7; spine AD-6

## Context

Veronel (Security & Compliance) checks at stage 4 that S-1 to S-8 are enforced in code through the shared fetch client, not left to convention (`security-compliance.md`). AC-N3.8 and C-12 require the same.

## Options considered

| Option | Pros | Cons |
|---|---|---|
| A. One `fetch` package; adapters get a `RetailerHttp` bound to their retailer and cannot name a host | All rules in one place, tested once; adapters cannot bypass | Adapters lose flexibility (by design) |
| B. A helper library each adapter calls | Simple | An adapter can skip it; rules checked per adapter |
| C. An outbound HTTP proxy container | Language independent | More infra; register and robots logic still needed |

## Decision

Option A. Before every request (robots.txt fetches included) the client refuses, without sending, unless: the retailer is Approved in the bundled register and enabled; fetching is `allowed` in `runtime.yaml` and no Block is active; the URL is `https` on the retailer's register domain (or `www.` of it) and no redirect leaves it; robots.txt (crawler-commons, loaded single-flight, cached 24 h; a 5xx or unparseable file disallows everything, RFC 9309) allows the path for our user agent; the retailer's `notBefore` is not in the future; a rate and concurrency permit is free before the deadline. Every attempt, redirect and robots.txt fetch takes a permit. It sends one honest user agent with the contact URL, no cookies, no login; retries only timeouts and 5xx, at most twice, with exponential backoff inside the deadline; on 403 or a recognized challenge page it calls `BlockStore.record` and does not retry. It stamps `fetchedAt` from the injected `Clock`. The EAN reaches a URL only URL-encoded.

**Retry-After across Lookups.** On 429 the client calls `BlockStore.recordRetryAfter(retailer, now + Retry-After)` (delta-seconds or HTTP-date; missing or unparseable → 60 s). If that time falls within the current request's deadline it waits and retries once; otherwise that row is `UNAVAILABLE`. `BlockStore.notBefore(retailer)` is the later of the value persisted in `blocks.json` and the `notBefore` in `runtime.yaml` (a 429 the Health check runner saw, ADR 0016); a later 429 never moves it earlier. Until it passes, the client refuses every request to that retailer without sending, whatever Lookup or Health check asks. The row is `UNAVAILABLE`, not cached (ADR 0011), and not a Block (AC-11.4). Production exposes `notBefore` in `/api/status` (ADR 0007), so the runner waits it out too.

**User agent at start.** The client builds the full user agent, contact URL from config included, and refuses to start if the contact URL is missing or the string contains `bot`, `robot`, `crawl` or `spider` in any case.

A robots.txt disallow is a Block (AC-11.1). 404 or 410 on a curated URL means `NOT_FOUND`; timeouts, 5xx after retries and refused permits mean `UNAVAILABLE`; the full mapping table is in the spine (AD-6).

## Consequences

- ArchUnit forbids HTTP client use outside `fetch` (ADR 0006).
- The user agent's contact URL is still needed (source register "Still needed"); the fetch client refuses to start without it.
- Unit tests cover each refusal with a stub server; none contact a retailer (T-3). One test sends a 429 with `Retry-After: 120`, then runs a second Lookup at a fixed `Clock` 10 s later and asserts the stub got no second request; another asserts a request is sent again after `notBefore`.
- `notBefore` survives a restart because `BlockStore` persists it; a test restarts `BlockStore` inside a window and asserts the refusal holds.

## Compliance

Enforces S-1 (register check per request), S-2 (no login or cookies), S-3 (one product-page fetch, plus single-flight in ADR 0011), S-4 (robots check per request), S-5 (limits, backoff, timeouts), S-6 (honest UA, stop on Block, no workaround), S-11 (encoding).
