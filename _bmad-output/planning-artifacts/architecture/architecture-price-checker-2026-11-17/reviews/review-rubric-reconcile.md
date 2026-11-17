# Review: rubric and input reconciliation — ARCHITECTURE-SPINE.md (price-checker)

Reviewed: the spine against the PRD, the PRD addendum, EXPERIENCE.md, `coding.md`, `infrastructure.md`, `security-compliance.md`, `testing.md` and `source-register.md`. I also looked at `docs/adr/` to check the companion claim. I did not render the mermaid diagrams; I read their syntax only.

## Verdict

The spine is a strong, mostly consistent substrate. Its main ideas hold: one fetch client, a server that decides, three config files with one writer each, and a precedence rule. It is not final. The AD-1 package graph contradicts the adapter contract, so the ArchUnit rule cannot be written as stated. Several "Rules" (AD-1, AD-14) cannot be enforced by the mechanism they name. The outcome mapping for adapters and the fetch client (Block, 404, parse failure) is open, so two adapters will diverge. The deployment and operations dimension leans on ADRs 0014 to 0017, which do not exist. Gate commands (T-8) are deferred past the first stories.

## HIGH

### H1. AD-1 allowed-dependency graph contradicts the adapter contract (AD-1, AD-7, diagram 1)

- AD-1 says the arrows are "exactly" the allowed dependencies, and that `retailer.<id>` never depends on `lookup`. But `RetailerAdapter`, `AdapterOutcome`, `PageFacts` and `ResolutionMethod` have no home package.
  - If they live in `lookup` (the diagram labels the edge "RetailerAdapter port"), every adapter depends on `lookup`. That breaks the sentence under the diagram.
  - If they live elsewhere, that package is missing from the feature-package list.
- Arrows that the design needs but the diagram lacks:
  - `retailer.<id> → ean`, because `lookup(Ean, …)` takes an `Ean`.
  - `lookup → fetch`, because the Errors convention says "only `fetch` and `lookup` turn exceptions into `UNAVAILABLE`". Lookup must catch fetch's exception or refusal types.
  - `api → registry` (or an explicit status provider), because `/api/status` needs retailer state.
  - `health → fetch` and `health → registry`, because AD-16 reads status and checks robots.txt.
  - `cache → ` the type of the cached facts (`PageFacts`, `RowStatus`).
- Fix:
  - Add a package, for example `adapter` (port, `AdapterOutcome`, `PageFacts`, `ResolutionMethod`), that depends only on `ean`.
  - Redraw the arrows so the ArchUnit test can be copied from them.
  - Define the exception or refusal types and say which package owns them.

### H2. AD-1 and AD-14 "Rules" are not enforceable by the mechanism named (S-1 to S-6, C-5, C-12, C-9)

- "No class outside `retailer.<id>` contains a retailer domain, URL or CSS selector" cannot be checked by ArchUnit. ArchUnit sees bytecode dependencies, not string constants. Fix: add a source-scan test, run in the `tests` gate, that greps `backend/src/main` and `web/src` for every domain in `docs/source-register.md` and fails outside `retailer/<id>` and the config and fixture folders.
- "No class outside `fetch` imports `java.net.http` … or any other HTTP client library" is vague. jsoup is in the Stack, and `Jsoup.connect(url).get()` opens its own connection. That is the easiest C-12 bypass, and nothing in the spine names it. Spring `RestClient`, `RestTemplate` and `WebClient`, Apache `org.apache.hc`, `okhttp3` and `java.net.URL#openStream` are not named either. Fix: give an explicit deny-list (`java.net.http`, `java.net.URL*`, `java.net.Socket`, `org.springframework.web.client`, `org.springframework.web.reactive`, `org.apache.hc`, `okhttp3`, `org.jsoup.Jsoup.connect` and `org.jsoup.Connection`). Say that jsoup is used only through `Jsoup.parse(String)`. Add a dependency allow-list check.
- AD-16 says the health profile "reads `GET /api/status` from production". If that is Java code, it is an HTTP call outside `fetch` and violates AD-1. If it is a workflow step (curl), AD-16 is misleading. Fix: state that the workflow reads `/api/status` and passes the result into the health profile as a file or env value. Or declare a second named outbound port with its own ArchUnit exception.
- AD-14 "one `LookupLog` helper" is not enforced. Direct SLF4J use, `System.out`, framework loggers and stack traces can all bypass it. Fix: add an ArchUnit rule that only the logging package may use `org.slf4j` or `System.out`. Add a logback config that sets framework loggers to WARN with a pattern that has no MDC or remote-address fields. Add a test that a log line from an unexpected exception holds only the allowed fields.

### H3. The ADR companions the spine relies on do not exist or are not indexed (D-2, I-1 to I-10, S-10, S-12, NFR-7)

- The spine says every AD is recorded as an ADR and names `docs/adr/0006 … 0017`.
  - `docs/adr/` has 0006 to 0013 only. 0014, 0015, 0016 and 0017 are missing. That covers the NFR-7 and CI decisions, the deployment topology, the health check and the logging and privacy decision.
  - `docs/adr/README.md` lists only 0001, so none of 0006 to 0013 is indexed. D-2 requires the index.
- Consequence: AD-15 and AD-16 bind S-10, S-12, I-4, I-5, I-8 and AC-N7.2 and AC-N7.3 only by pointing at a missing ADR 0014. The spine itself decides none of them (see M4).
- `status: final` is premature.
- Fix: write ADR 0014 to 0017, add 0006 to 0017 to the index, then mark the spine final. Or state in the spine, one line per item, what the missing ADRs will decide.

### H4. Gate commands deferred past the first stories (T-8, AGENTS.md TODO "stage 4")

- AGENTS.md says the backend and web build, test and lint commands are a stage 4 TODO. The spine defers them to a "stage 9 PR". That PR follows a merge, so the first backend and web stories reach the "tests pass" gate with only the orchestrator command in the block. T-8 would not apply to them.
- The spine does not name the commands, only "proposed in ADR 0014".
- Fix: put the exact commands in the spine, in a form that satisfies the no-shell, no-`cd` rule. For example `./backend/gradlew -p backend check` and `npm --prefix web run check`. Cover the Playwright e2e and the `terraform_plan` gate (`terraform -chdir=infra …`). Raise the `AGENTS.md` PR before the first Java or React story, not after.

### H5. Outcome mapping between adapter, fetch client and row status is not decided (FR-5, FR-10, FR-11, AC-5.1/5.2/5.5, AC-10.3, AC-11.1 to 11.4, AC-8.7)

Several decisions are left to each adapter or each developer:

- Where "price missing or unparseable" (a FOUND row with `priceMinor` absent, AC-5.5) ends and "page cannot be parsed" (UNAVAILABLE, AC-5.2) begins.
- How HTTP statuses map to outcomes:
  - 404 or 410 on a curated URL: `NOT_FOUND` or `UNAVAILABLE`?
  - 5xx and timeouts: `UNAVAILABLE`.
  - Which statuses are retried. AD-6 says "retries errors at most 2 times" but 403, 429 and 4xx need explicit handling.
- 429 must not be a Block (AC-11.4). AD-6 honors `Retry-After` but never says so. A `Retry-After` longer than the remaining deadline has no rule.
- "A recognized challenge page" has no definition. Which markers? Does the detection live in `fetch` (generic) or in each adapter (the adapter contract has no way to report one)?
- A robots.txt disallow, or a robots.txt that now disallows a used path, must be a Block (AC-11.1, AC-8.7). AD-6 step 4 only refuses. It does not say that it writes `blocks.json`, logs at WARN (AC-11.2), makes the row `UNAVAILABLE` or makes the Health check fail as a Block. How does `crawler-commons` treat a robots.txt that returns 4xx or 5xx or times out? That case is open.
- The `reason` values of `Unavailable(reason)` are not an enum. The `outcome` log field vocabulary is therefore open.

Fix: add a mapping table to AD-6 and AD-7, with the status-to-outcome rows above, one challenge-page detector in `fetch`, "robots disallow = Block" and the `outcome` enum. Add a contract test per row.

## MEDIUM

### M1. Health mode on a GitHub runner: a second process breaks single-process assumptions (AD-2, AD-16, S-5, AC-8.4, AC-8.5, AC-11.1, T-2, I-10)

- The health run has its own rate limiter and its own block state. If it runs while a Shopper Lookup is in flight, the retailer sees two concurrent requests. That breaks the concurrency limit from the register (S-5, AC-8.5). AD-2 claims one shared limiter.
- A health-mode Block (403 or a challenge page from a GitHub datacenter IP) is not written to production's `blocks.json`. State this. Also decide whether such a Block is a false positive, because the runner IP differs from the VM IP. A green run does not prove production can fetch. Record that as a known limitation. S-6 forbids working around it.
- The mechanism by which the health profile learns kill-switch state is not stated. The runner has no `runtime.yaml`. Define the profile's config (retailers enabled; skip those the status endpoint marks `disabled`).
- AC-8.4 and UJ-4 need a way to simulate a layout change. The spine has none, and live health cannot be altered. Fix: let the health core run against `fixtures/<id>/` through a stub `RetailerHttp` (a `workflow_dispatch` input such as `fixtures=altered`). Say so.
- The stripper that applies T-2 to the saved page (AD-16, AC-N3.7, S-7) has no owner. A GitHub artifact of an unstripped page stores copied descriptions and images. Put the stripper in `extract`, test it, and use it for fixtures and for the health artifact.
- I-10 says the failing fixture is attached to the issue; AD-16 says an artifact. Reconcile (link to the artifact from the issue).
- Schedule the cron outside likely Shopper hours, or document the residual overlap risk.

### M2. The AD-8 consistency test needs data the parser does not read (AC-N3.3, AC-9.1, AC-10.1)

- The test fails when the rate limit is "looser than the register's Limits column". The parser reads only `Domain` and `Status`. The Limits column is free prose ("1 request / 2 s, concurrency 1"), and `—` for non-approved rows.
- Fix: either parse a defined `N request / M s, concurrency C` grammar and fail closed on a mismatch for an Approved row, or compare against a machine-readable limit field added through an owner-approved register change. Do not leave it as prose.

### M3. `runtime.yaml` lifecycle and the 60 s measure (FR-7, AC-7.2/7.3, AC-11.1, AD-9)

- Missing entry is off. But who creates the first `runtime.yaml`, and who adds an entry when a retailer is newly Approved? A newly approved retailer deploys as Disabled until someone runs `kill-switch`. Decide: Terraform or cloud-init seeds it, or the deploy job adds missing ids as `off`. State that deploy never overwrites it.
- AC-7.2 says 60 s from "a Kill switch being turned off". The only writer is a workflow (dispatch, runner start, SSH, edit) plus a 15 s poll. UX KF-3 expects the effect within a minute of the presenter's action. Say where the 60 s clock starts (file written), and that the presenter's flow budget includes the workflow time. Or allow the owner a direct documented edit path.
- Edits that replace the file (editors, `sed -i`) break single-file bind mounts. The table says the directory is mounted; say that the app watches the directory, and confirm it.
- `blockedAt > blockClearedAt` compares the VM clock with the clock of whoever writes `blockClearedAt`. Specify that `clear-block` writes the VM's `date -u`, not the runner's.

### M4. Deployment, environments and operations are only partly decided (I-3, I-4, I-5, I-8, S-10, S-12, S-13, NFR-7)

Decided: one prod environment, no staging, no metrics, Docker Compose, Caddy, GHCR, SHA tags. Not decided, and not marked deferred or open:

- SSH access (I-3, S-13). GitHub runner IPs are dynamic and wide, so "SSH from the deploy source" cannot be an IP rule. Choose: GitHub meta ranges in the NSG; or the workflow opens and closes an NSG rule through OIDC; or a self-hosted runner. This also bears on the policy "SSH only from GitHub Actions".
- I-4 remote Terraform state with locking; I-5 OIDC to Azure; I-8 tags and the Azure Budgets cost alert. None appears in the spine or in AD-15.
- NFR-7: secret scan (S-10, AC-N7.2), vulnerability scan with a threshold (S-12, AC-N7.3), dependency pinning and lock files. Gradle dependency locking, `npm ci` with a lock file, and image digest pinning are not named; there is no AD for the CI gate set.
- How the VM pulls from GHCR (public package or a pull token) and where that secret lives.
- Rollback (redeploy the previous SHA?). Container restart policy and health check, and the stop-then-start deploy order. A start-first rolling deploy would briefly run two backends, against AD-2.
- Log retention and rotation (the Docker log driver). The Caddy data volume must persist, or certificate re-issue hits Let's Encrypt limits.
- A contact URL for the user agent (source-register "Still needed", S-6). It needs an owner and a config location. There is no Open Questions section at all, although the owner-dependent items exist (contact URL; evoMAG's EAN route to hand to Veronel and the owner; two Approved retailers for RC-1; PRD Q2, Q3, Q5, Q6).

Fix: add one AD or Deferred line per item above. Add an "Open questions" section with owner and blocking status.

### M5. Privacy outside the app log (NFR-5, AC-N5.2, AC-N5.4, S-9, C-9)

- "The reverse proxy has no access log" is not enough. Caddy's default error and runtime logs include client IPs (for example TLS handshake errors with `remote_ip`). Fix: set the Caddy global `log` to a level and format that drops `remote_ip` and `client_ip`, or discards the `http.log.error` logger. Put it in `deploy/Caddyfile` and test it.
- AC-N5.4 (no third-party scripts) has no mechanism. Add a `Content-Security-Policy: default-src 'self'` header in the Caddyfile, plus a note that fonts are self-hosted.
- Also decide and state that Spring and Tomcat log no remote address and no query-string dumps at INFO or above.

### M6. EAN validation divergence between Java and TypeScript (AD-13, AC-1.2, AC-1.4)

- "Trim" and "any non-digit" differ by runtime. Java `Character.isDigit` and `String.strip` accept Unicode digits and other whitespace. JS `\d` is ASCII. `trim()` and `strip()` differ on some characters (U+0085, U+FEFF, NBSP).
- Fix: state "digits are ASCII 0 to 9 only" and "trim ASCII whitespace plus NBSP" (or one stated set). Add cases to `api/ean-cases.json`: Unicode digits, NBSP, inner spaces and dashes (UX says they are not stripped), a missing `ean` parameter, and a repeated parameter. Define missing parameter as `EAN_EMPTY`.

### M7. UX URL state and the k-count are not decided (EXPERIENCE.md "URL state", Voice and Tone, State Patterns)

- Loading copy is "Checking {k} retailers…". The UX allows the fallback "Checking retailers…" when k is not known before the response. The spine has `GET /api/status`, which could supply k, but it does not say that web calls it. It also does not say whether `/api/status` lists only Approved retailers (it must; Altex must not appear) or all configured ones. Fix: choose the simplest option, the fallback copy, and say `/api/status` is for the health workflow only and lists only Approved retailers. Or have web call it once on load.
- URL state: the spine says only "URL `/?ean=` via the History API". It leaves open `pushState` or `replaceState`, back and forward behavior (popstate re-runs the Lookup), and loading `/?ean=` with an invalid value (show the error or ignore it). Fix: one line in the Web state convention.
- The UX says a new submit replaces the current Lookup. State that the client aborts only its own request and the server's single-flight load continues (so the rate-limited fetch is not wasted or orphaned).

### M8. Test infrastructure decisions missing (T-2, T-3, T-4, T-5, AC-8.6, AC-N6.1, AC-N6.2)

- T-3: "CI uses stubs" does not say how real network is prevented. Fix: only the `health` profile wires a real transport; the default and test profiles bind a stub `RetailerHttp`; a test asserts it.
- T-4: how are "stubbed retailers" provided for Playwright? AC-N6.2 needs one retailer timing out and a repeat Lookup served from cache. Fix: a `stub` profile where `RetailerHttp` serves `fixtures/<id>/`, with a per-retailer delay or failure switch and a settable `Clock`.
- T-5: `Clock` is injected, but backoff, rate-limit waits and the 15 s `runtime.yaml` poll also need an injected scheduler or sleeper. The web needs an injected "now" for "today, 14:05".
- A test for `config/known-eans.yaml`: at least two EANs per Approved retailer (AC-8.6), at least one EAN for two or more retailers (AC-N6.1), every entry's retailer is in `retailers.yaml`. AD-8 lists the register tests; AD-12 lists none for this file.
- Availability `UNKNOWN` and AC-8.2 "an Availability": does it pass the health check?

### M9. Standards TBDs and deviations are not recorded (coding.md, infrastructure.md "Architect confirms or changes")

- C-B4 "static analysis TBD": the Stack names Spotless and google-java-format and ArchUnit, but no static analyzer, and no statement of "none". C-W4 is resolved by ADR 0013. State C-B4's resolution, and list the standards edits for Aglaia (Knowledge Keeper).
- Deviations that should be written down as confirmed or changed standard rules:
  - I-9 says "kill switches and retailer config live in a mounted file that is reloaded". The spine bakes `retailers.yaml` into the image (reload on deploy).
  - C-4 says configuration comes from environment variables. The spine uses YAML files and env vars for secrets and paths only.
  - I-10 says "failing fixture attached" (see M1).
- C-B2, C-B3 (constructor injection, no logic in controllers) and C-11 (folder `CLAUDE.md`) have no enforcement or seed entry. Add ArchUnit rules for field injection and controller dependencies, and `backend/CLAUDE.md` and `web/CLAUDE.md` to the Structural Seed.

### M10. Robots.txt fetches, rate limit and cache bounds (AC-2.4, S-3, S-5, AC-6.x, NFR-1)

- AD-6 sends robots.txt through the same limiter. With cel.ro at 1 request per 2 s, the first lookup after a start needs the robots fetch, a 2 s wait, and the page fetch, against a 4 s deadline. Decide whether robots fetches count toward AC-2.4's "at most one fetch per Retailer per EAN" and the rate limit. State that robots.txt is prefetched at startup or on first use outside the Lookup deadline, so it does not push rows to `UNAVAILABLE`.
- The cache has no size bound. A stream of random valid EANs fills memory with `NOT_FOUND` entries (curated lookups send no request for an unknown EAN). Fix: a Caffeine `maximumSize`, or do not cache the no-request `NOT_FOUND` (nothing to save).
- There is no stated limit on our own API (AD-2 notes one instance). Say that none is needed beyond the cache, or add one.

## LOW

- **Stack versions.** The "minimum line" rows ("3.5.x or newer", "8.x or newer", "latest at scaffold") are not a decision. Spring Boot 3.5 is out of open-source support by November 2026, and a newer line changes the generated-interface setup. Pin one Spring Boot major line, one Gradle major and one Vite major in the spine, so separate scaffold stories do not diverge. `status: final` with unchecked versions is inconsistent.
- **AD-6 redirects.** Redirects to the same host are allowed. State a hop cap and that each hop is re-checked against robots.txt and the host rule (S-3, S-4).
- **AD-6 vs AD-11 naming.** "The Lookup's deadline" and the "per-retailer deadline" are used interchangeably. Use one term.
- **AD-4 completeness.** AD-4 cites AC-4.1 to 4.5 but does not restate the Out of stock exclusion (AC-4.4). Add it to the Rule, or the tie-break by name for Found rows with an unavailable price.
- **AD-11 Cache types.** It holds `status`, so `RowStatus` must live in a package `cache` may depend on (see H1).
- **S-11 for search later.** AD-12 allows `search` by a future ADR, but AD-6's `RetailerHttp` has no typed query-building API. Specify that adapters pass parameters, not URLs, and the client encodes them.
- **AD-1 package naming.** `retailer.celro` versus the id `cel.ro`; add a test that maps them (a registry check that every id has exactly one package).
- **AD-2.** One backend container, but the health runner is a second process (M1); the Rule should say "one production process".
- **Mermaid.** No syntax problems found in the three diagrams. Quoted labels, `&lt;`, `&gt;`, the `subgraph` quoting and `par … end` all look valid. Two semantic nits: diagram 1 lacks the edges named in H1, and the sequence diagram shows `L->>C` then `C->>A`. That is consistent with AD-11 but hides the fetch refusals from the Lookup (see H5).
- **Placeholders.** No literal TBD or TODO text. Stack "latest at scaffold" and "verify at scaffold" are the only soft entries (Low, above).

## Coverage table

Covered well: AC-2.1 including Disabled (AD-8, AD-10); AC-N1.1, AC-N1.2 (AD-11); AC-3.5, AC-N2.3 (AD-4); AC-6.1 to 6.5 (AD-10, AD-11, restart empties the cache); AC-7.5 (AD-9); AC-9.1 to 9.4 (AD-8, fail closed); AC-10.1 to 10.4 (AD-12); AC-N3.5 to N3.8 (AD-6, AD-7); AC-11.3 (single fixed user agent, no other HTTP path); AC-8.1, AC-8.3, AC-8.5 (AD-16); S-1, S-2, S-4, S-6, S-8 (AD-6, AD-8, AD-9); S-3 for curated resolution; S-7 (AD-7, AD-11); S-11 (AD-13); I-1, I-6, I-7 (AD-15); T-1, T-7 (Conventions, AD-13); UX row order, wire formats and 400 copy mapping (AD-4, AD-5).

| Item | Status | Where in this review |
| --- | --- | --- |
| AC-7.2 60 s | AD-9 poll 15 s; start of the clock and workflow latency undecided | M3 |
| AC-8.3 / AC-8.7 | AD-16 covers failure visibility; robots as Block undecided | H5, M1 |
| AC-8.4 simulated layout change | no mechanism | M1 |
| AC-11.1 to 11.4 | 403 and challenge covered; robots-as-Block, 429, challenge definition, retry set missing | H5 |
| AC-N1.2 | cache returns first (AD-10, AD-11); fine | none |
| NFR-7 scans (AC-N7.2, AC-N7.3) | only via missing ADR 0014 | H3, M4 |
| I-3 | SSH source impossible by IP | M4 |
| I-4, I-5, I-8 | absent | M4 |
| I-9, I-10 | deviation unrecorded | M9, M1 |
| T-2 | stripper owner missing | M1 |
| T-3, T-4, T-5 | mechanisms missing | M8 |
| T-8 | deferred to stage 9 | H4 |
| C-B4 | static analysis unresolved | M9 |
| C-B1 | satisfied | none |
| C-W4 | resolved by ADR 0013 | none |
| S-9 | Caddy error log IPs | M5 |
| S-10, S-12 | in ADR 0014 only | H3, M4 |
| S-13 | SSH restriction | M4 |
| UX URL state | partly (History API) | M7 |
| UX k-count loading copy | undecided | M7 |
| UX "submit replaces Lookup" | undecided server effect | M7 |
