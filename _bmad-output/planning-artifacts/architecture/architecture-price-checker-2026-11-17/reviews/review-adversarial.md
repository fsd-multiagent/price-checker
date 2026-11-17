# Adversarial review: ARCHITECTURE-SPINE.md (price-checker)

Reviewer lens: build two units one level down that obey every AD literally yet are incompatible; plus non-Approved contact, double fetch, personal-data logging.

Verdict: the spine is strong on the fetch-client gate, but it has several places where two compliant stories diverge (cache/adapter ownership, where EAN mismatch and non-RON are applied, kill-switch polarity, health mode vs the HTTP/state rules) and two privacy gaps (free-text EAN in logs, Caddy/framework logs). It should not be final until the High items are closed.

## High

### H1. Cache stores no `eanOnPage`, but EAN mismatch is applied after the cache (AD-4, AD-7, AD-11, sequence diagram)
- AD-11 cache holds `status, priceMinor, currency, availability, url, fetchedAt` (no `eanOnPage`). AD-4 / the sequence diagram apply "EAN mismatch -> NOT_FOUND" and "non-RON -> price unavailable" in `lookup` after the cache. Story A applies both before caching (cache holds the final verdict); Story B applies them after (impossible for mismatch, since `eanOnPage` is gone; for currency B caches a non-RON FOUND and converts on each read). Both obey the ADs; cached results differ, and AC-6.3 ("only FOUND/NOT_FOUND cached") is ambiguous for a mismatch.
- Fix (tighten AD-11/AD-4): the cache loader is `adapter.lookup` followed by a pure `normalize(PageFacts) -> CachedVerdict` (EAN mismatch, non-RON, null price) inside the load; the cache stores only the normalized verdict. Remove `currency` from the cache entry or state it is always RON after normalize. Fix the sequence diagram to match.

### H2. Who calls the adapter and who owns the curated URL: `cache` vs `lookup` vs adapter (AD-1, AD-7, AD-10, AD-11, AD-12)
- The diagram shows `cache -> adapter`, but AD-1 allows no `cache -> retailer` arrow, and `lookup` is the only package allowed to see adapters. `RetailerAdapter.lookup(Ean, RetailerHttp)` receives no URL, and adapters have no arrow to `registry`, so nothing says who reads `known-eans.yaml`. Story A has each adapter read the YAML itself (violating the arrow rule or adding a hidden one); Story B has `lookup` resolve the URL and "NOT_FOUND with no request" there, so adapters never see it. Also two owners of "no request sent for unknown EAN".
- Fix: add AD: `lookup` owns `known-eans.yaml` lookup (curated resolution) and passes a `ProductTarget(url)` to the adapter: `lookup(Ean, ProductTarget, RetailerHttp)`. The cache is a generic `AsyncCache<Key, Verdict>` given a loader function by `lookup` (no dependency on adapters). Correct the diagram arrows.

### H3. `resolution` has two sources (AD-7 vs AD-8/AD-9)
- `RetailerAdapter.resolution()` (code) and `resolution` in `retailers.yaml` (config) both state the same fact. Developers set them differently; the register test checks only the YAML.
- Fix: keep it in YAML only and drop `resolution()` from the port, or add a startup assertion that the two are equal. Also add a startup/test bijection: every `retailers.yaml` id has exactly one adapter bean with that `retailerId()`, and vice versa (an adapter with no entry is never called; an entry with no adapter must fail the boot, not silently produce no row).

### H4. Kill switch polarity is ambiguous (AD-6 step 2, AD-9, AD-10)
- AD-6: "the Kill switch is on and there is no active Block"; AD-9: `killSwitch: on|off`; AD-10: "Kill switch off -> DISABLED"; AD-9: "missing from runtime.yaml is off (fail closed)". Everywhere else in the industry "kill switch on" means stopped. Developer of the `kill-switch` workflow ("on" = engage the kill) and developer of the registry reader ("on" = retailer allowed) build opposite behavior, and both fit the text. Fail-closed on a flipped reading would enable a stopped retailer.
- Fix: rename to an unambiguous field, e.g. `enabled: true|false` in `runtime.yaml` (workflow inputs `enable|disable|clear-block`), and use "Kill switch engaged" only for `enabled: false`. Update AD-6/9/10 and FR text mapping.

### H5. Health mode contradicts AD-1 and AD-9/AD-2 (AD-16)
- AD-16 has `health` read `GET /api/status` from production and open GitHub issues: it needs an HTTP client, but AD-1 forbids any HTTP client outside `fetch`, and `fetch` refuses any host except a retailer's register domain. Diagram has no `health -> fetch` arrow, yet "checks robots.txt for each adapter path" needs one. In health mode there is also no `runtime.yaml`/`blocks.json` defined: missing `runtime.yaml` means all retailers off (AD-9), so the Health check either checks nothing or each developer invents a runner config.
- Double fetch / limits: the runner process has its own rate limiter and cache, so a daily health run plus production traffic exceeds S-5 combined limits, and cache hits can mask a broken adapter.
- Fix: new AD for health mode: the status read and issue creation are done by the workflow (shell/`gh`), not by Java; the Java `health` profile gets a `runtime.yaml` that enables every Approved retailer, uses a no-op cache (always load), reuses fetch (add the `health -> fetch` arrow), runs its pass at least N minutes from the last production request or accepts the combined-limit risk in an ADR (S-5), and never writes `blocks.json` of production.

### H6. Free-text EAN and exception text can reach logs (AD-14, AD-7)
- AD-14 allows the field `ean` in every event. If the helper is called on the 400 path, a user-typed arbitrary string (could be a name, phone number, anything pasted) is logged; it is not validated yet. `Unavailable(reason)` is free text and `outcome`/exception messages can embed page fragments or full URLs.
- Fix: log `ean` only as a validated `Ean` value object (the helper accepts `Ean`, not `String`); invalid input logs `event=ean_rejected, code` only. `reason` is an enum, never a message. Add an ArchUnit rule: no `org.slf4j`/`System.out` use outside `LookupLog`.

## Medium

### M1. Logging holes outside `LookupLog` (AD-14, NFR-5, S-9)
- "Tomcat access logs off; proxy has no access log" does not cover Caddy's default error and TLS logs (they include `remote_ip`, headers), Spring's own framework/Tomcat logs (bad-request stack traces with the query string), or Docker log driver capturing them. The rule restricts a helper, not the logger.
- Fix: Caddyfile `log { output discard }` for the site and a global log filter that deletes `request>remote_ip, headers, uri`; Logback root set to WARN with the same JSON encoder and allowlist; no `X-Forwarded-For` handling enabled. Test: grep of container logs after a request in CI for the test client IP.

### M2. `/api/status` can hide a Block (AD-3, AD-10, AD-16)
- `state` has one value; a retailer both kill-switched and Blocked is `disabled` per AD-10 precedence, but AD-16 "fails blocked ones", so the Health check can pass over a Block it is meant to surface (the stated Prevents).
- Fix: `StatusResponse` carries `enabled` and `blocked` booleans (or `state` precedence defined as blocked > disabled for status only).

### M3. Block state: owner and concurrency unspecified (AD-6, AD-9)
- `fetch` detects Blocks, `registry` holds them, `blocks.json` is written "backend only (atomic write)". Two virtual threads blocking two retailers read-modify-write the whole file: lost update. Story A keeps blocks in memory in `fetch`, Story B in `registry`; `lookup` reads one and not the other.
- Fix: `registry.BlockStore` is the single owner (in-memory map + serialized write of the whole map, write-temp-then-rename); `fetch` calls `BlockStore.record`, `lookup` calls `BlockStore.isBlocked`. State that `fetch` and `lookup` never read the file.

### M4. `blockClearedAt` vs `blockedAt` clock skew (AD-9)
- Cleared by the GitHub runner/VM shell clock, compared with `blockedAt` from the backend `Clock`. A cleared timestamp slightly behind the VM keeps the Block active; slightly ahead can clear a Block that happened after a poll but within skew.
- Fix: use a monotonically increasing `clearGeneration` integer in `runtime.yaml` and store `blockGeneration` in `blocks.json`; Block is active while `blockedGeneration >= clearGeneration` (or let the backend, not the workflow, consume a clear request and write the timestamp). Also require the workflow to write `runtime.yaml` atomically (temp + `mv`) so the 15 s poll never reads half a file.

### M4b. Retries and `robots.txt` can fetch the same page more than once (AD-6, AD-11, S-3)
- Up to 2 retries means 3 requests to the same product page per Lookup; failures (`UNAVAILABLE`) are not cached, so every repeat Lookup of a failing retailer refetches 3x. Redirect hops within the same host are additional requests. robots.txt has a 24 h cache but no single-flight: concurrent first Lookups fetch it twice; no rule for robots.txt 4xx/5xx (RFC 9309: 5xx -> disallow all).
- Fix: state that S-3 means one logical lookup, one rate-limit permit per attempt (including redirects and robots.txt); retry only on 5xx/timeouts, never on 4xx; add a short negative cache (e.g. 60 s) for `UNAVAILABLE` (in the cache package, status not exposed as cached) so a refresh loop cannot hammer; make robots.txt loading single-flight and fail closed on 5xx/unparseable.

### M5. Deadline semantics with single-flight (AD-6, AD-9, AD-11)
- AD-9 has a per-retailer `timeout`, AD-11 a `deadline` "from retailers.yaml", AD-6 retries "inside the deadline". Two names for maybe the same value. A joiner of someone else's in-flight load has a shorter remaining deadline; if the load is not cancelled on the first caller's deadline, backoff retries continue after the response was already sent (violates "inside the deadline" and S-5 intent); if it is cancelled, the second caller double-fetches.
- Fix: one key `deadlineMs`; the in-flight load owns the deadline (starting at load start) and is never cancelled by a joiner leaving; a joiner whose deadline expires first returns `UNAVAILABLE` for its row only; the loaded result is still cached.

### M6. Domain types vs generated types (AD-3, AD-5)
- Generated Spring interfaces and models come from `openapi.yaml`. Story A returns the generated `ResultRow` from `lookup` (so `lookup` depends on `api`, the reverse of the arrow); Story B defines a domain `ResultRow` and maps. Two `ResultRow` types in one codebase.
- Fix: add: `lookup` returns domain `LookupResult`/`Row` records; only `api` imports generated types and holds the mapper; ArchUnit forbids imports of `..api.generated..` outside `api`.

### M7. AD-8 parser edge cases and www/host normalization (AD-6, AD-8)
- Duplicate rows for one domain (one `Approved`, one `Rejected`), a domain with a `www.` or scheme, backticks, or case differences, and `Approved` followed by extra text in the Status cell are unspecified; the register says one domain per row but a parser must be told. `retailers.yaml` ids that differ in case or with `www.` could match a different row.
- Fix: parser lower-cases, strips backticks; duplicate domain rows, an unknown status word, or a malformed date -> that domain not Approved and the whole register test fails; Status must match `^Approved \d{4}-\d{2}-\d{2}$` exactly; `retailers.yaml` id must equal the register domain byte for byte. Also state that the `fetch` host check is exact-match (`id` or `www.`+id), never suffix match (`evil-cel.ro`, `cel.ro.evil.com`).

### M8. Known-EAN URLs are an unchecked source of hosts (AD-12, AD-6)
- `known-eans.yaml` holds URLs. Nothing tests that each URL's host is the retailer's own register domain, that it is https, or that its path is robots-allowed; a wrong entry only fails at runtime (refusal). Also an entry for a non-Approved retailer id is dead data that could be wired later.
- Fix: add to the AD-8 test: every `known-eans.yaml` retailer id is Approved and in `retailers.yaml`; every URL host passes the AD-6 step 3 check; EAN keys pass the shared `Ean` validation; no query-string with the EAN as an unencoded value (S-11).

### M9. Health check artifact stores full page (AD-16, S-7, T-2)
- "Saves the fetched page (stripped per T-2) as an artifact": the page is retained in GitHub (including descriptions/images text) which S-7 forbids storing outside fixtures; "stripped" is undefined.
- Fix: artifact limited to the stripped fixture format of T-2 with retention <= 7 days, and named in AD-16; or store only the extracted facts and the missing field.

### M10. Adapter outcome mapping for HTTP statuses (AD-6, AD-7)
- A 404 from a curated URL: `NotFound` (cached 15 min) or `Unavailable` (not cached)? Fetch says 403/challenge -> Block; nothing says 404/410/301-to-homepage. Two adapters differ, so a product removed at one shop shows "Not found" and at another "Unavailable".
- Fix: add a table: 404/410 -> `NotFound`; 5xx, timeout, refusal -> `Unavailable(reason enum)`; 200 without a price -> `Found` with null price; 403/challenge -> Block + `Unavailable(BLOCKED)`.

## Low

- L1. `retailerName` exists in `retailers.yaml` and in the register's Retailer column. Declare `retailers.yaml` the display source; test they match, or drop the duplicate.
- L2. AD-5 non-FOUND rows "carry only" four fields; a FOUND row with `priceMinor` absent and `status=FOUND` needs `availability` and `url` still; state it in the OpenAPI schema with `oneOf` by status so clients cannot see a stray field. Also say whether `url` for FOUND is the curated URL or the final URL after redirect (same host).
- L3. AD-13: whitespace definition (Unicode vs ASCII, ` `) differs between Java `String.strip()` and JS `trim()`; add cases to `api/ean-cases.json` and define as "strip ASCII and Unicode whitespace per JS `trim()`". Also non-ASCII digits (`isDigit` accepts Arabic-Indic digits in Java): require `[0-9]`.
- L4. AD-2: a Compose redeploy may briefly run old and new backend together (start-first order), doubling limiters; state `stop-first` (`deploy.update_config.order`) / `docker compose up --force-recreate` with stop-first.
- L5. AD-15 `kill-switch` workflow edits a mounted file over SSH: specify that it never edits `blocks.json` and that `clear-block` is its only way to un-Block (matches H4/M4).
- L6. AD-11: cache TTL "configurable" and the deadline in `retailers.yaml`; say that the TTL is global in `application.yaml` so two stories do not add per-retailer TTLs.
- L7. Stack: versions unpinned (S-12 requires pinned versions); acceptable as deferred only if the scaffold story is a blocker for all others.

## Non-Approved contact / double fetch / personal data: summary

| Concern | Status |
|---|---|
| Non-Approved retailer contacted | Mostly closed by AD-6/AD-8. Remaining: M7 (parser edge cases, host matching), M8 (curated URLs unchecked), H5 (health mode runtime config could enable a retailer outside the prod `runtime.yaml`; define it from the same register parser). |
| Page fetched twice | M4b (retries, redirects, robots.txt single-flight, no negative cache), M5 (deadline vs single-flight), H5 (health run alongside production). |
| Personal data logged | H6 (free-text EAN, exception text), M1 (Caddy, framework, Docker logs). |
