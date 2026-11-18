---
title: 'robots.txt enforcement in the fetch client'
type: 'feature'
created: '2026-11-18'
status: 'done'
baseline_revision: 'e8fc3c122c3748e5e6768ce0c3aece7d66778de3'
review_loop_iteration: 0
followup_review_recommended: false
context:
  - '{project-root}/_bmad-output/planning-artifacts/architecture/architecture-price-checker-2026-11-17/ARCHITECTURE-SPINE.md'
warnings: []
deferred:
  - summary: >-
      The single-flight wait in RobotsCache uses wall time, not the injected Clock or Sleeper.
    evidence: |-
      A waiter blocks on CompletableFuture.get(left), measured in real time; the remaining time comes from the injected clock (T-5). It is the same pattern as the concurrency semaphore wait already deferred in story 2.4, and the concurrent test waits on real time for up to 5 s.
    location: >-
      backend/src/main/java/ro/pricechecker/fetch/RobotsCache.java
    severity: medium
---

<intent-contract>

## Intent

**Problem:** `FetchClient` (story 2.4) sends any on-host path, so an adapter could request a path the retailer's robots.txt disallows for our user agent (S-4, AD-6 check 4, AC-10.2).

**Approach:** Before every page attempt (redirect hops and retries included) the bound client asks a per-host `RobotsCache` whether the path is allowed for the product token `price-checker`. The cache loads `https://<host>/robots.txt` once per host (single-flight), through the same gate, host check and rate/concurrency permit as any request, keeps a parsed answer 24 h, and fails closed per RFC 9309. Parsing and matching use crawler-commons 1.6 (architecture stack table).

## Boundaries & Constraints

**Always:** nothing is sent to a disallowed path; the robots.txt fetch takes a permit inside the caller's deadline; time from the injected `Clock`, waits from the injected `Sleeper` (T-5); tests use the stub transport only (T-3); test names and `@Tag`s carry the AC id (T-1).

**Never:** `BlockStore.record`, `blocks.json` or WARN logging for the Block (story 2.6); 429/`Retry-After` (2.7); prefetching robots.txt at startup; retrying a robots.txt fetch; following an off-host robots redirect.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Allowed | robots 200 `Disallow: /cauta` for `*`; GET `/p/1` | robots fetched, then page; 2 requests | none |
| Disallowed | same robots; GET `/cauta?q=1`, or a redirect hop to it | only the robots request is sent | `Unavailable(BLOCKED)` = Block candidate (2.5.1) |
| Our token | `User-agent: price-checker` group disallows `/p/` | `/p/1` not sent | `Unavailable(BLOCKED)` |
| Unparseable | 200 with a body that has no robots line (e.g. HTML) or a NUL char | everything disallowed, cached 24 h | `Unavailable(BLOCKED)` (2.5.3) |
| Empty / 4xx | 200 empty body, or 404/410/403 etc. (not 429) | allow all, cached 24 h (RFC 9309 §2.3.1.3) | none |
| Unreachable | 5xx, 429, timeout, I/O error, off-host or >3 redirects | everything disallowed for this request, not cached | `Unavailable(REFUSED)` (2.5.3) |
| Gate / permit | not allowed, or no permit before the deadline for the robots fetch | nothing sent | `Unavailable(REFUSED)`, not cached |
| Cache | second request within 24 h; after 24 h | no robots refetch; then one refetch | none |
| Concurrent | two first requests to one host | one robots request, both wait for it | waiter past its deadline → `REFUSED` |

</intent-contract>

## Code Map

- `backend/src/main/java/ro/pricechecker/fetch/FetchClient.java:122` -- `follow` loop: call `check(url)` then the robots check before each `attempt`; `attempt` (gate + permit + one send) is reused for the robots fetch.
- `backend/src/main/java/ro/pricechecker/fetch/UserAgent.java` -- value `price-checker/<v> (+url)`; add `productToken()` = `price-checker` for robots matching.
- `backend/src/main/java/ro/pricechecker/fetch/FetchFailure.java` -- `unavailable(reason, at)` factory reused.
- `backend/src/main/java/ro/pricechecker/adapter/UnavailableReason.java` -- existing `BLOCKED` marks the Block candidate; no change.
- `backend/src/main/java/ro/pricechecker/FetchClientConfig.java` -- 6-arg constructor stays; no change.
- `backend/src/test/java/ro/pricechecker/fetch/{FetchClientTest,FetchClientRecheckTest}.java` -- construct `FetchClient` with a `RobotsCache` pre-seeded allow-all so their request counts and sleeps stay as they are.
- `backend/src/test/java/ro/pricechecker/arch/ArchitectureRules.java` -- `fetch` may use `adapter`, `registry`; third-party libs are not restricted.

## Tasks & Acceptance

**Execution:**
- `backend/build.gradle.kts` -- add `com.github.crawler-commons:crawler-commons:1.6` -- RFC 9309 parser and matcher.
- `backend/src/main/java/ro/pricechecker/fetch/RobotsRules.java` -- wraps crawler-commons: `parse(body, token)` → `Optional` (empty when unparseable), `ALLOW_ALL`, `DISALLOW_ALL`, `allows(URI)`.
- `backend/src/main/java/ro/pricechecker/fetch/RobotsCache.java` -- per-host single-flight `ConcurrentHashMap<String, CompletableFuture<Verdict>>`, TTL 24 h from the clock; uncacheable verdicts are removed; package-private `put(host, rules)` for tests.
- `backend/src/main/java/ro/pricechecker/fetch/FetchClient.java` -- robots check per hop; robots loader with on-host redirects and the status mapping above; package-private constructor taking a `RobotsCache`.
- Tests: `fetch/RobotsTest` (FetchClient + stub transport: every matrix row, AC 2.5.1–2.5.3), `RobotsRulesTest` (parser cases); seed allow-all in the 2.4 tests.

**Acceptance Criteria:**
- Given a stub robots.txt disallowing a path, when it (or a redirect to it) is requested, then the stub records only the robots request and the outcome is `Unavailable(BLOCKED)` (2.5.1).
- Given two threads making the first request, when both finish, then exactly one robots request was recorded; given a 2000 ms interval, the page waits one slot after the robots fetch; given 24 h later, robots is fetched again (2.5.2).
- Given robots 5xx or unparseable, when any path is requested, then no page request is sent (2.5.3).

## Spec Change Log

## Review Triage Log

### 2026-11-18 — Review pass

Layers: Edge Case Hunter only. Blind Hunter, Verification Gap and Intent Alignment were skipped because the run budget was low; the pipeline's QA and code-review stages review the diff next.

- verdicts: 7 findings — high 0, medium 3, low 2, false 2, maybe-false 0
- findings:
  - `[medium]` `[patch]` crawler-commons turns a `Crawl-delay` above its 300 s maximum into ALLOW_NONE, so a valid file would block the whole host for 24 h. Fixed: `parser.setMaxCrawlDelay(Long.MAX_VALUE)`, with the test `ac_2_5_1_longCrawlDelayDoesNotDisallowEverything`.
  - `[low]` `[patch]` The unparseable check missed `Useragent:` and `User agent:`, which crawler-commons accepts. Fixed: the regex is now `user[- ]?agent`, with a parameterized test.
  - `[false]` `[reject]` A null 2xx body or a parser `RuntimeException` escapes `fetch`. `JdkTransport` reads with `BodyHandlers.ofString`, which never yields null; a `RuntimeException` still completes the single-flight future in `finally`, and `LookupService` catches `RuntimeException` per row.
  - `[low]` `[reject]` When `minIntervalMs` is close to `deadlineMs`, the robots fetch takes the rate slot and the first page request is refused. AC 2.5.2 requires the robots fetch to take a permit; the configured cel.ro and evoMAG intervals (2000 ms) fit inside the 4000 ms deadline, and the fix (prefetching) is outside the spec.
  - `[false]` `[reject]` A 1xx, 300 or 304 for robots.txt is unhandled. It falls into the unreachable branch: the page is refused and nothing is kept, which is the fail-closed behaviour RFC 9309 asks for.
  - `[medium]` `[defer]` The single-flight wait uses wall time (T-5). The same pattern as the 2.4 semaphore wait (already deferred); recorded in `deferred`.
  - `[medium]` `[patch]` (claim) A long Crawl-delay disallows everything, although the matrix lists only unparseable files. Same root cause as the first row; fixed by the same patch.

## Design Notes

Block candidate = `Unavailable(BLOCKED)`: story 2.6 turns it into `BlockStore.record`. Only a robots.txt that answered (explicit rule, or unparseable 2xx) gives `BLOCKED`; an unreachable one (5xx, 429, timeout) refuses with `REFUSED` and is not cached, so a transient outage does not become a 24 h stop or a Block. Robots is per host, so `www.a.example` has its own entry. The single-flight wait is `future.get(left)` on wall time, the same known limit as the concurrency semaphore (deferred in 2.4).

## Verification

**Commands:**
- `backend/gradlew -p backend check` -- expected: BUILD SUCCESSFUL (Spotless, `-Werror`, tests, ArchUnit, text scan).

## Auto Run Result

**Summary:** before every page attempt (redirect hops and retries included), the bound client in `FetchClient` asks a per-host `RobotsCache` whether robots.txt allows the path for the product token `price-checker`.
- robots.txt is loaded from `https://<host>/robots.txt` on a host's first request.
  - Two requests that arrive together share one load (single-flight).
  - The load goes through the same gate, host check and rate and concurrency permit as a page, inside the caller's deadline. It is never retried, and it follows only on-host redirects, at most 3.
- An answer is kept 24 h from the injected Clock.
- How each robots.txt result is handled:

| robots.txt result | Path is | Outcome |
|---|---|---|
| Rule disallows the path | not sent | `Unavailable(BLOCKED)`, the Block candidate for story 2.6 |
| Unparseable: NUL character, or no robots line (for example an HTML page) | every path disallowed | `Unavailable(BLOCKED)`, kept 24 h |
| Empty file, or a 4xx other than 429 | every path allowed | kept 24 h |
| Unreachable: 5xx, 429, timeout, I/O error, bad redirect, no permit | not sent | `Unavailable(REFUSED)`, not kept |

- Parsing and matching use crawler-commons 1.6, from the architecture stack table. `Crawl-delay` is ignored, because the register sets our rate.

**Files changed:**
- `backend/build.gradle.kts` -- adds `crawler-commons:1.6`, which also brings in `commons-io` and `slf4j-api`.
- `backend/src/main/java/ro/pricechecker/fetch/RobotsRules.java` -- wraps crawler-commons and decides what counts as unparseable.
- `backend/src/main/java/ro/pricechecker/fetch/RobotsCache.java` -- per-host single-flight cache with a 24 h TTL.
- `backend/src/main/java/ro/pricechecker/fetch/FetchClient.java` -- robots check per hop, robots loader, package-private constructor that takes a cache.
- `backend/src/main/java/ro/pricechecker/fetch/UserAgent.java` -- `productToken()`.
- Tests:
  - `fetch/RobotsTest` (35 cases) covers every matrix row and AC 2.5.1 to 2.5.3.
  - `fetch/RobotsRulesTest` (15 cases) covers the parser.
  - `UserAgentTest` has one added case.
  - `FetchClientTest` and `FetchClientRecheckTest` now use a cache pre-seeded allow-all.

**Review:**
- 7 findings, from the Edge Case Hunter. Three layers were skipped for budget.
- Patched 3 rows (two share a root cause): a long Crawl-delay blocked everything (medium), and `Useragent` spelling variants counted as unparseable (low).
- Deferred 1 (medium): the single-flight wait uses wall time.
- Rejected:
  - 1 low: robots taking the rate slot when the interval is close to the deadline. The AC requires the permit and the configured values fit.
  - 2 false: the null body and `RuntimeException` escape, and the unhandled 1xx/3xx statuses. Reasons are in the triage log.

**Follow-up review recommended:** false. Patched entries by verdict: high 0, medium 1, low 1.

**Verification:** `backend/gradlew -p backend check` passed before and after the patches (Spotless, `-Werror`, all tests, ArchUnit, retailer text scan). Every matrix row has a passing AC-tagged test.

**Residual risks:**
- The two-thread test waits on real time, up to 5 s, until the second thread is blocked.
- An unreachable robots.txt is refetched on every request until it answers. The rate limit still bounds this.
- The first request after a start, or after 24 h, spends one rate slot on robots.txt inside the page's deadline.
- `BLOCKED` is not yet recorded in `BlockStore` (story 2.6), and the client is not yet wired into `LookupService` (story 2.8).
