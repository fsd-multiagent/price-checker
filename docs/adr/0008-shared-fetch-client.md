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

Option A. Before every request (robots.txt fetches included) the client refuses, without sending, unless: the retailer is Approved in the bundled register and enabled; fetching is `allowed` in `runtime.yaml` and no Block is active; the URL is `https` on the retailer's register domain (or `www.` of it) and no redirect leaves it; robots.txt (crawler-commons, loaded single-flight, cached 24 h; a 5xx or unparseable file disallows everything, RFC 9309) allows the path for our user agent; a rate and concurrency permit is free before the deadline. Every attempt, redirect and robots.txt fetch takes a permit. It sends one honest user agent with the contact URL, no cookies, no login; retries only timeouts and 5xx, at most twice, with exponential backoff inside the deadline; honors `Retry-After` on 429; and on 403 or a recognized challenge page calls `BlockStore.record` and does not retry. It stamps `fetchedAt` from the injected `Clock`. The EAN reaches a URL only URL-encoded.

A robots.txt disallow is a Block (AC-11.1). 404 or 410 on a curated URL means `NOT_FOUND`; timeouts, 5xx after retries and refused permits mean `UNAVAILABLE`; the full mapping table is in the spine (AD-6).

## Consequences

- ArchUnit forbids HTTP client use outside `fetch` (ADR 0006).
- The user agent's contact URL is still needed (source register "Still needed"); the fetch client refuses to start without it.
- Unit tests cover each refusal with a stub server; none contact a retailer (T-3).

## Compliance

Enforces S-1 (register check per request), S-2 (no login or cookies), S-3 (one product-page fetch, plus single-flight in ADR 0011), S-4 (robots check per request), S-5 (limits, backoff, timeouts), S-6 (honest UA, stop on Block, no workaround), S-11 (encoding).
