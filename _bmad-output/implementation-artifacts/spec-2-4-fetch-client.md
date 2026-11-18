---
title: 'Shared fetch client with the approval gate and limits'
type: 'feature'
created: '2026-11-18'
status: 'in-review'
baseline_revision: '8674211b3b83551f181e924798c90d4556d1a661'
review_loop_iteration: 0
followup_review_recommended: false
context:
  - '{project-root}/_bmad-output/planning-artifacts/architecture/architecture-price-checker-2026-11-17/ARCHITECTURE-SPINE.md'
warnings: []
deferred: []
---

<intent-contract>

## Intent

**Problem:** Adapters have a `RetailerHttp` port but nothing implements it, so polite fetching (S-1 to S-6) would depend on each adapter (AC-N3.8, AD-6, C-12).

**Approach:** Add a `FetchClient` in `fetch` that hands out a `RetailerHttp` bound to one retailer id. Before every attempt (redirects included) it checks approval, adapter enabled, `fetching: allowed`, https on the register domain or `www.` of it, and takes a rate and concurrency permit before the per-retailer deadline; it retries only timeouts and 5xx (at most 2, exponential backoff inside the deadline), and reports outcomes through a `FetchFailure` (an `IOException` carrying an `AdapterOutcome`). The user agent is built and checked at startup.

## Boundaries & Constraints

**Always:** send nothing when a check fails; take time from the injected `Clock` and waits from an injected `Sleeper` (T-5); one fixed user agent with the contact URL, no cookies, no login, no redirect following by the JDK client; tests never contact a retailer (T-3); test names carry the AC id (T-1).

**Never:** robots.txt (2.5), `BlockStore`/Block handling and 403/challenge mapping (2.6), 429/`Retry-After`/`notBefore` (2.7), wiring the client into `LookupService` (2.8); never retry 4xx; never change headers, user agent or route on retry (S-6).

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Happy path | Approved, enabled, allowed; `https://a.example/p/1` → 200 | body returned with `fetchedAt` = clock now; one request with the UA | none |
| Off-host / scheme | `http://a.example/…`, `https://evil.example/…`, `https://x.a.example/…`, port ≠ 443, userinfo | nothing sent | `FetchFailure` → `Unavailable(REFUSED)` |
| Redirect | 301 to `https://www.a.example/q` → followed (new permit); to another host → refused | follow checked hops only, max 3 | off-host → `Unavailable(REFUSED)` |
| Gate | not Approved / adapter disabled / `fetching: stopped` | nothing sent | `Unavailable(REFUSED)` |
| Limits | next rate slot or concurrency permit after the deadline | nothing sent | `Unavailable(REFUSED)` |
| Retries | timeout or 5xx | ≤ 2 retries, backoff 250 ms then 500 ms, only if it fits the deadline | `Unavailable(TIMEOUT \| HTTP_ERROR)` |
| 4xx | 400/403/429 etc. (not 404/410) | not retried | `Unavailable(HTTP_ERROR)` |
| Not found | 404 or 410 | not retried | `NotFound`, `fetchedAt` from clock |
| Startup | contact URL missing/blank/not absolute http(s), or UA contains bot/robot/crawl/spider (any case) | app refuses to start | `IllegalStateException` |

</intent-contract>

## Code Map

- `backend/src/main/java/ro/pricechecker/adapter/RetailerHttp.java` -- port `String get(URI) throws IOException`; the bound client implements it.
- `backend/src/main/java/ro/pricechecker/adapter/UnavailableReason.java` -- add `REFUSED` (send nothing); `LookupSkeletonAcceptanceGapsTest:340` iterates all values, so still green.
- `backend/src/main/java/ro/pricechecker/registry/Registry.java` -- `isApproved(domain)`, `retailers()` (`RetailerConfig.enabled()`, `minIntervalMs`, `concurrency`, `deadlineMs`, validated positive by `ConfigCheck`).
- `backend/src/main/java/ro/pricechecker/registry/RuntimeSwitch.java` -- `isAllowed(domain)`; `LookupService` already calls `refresh()` once per Lookup.
- `backend/src/main/java/ro/pricechecker/RuntimeSwitchConfig.java` -- pattern for root-level wiring with `@Value`; wire `FetchClient` the same way so `fetch` reads no property.
- `backend/src/test/java/ro/pricechecker/arch/ArchitectureRules.java:32` -- `fetch` may use `adapter`, `registry`; only `fetch` may use `java.net.http`.
- `backend/src/test/java/ro/pricechecker/registry/{MutableClock,TestRegistries}.java` -- `mixed()` registry (a Approved+enabled, b Approved+disabled, c Proposed) and `runtimeAllowing`.

## Tasks & Acceptance

**Execution:**
- `backend/src/main/java/ro/pricechecker/fetch/UserAgent.java` -- build `price-checker/<version> (+<contactUrl>)`, validate -- AC 2.4.5.
- `backend/src/main/java/ro/pricechecker/fetch/Transport.java` + `JdkTransport.java` -- one GET (`java.net.http`, `Redirect.NEVER`, no cookie handler, UA header, per-request timeout) returning status, `Location`, body -- seam for stub tests.
- `backend/src/main/java/ro/pricechecker/fetch/Sleeper.java` -- injected wait.
- `backend/src/main/java/ro/pricechecker/fetch/RetailerLimiter.java` -- per-retailer slot reservation and semaphore, refuse when not available before the deadline -- AC 2.4.3.
- `backend/src/main/java/ro/pricechecker/fetch/FetchClient.java` -- `bind(retailerId)` → `BoundHttp` with gate, URL check, redirects, retries, outcome mapping -- AC 2.4.1, 2.4.2, 2.4.4, 2.4.6.
- `backend/src/main/java/ro/pricechecker/fetch/FetchFailure.java`, `FetchedPage.java` -- outcome carrier and `(url, body, fetchedAt)`.
- `backend/src/main/java/ro/pricechecker/FetchClientConfig.java` + `application.yaml` -- `price-checker.contact-url` (env `PRICE_CHECKER_CONTACT_URL`), default the project repo URL.
- Tests in `backend/src/test/java/ro/pricechecker/fetch/` (stub transport, recording sleeper, `MutableClock`) and `JdkTransportTest` against a local `HttpServer`; `FetchClientConfigTest` at root.

**Acceptance Criteria:**
- Given each refusal row of the matrix, when `get` is called, then the stub transport records zero requests (2.4.1, 2.4.2, 2.4.3).
- Given 5xx/timeouts, when fetched, then exactly 3 attempts with sleeps 250 ms and 500 ms; given 400, exactly 1 (2.4.4).
- Given a missing contact URL or a forbidden word, when the Spring context or `UserAgent` is built, then it fails; the JDK transport sends the UA and no `Cookie` header (2.4.5).
- Given 404/410, then `FetchFailure.outcome()` is `NotFound` and `fetchedAt` equals the clock (2.4.6).

## Spec Change Log

## Review Triage Log

## Design Notes

`RetailerHttp.get` returns only a body, so failures travel as `FetchFailure extends IOException` with `outcome()`; an adapter writes `catch (FetchFailure f) { return f.outcome(); }`. The limiter reserves the next rate slot (`max(now, next)`) only when it is before the deadline, then waits via `Sleeper`; concurrency is a fair `Semaphore` tried for the remaining deadline. The client is not yet wired into `LookupService` (which still takes `Optional<RetailerHttp>` empty in production), so no live traffic can start from this story.

## Verification

**Commands:**
- `backend/gradlew -p backend check` -- expected: BUILD SUCCESSFUL (Spotless, `-Werror`, tests, ArchUnit, text scan).
