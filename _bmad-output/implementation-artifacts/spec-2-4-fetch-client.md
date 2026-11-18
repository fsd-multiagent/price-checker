---
title: 'Shared fetch client with the approval gate and limits'
type: 'feature'
created: '2026-11-18'
status: 'done'
baseline_revision: '8674211b3b83551f181e924798c90d4556d1a661'
review_loop_iteration: 0
followup_review_recommended: false
context:
  - '{project-root}/_bmad-output/planning-artifacts/architecture/architecture-price-checker-2026-11-17/ARCHITECTURE-SPINE.md'
warnings: []
deferred:
  - summary: >-
      JdkTransport buffers the whole response body with no size cap.
    evidence: |-
      BodyHandlers.ofString reads everything into memory; an Approved retailer returning a huge or endless body could exhaust memory. The deadline now bounds the time but not the size.
    location: >-
      backend/src/main/java/ro/pricechecker/fetch/JdkTransport.java
    severity: medium
  - summary: >-
      RetailerLimiter waits for the concurrency permit on wall time, while the deadline comes from the injected Clock.
    evidence: |-
      Semaphore.tryAcquire(left) uses real time; two tests wait 20-50 ms for real (T-5). A fake or skewed Clock makes the permit wait ignore the deadline.
    location: >-
      backend/src/main/java/ro/pricechecker/fetch/RetailerLimiter.java:46
    severity: medium
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

### 2026-11-18 — Review pass

Layers: Edge Case Hunter only. Blind Hunter, Verification Gap and Intent Alignment were skipped because the run budget was low; the pipeline's QA and code-review stages review the diff next. One more finding came from the parent's own check of the diff.

- verdicts: 9 findings — high 0, medium 3, low 4, false 1, maybe-false 0
- findings:
  - `[low]` `[reject]` A retry the limiter refuses is reported as `REFUSED` instead of the original 5xx or timeout reason. The row is `UNAVAILABLE` either way (`LookupService` maps every `Unavailable` alike), and the fix adds a branch.
  - `[low]` `[reject]` The backoff check can leave only milliseconds for the retry. Rare with backoffs of 250 ms and 500 ms inside a 4000 ms deadline, and the fix adds a guard.
  - `[medium]` `[patch]` `HttpRequest.timeout` bounds only the wait for headers, so a slow body could run past the deadline. Fixed: `JdkTransport` now uses `sendAsync(...).get(timeout)`, cancels the request on timeout and throws `HttpTimeoutException`.
  - `[medium]` `[defer]` The response body is buffered with no size cap. A cap needs a new `BodySubscriber`; deferred.
  - `[false]` `[reject]` A `RuntimeException` from the transport escapes and fails the Lookup. `LookupService.safeRow` and `row` catch `RuntimeException`, so only that row becomes `UNAVAILABLE`.
  - `[medium]` `[defer]` The concurrency-permit wait uses wall time, not the injected Clock (T-5). The fix needs a Sleeper-driven wait loop; deferred.
  - `[low]` `[reject]` Claim: retries end only as `TIMEOUT` or `HTTP_ERROR`. Same root cause as the first row; same reason.
  - `[medium]` `[patch]` Claim: the per-request timeout keeps each attempt inside the deadline. Same root cause as the header-timeout row; fixed by the same patch.
  - `[low]` `[patch]` (parent check) In `FetchClientTest` the AC ids 2.4.1 (URL and host check) and 2.4.2 (approval gate) were swapped in test names and `@Tag`s, which breaks T-1 traceability. Fixed by swapping the labels.

## Design Notes

`RetailerHttp.get` returns only a body, so failures travel as `FetchFailure extends IOException` with `outcome()`; an adapter writes `catch (FetchFailure f) { return f.outcome(); }`. The limiter reserves the next rate slot (`max(now, next)`) only when it is before the deadline, then waits via `Sleeper`; concurrency is a fair `Semaphore` tried for the remaining deadline. The client is not yet wired into `LookupService` (which still takes `Optional<RetailerHttp>` empty in production), so no live traffic can start from this story.

## Verification

**Commands:**
- `backend/gradlew -p backend check` -- expected: BUILD SUCCESSFUL (Spotless, `-Werror`, tests, ArchUnit, text scan).

## Auto Run Result

**Summary:** `FetchClient` (in `fetch`) hands out a `RetailerHttp` bound to one retailer id. It checks every attempt, redirect hops and retries included, and sends nothing unless all of these hold:
- the retailer is Approved, its adapter is enabled and `fetching: allowed` is set;
- the URL is https on the register domain or `www.` of it, port 443, with no userinfo;
- a rate-limit and concurrency permit is free before the per-call deadline (`deadlineMs`).

Behaviour:
- Only timeouts and 5xx are retried, at most 2 times, waiting 250 ms then 500 ms through the injected `Sleeper`, and only inside the deadline.
- Other 4xx answers are never retried.
- 404 and 410 give `NotFound`.
- Every outcome carries `fetchedAt` from the injected Clock.
- Failures reach the adapter as a `FetchFailure` (an `IOException`) that carries the `AdapterOutcome`.
- The user agent is `price-checker/<version> (+<contactUrl>)`, built at startup. It comes from `price-checker.contact-url` (env `PRICE_CHECKER_CONTACT_URL`), and the app refuses to start when the URL is missing or invalid, or the string contains bot, robot, crawl or spider in any case.
- The JDK transport follows no redirects, keeps no cookies and uses no proxy.

The client is not wired into `LookupService` yet (story 2.8), so no live traffic can start from this story.

**Deviations from the spec:**
- The limiter takes the concurrency permit first and claims the rate slot at the moment of sending. This keeps two sends at least one interval apart.
- `UnavailableReason.REFUSED` was added in `adapter`, outside the story's `touches` list. It is needed for "refused, nothing sent".

**Files changed:**
- `backend/src/main/java/ro/pricechecker/fetch/FetchClient.java` -- bound client: gate, host check, redirects, retries, outcome mapping.
- `backend/src/main/java/ro/pricechecker/fetch/RetailerLimiter.java` -- per-retailer rate slot and fair semaphore.
- `backend/src/main/java/ro/pricechecker/fetch/UserAgent.java` -- builds and validates the user agent.
- `backend/src/main/java/ro/pricechecker/fetch/{Transport,JdkTransport,Sleeper,FetchFailure,FetchedPage}.java` -- the transport port, the JDK client bounded by the whole-exchange timeout, the injected wait, the outcome carrier and the page result.
- `backend/src/main/java/ro/pricechecker/FetchClientConfig.java`, `backend/src/main/resources/application.yaml` -- bean wiring and the contact URL.
- `backend/src/main/java/ro/pricechecker/adapter/UnavailableReason.java` -- `REFUSED`.
- Tests: `fetch/FetchClientTest`, `RetailerLimiterTest`, `UserAgentTest`, `JdkTransportTest` (loopback `HttpServer`), `FetchClientConfigTest`; helpers `StubTransport`, `RecordingSleeper`, `TestRegistries.fetchable`.

**Review:**
- 9 findings, from the Edge Case Hunter plus one from the parent's own check.
- Patched 2 entries: medium, the body read could run past the deadline; low, the AC 2.4.1 and 2.4.2 tags were swapped.
- Deferred 2 (medium): no cap on the body size, and the semaphore wait uses wall time.
- Rejected 4 low (refused-retry reason ×2, tiny retry timeout, all with reasons in the triage log) and 1 false (the `RuntimeException` escape).
- Three review layers were skipped for budget.

**Follow-up review recommended:** false. Patched counts by verdict: high 0, medium 1, low 1.

**Verification:** `backend/gradlew -p backend check` passed (Spotless, `-Werror`, all tests, ArchUnit, text scan), both before and after the patches. Every row of the I/O matrix has a passing AC-tagged test.

**Residual risks:**
- The user agent reads `price-checker/dev` until the jar manifest sets `Implementation-Version`.
- No test covers the Kill switch flipping while a request waits for a rate slot; the code checks again after the wait.
- robots.txt, Blocks and 403 handling, and 429/`Retry-After` remain for stories 2.5 to 2.7.
