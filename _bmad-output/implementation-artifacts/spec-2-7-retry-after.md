---
title: '429 and Retry-After handling'
type: 'feature'
created: '2026-11-18'
status: 'done'
baseline_revision: '31623dadc692ecc00c3ecee8fbb09733291c4fe4'
review_loop_iteration: 0
followup_review_recommended: false
context:
  - '{project-root}/_bmad-output/planning-artifacts/architecture/architecture-price-checker-2026-11-17/ARCHITECTURE-SPINE.md'
warnings: []
deferred: []
---

<intent-contract>

## Intent

**Problem:** A 429 today is a plain `Unavailable(HTTP_ERROR)`: the client does not read `Retry-After`, and the next Lookup (or a restart) sends again at once, so a rate-limited retailer never gets the pause it asked for (AC-11.4, S-5).

**Approach:** The transport passes the `Retry-After` header up. On a 429 `FetchClient` computes `notBefore` (delta-seconds, HTTP-date, or 60 s), calls `BlockStore.recordRetryAfter`, which persists it in the retailer's `blocks.json` entry and never moves it earlier, and waits and retries once only when `notBefore` is before the deadline. Every request is refused without sending while `BlockStore.notBefore` (later of `blocks.json` and `runtime.yaml` `notBefore`) is in the future (AD-6 check 5).

## Boundaries & Constraints

**Always:** time from the injected `Clock`, waits through the injected `Sleeper` (T-5); a 429 is never a Block (`isBlocked` unchanged, no WARN line); the 429 retry is at most one and separate from the 5xx/timeout retries; headers, user agent and route never change on the retry (S-6); `notBefore` writes use the existing atomic whole-map write; an unparseable `runtime.yaml` `notBefore` rejects the whole file (last good kept); a robots.txt 429 also records `notBefore` (no retry, robots stays unreachable). `blocks.json` parsing ignores unknown fields (story 2.6 deferral) but stays strict on the known ones.

**Never:** `/api/status` (2.9); wiring the fetch client into `LookupService`, the cache or row precedence (2.8); a new log event (AD-14 allowlist); a Block on 429; challenge detection on 429 bodies.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Delta inside deadline | 429 `Retry-After: 1`, deadline 4 s, then 200 | `notBefore = now+1s` persisted, one 1 s sleep, second request, page returned | — |
| HTTP-date | 429 `Retry-After: <IMF-fixdate now+2s>` | `notBefore` = that date | — |
| Missing / garbage | 429 with no header or `soon` | `notBefore = now+60s`, outside deadline: one request, `Unavailable(REFUSED)` | — |
| Second 429 | 429, wait, 429 again | two requests, `Unavailable(REFUSED)` | no third request |
| Never earlier | stored `notBefore` later than new 429's | stored value kept | — |
| Later Lookup inside pause | `notBefore` in future | nothing sent (robots.txt included), `Unavailable(REFUSED)`, `isBlocked` false | — |
| Restart | new `BlockStore` on same file | `notBefore` still honored | — |
| Runner pause | `runtime.yaml` `notBefore: <future ISO instant>` polled | nothing sent | bad value rejects file |
| Old Block + notBefore | entry with Block and `notBefore` | both kept on every write | — |
| Huge delta | `Retry-After: 99999999999999999999` | capped at 10 years, no overflow | — |

</intent-contract>

## Code Map

- `backend/src/main/java/ro/pricechecker/fetch/Transport.java` -- `Response(status, location, body)`; add `retryAfter` component, keep a 3-arg constructor so `StubTransport`/`BlockTest` builders still compile.
- `backend/src/main/java/ro/pricechecker/fetch/JdkTransport.java:57` -- read `Retry-After` with `firstValue`.
- `backend/src/main/java/ro/pricechecker/fetch/FetchClient.java` -- `follow` loop (status handling; 429 now falls into `status < 500` → `HTTP_ERROR`), `check(url)` gate (add notBefore after host check), `loadRobots` (`status != 429` branch), `block()` swallow pattern for `UncheckedIOException`.
- `backend/src/main/java/ro/pricechecker/registry/BlockStore.java` -- `Entry`, `record`, `write`, strict `parse` (`Reason.valueOf`).
- `backend/src/main/java/ro/pricechecker/registry/RuntimeSettings.java` / `RuntimeSwitch.java` -- add `notBefore(domain)`; SnakeYAML `SafeConstructor` turns an unquoted ISO timestamp into `java.util.Date`, so accept `Date` and ISO `String`.
- Tests: `fetch/BlockTest` (setup pattern: `MutableClock`, `RecordingSleeper`, `StubTransport`, `@TempDir`), `registry/BlockStoreTest`, `registry/RuntimeSettingsTest`, `fetch/StubTransport`.

## Tasks & Acceptance

**Execution:**
- `fetch/Transport.java`, `fetch/JdkTransport.java` -- carry `Retry-After`.
- `fetch/RetryAfter.java` (new) -- `Instant notBefore(Optional<String> header, Instant now)`.
- `registry/RuntimeSettings.java`, `registry/RuntimeSwitch.java` -- parse/expose `notBefore`.
- `registry/BlockStore.java` -- `recordRetryAfter`, `notBefore`, entry with optional Block and optional `notBefore`, tolerant of unknown fields.
- `fetch/FetchClient.java` -- 429 handling, notBefore gate, robots 429.
- Tests: `fetch/RetryAfterTest`, `fetch/RetryAfterHandlingTest`, additions to `BlockStoreTest`, `RuntimeSettingsTest`, `StubTransport.tooMany`.

**Acceptance Criteria:**
- Given a 429 with delta-seconds, HTTP-date or no/garbage `Retry-After`, when received, then `blocks.json` holds `notBefore` = now + value (60 s default), a later 429 never moves it earlier, and `isBlocked` stays false (2.7.1).
- Given `notBefore` before the deadline, then the client sleeps until it and sends exactly one more request; otherwise exactly one request and `Unavailable(REFUSED)` (2.7.2).
- Given a future `notBefore` from `blocks.json` (also after restart) or from `runtime.yaml`, when a new fetch runs, then nothing is sent and the outcome is `Unavailable` (2.7.3).

## Spec Change Log

## Review Triage Log

### 2026-11-18 — Review pass

Layers: Edge Case Hunter only. Blind Hunter, Verification Gap and Intent Alignment were skipped because the run budget was low; the pipeline's QA and code-review stages review the diff next.

- verdicts: 7 findings — high 0, medium 0, low 5, false 2, maybe-false 0
- findings:
  - `[low]` `[patch]` After a 429 the client waited for this response's own `notBefore`, even when a later stored one was already in place, so `check` refused after a wasted wait. Fixed: the wait and the deadline test now use `BlockStore.notBefore` (the later of all values), read after `recordRetryAfter`.
  - `[low]` `[reject]` A `notBefore` just before the deadline leaves the retry very little time, so it likely ends as TIMEOUT rather than REFUSED. The row is `UNAVAILABLE` either way, and a minimum-attempt guard adds a new tuning constant.
  - `[low]` `[reject]` The obsolete RFC 850 and asctime HTTP-date forms fall back to 60 s. Servers send IMF-fixdate (RFC 9110 §5.6.7), so this is rare, and the fallback still pauses.
  - `[false]` `[reject]` A past HTTP-date retries at once. That is the date the retailer named (RFC 9110), the retry is still at most one, and the rate-limit permit still applies.
  - `[low]` `[reject]` A hostile `Retry-After` can pause a retailer for up to 10 years, and `clear-block` does not end the pause. Honoring the retailer's own request is compliant (S-5), and AD-9 defines no operator clear for `notBefore`. Recorded as a residual risk.
  - `[low]` `[reject]` An unquoted date-only `runtime.yaml` value is taken as UTC midnight. Only the kill-switch script writes that file, and it writes full instants (AD-9). Telling the forms apart would need a custom YAML resolver.
  - `[false]` `[reject]` Claim: "sleeps then sends exactly one more request" fails when a concurrent later 429 arrives. Refusing then is the intended AD-6 check 5, and since the patch above the client no longer sleeps for a value it will refuse.

## Design Notes

`blocks.json` entry: `{"blockGeneration": 3, "blockedAt": "...", "reason": "HTTP_403", "notBefore": "<ISO instant>"}`; the Block fields are all present or all absent, `notBefore` optional, at least one of the two. "Not cached" in 2.7.3 holds by AD-11 (only FOUND/NOT_FOUND cached); the cache is wired in 2.8.

## Verification

**Commands:**
- `backend/gradlew -p backend check` -- expected: BUILD SUCCESSFUL

## Auto Run Result

Status: done

**Summary:** A 429 is now handled as a pause, not a Block. `fetch.RetryAfter` turns `Retry-After` into `notBefore`. It accepts delta-seconds or an IMF-fixdate. A missing or unparseable value means 60 s, and a value over 10 years is capped there. `BlockStore.recordRetryAfter` keeps `notBefore` in the retailer's `blocks.json` entry, never moves it earlier, and uses the same atomic write. `BlockStore.notBefore` returns the later of that value and `runtime.yaml`'s `notBefore`. `FetchClient` waits and retries once when the effective `notBefore` is before the deadline; otherwise the outcome is `Unavailable(REFUSED)`. While `notBefore` is in the future it refuses every request, robots.txt included, without sending. A robots.txt 429 records `notBefore` and is not retried.

**Files changed:**
- `fetch/Transport.java`, `fetch/JdkTransport.java`: `Response` carries `Retry-After`, and a 3-arg constructor is kept.
- `fetch/RetryAfter.java` (new): parses the header.
- `fetch/FetchClient.java`: the 429 branch, the `notBefore` gate in `check`, and the robots.txt 429.
- `registry/BlockStore.java`: `recordRetryAfter` and `notBefore`. An entry can hold a Block, a `notBefore` or both, and the parser ignores unknown fields (story 2.6 deferral).
- `registry/RuntimeSettings.java`, `registry/RuntimeSwitch.java`: parse and expose `notBefore`, quoted or unquoted. A bad value rejects the whole file.
- Tests: `RetryAfterTest` and `RetryAfterHandlingTest` (new). `BlockStoreTest`, `RuntimeSettingsTest` and `StubTransport` gain cases or helpers. 429 was removed from the old non-retried-4xx case in `FetchClientTest` and from the robots-error case in `RobotsTest`, because both expected the behaviour before this story.

**Review:** one patch (wait for the effective `notBefore`), nothing deferred, six rejected (reasons in the Review Triage Log).

**Follow-up review recommended:** false. The only patched entry was low.

**Verification:** `backend/gradlew -p backend check` passes after the patch (Spotless, compile with `-Xlint:all -Werror`, tests, ArchUnit, RetailerTextScan). No test is skipped, and every matrix row has a passing `ac_2_7_*` test.

**Residual risks:**
- `registry/RuntimeSettings.java` and `RuntimeSwitch.java` are outside the story's `touches` list. AC 2.7.3 needs the `runtime.yaml` `notBefore`, and `BlockStore` can only reach it through them.
- A Retry-After of up to 10 years is honored, and no operator action clears it short of editing `blocks.json`.
- Rolling back to the story 2.6 jar while `blocks.json` holds a `notBefore` stops that jar at start, because its parser is strict. From now on unknown fields are ignored.
- "Not cached" (2.7.3) relies on story 2.8 caching only FOUND and NOT_FOUND.
- `JdkTransportTest` has no case that reads `Retry-After` from a real response.
