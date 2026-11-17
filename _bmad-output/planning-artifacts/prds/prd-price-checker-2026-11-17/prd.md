---
title: "PRD: Price Checker"
status: in-review
created: 2026-11-17
updated: 2026-11-17
owner: Adrian (product owner, sole approver)
author: Onofrei (PM), headless, run 20261117-174705-full, stage requirements, round 2
inputs:
  - _bmad-output/planning-artifacts/briefs/brief-price-checker-2026-11-17/brief.md
  - _bmad-output/planning-artifacts/briefs/brief-price-checker-2026-11-17/addendum.md
  - docs/initial-spec.md (v2, 2026-11-12)
  - docs/standards/security-compliance.md
  - docs/source-register.md
---

# PRD: Price Checker

## 0. Document purpose

This PRD is for Safta (UX Designer), Loredan (Architect), Veronel (Security & Compliance), Eufrosina (QA) and the epics-and-stories step. It turns the product brief and `docs/initial-spec.md` into numbered requirements, each with acceptance criteria that have their own IDs. Tests must carry those IDs in their names or tags (T-1).

- FR-1 to FR-8 keep the numbers they have in spec §6. FR-9 to FR-11 are new: they turn the compliance gate, EAN resolution and block handling from spec §4 and §8 into requirements.
- Acceptance criteria are numbered `AC-<FR>.<n>` (for example `AC-3.2`). NFR criteria are numbered `AC-N<NFR>.<n>` (for example `AC-N3.4`).
- Every item in spec §7 and §8 maps to an NFR with acceptance criteria. The NFRs cite rules S-1 to S-13 (Veronel's stage-3 check).
- Every AC is verified by an automated test (T-1). Process steps that no test can verify (owner approvals, recordings, pre-talk checks) are not ACs; they are in §7.1 Release checklist.
- Terms in §3 Glossary are used exactly as defined. Inferred points are tagged `[ASSUMPTION]` and listed in §10. An `[ASSUMPTION]` never widens a rule in `docs/standards/`.
- **Deviation from spec §6 FR-3:** the spec shows a product name in each row. S-7 allows storing only price, currency, availability, timestamp and URL, and the Cache stores Result rows, so the first release shows no product name. §9 question 5 asks the owner whether to amend S-7.
- Technical choices that are already made (spec §10) and the options for EAN resolution are in `addendum.md`, not here.

## 1. Vision

Price Checker is a small website for shoppers in Romania. You type a product's EAN and get one table with the current price at each Approved retailer. The lowest price is highlighted. A retailer that fails says so in its own row and never breaks the page.

The product is the worked example in a demo of an automated SDLC run by specialized AI agents. It runs live against real retailers, because that is what makes the demo credible. When a choice is between more features and a simpler, clearer demo, the simpler demo wins (spec §1).

How the product reads retailer pages is part of the story: it only contacts retailers the owner has approved, it fetches politely and honestly, and it stores only facts.

## 2. Target users

### 2.1 Jobs to be done

- **Shopper:** "I have this EAN. What does it cost at each retailer right now, and where is it cheapest?" The shopper wants this without searching each site by hand, and wants to trust the answer: no invented prices, no silent gaps.
- **Presenter:** "Show the journey working live on stage against real retailers, and show each SDLC phase's output on screen." The presenter needs the demo to survive one retailer failing, the network being slow, and the same EAN being typed many times in rehearsal.

### 2.2 Non-users (first release)

- Shoppers outside Romania, or anyone looking for a product by name rather than EAN.
- Anyone who wants an account, price history or price alerts.

### 2.3 Key user journeys

- **UJ-1. Ioana checks a headphone price before buying.**
  Ioana, a shopper in Cluj, has a pair of headphones in her basket at one shop and the box's EAN-13 in front of her. She opens the website on her laptop (no sign-in) and pastes the 13 digits. Within about 5 seconds she sees one row per Approved retailer: retailer name, price in RON, availability, a link and when the row was fetched. The cheapest row is highlighted. She follows its link to the retailer's page. **Edge case:** she mistypes one digit; the check digit fails, she sees an error next to the input, and no lookup runs.

- **UJ-2. The presenter runs the lookup live while one retailer misbehaves.**
  On stage, the presenter types a demo EAN. One retailer answers; another times out. The table still renders: the first row shows its price and is highlighted; the second row says "Unavailable". The presenter types the same EAN again for the audience and it answers at once from the cache, with the original fetch time. **Edge case:** if the venue network is down, the presenter switches to the fallback video.

- **UJ-3. The presenter shows the compliance gate and the kill switch.**
  The presenter shows the source register, where Altex is Not checked, and shows that Altex never appears in the table. Altex's Adapter already exists, built and tested offline from saved pages, but is disabled. After the owner changes Altex to Approved (and only then), its Adapter may be enabled. Later the presenter turns one retailer's kill switch off in the config file; within a minute, without a deploy, that retailer's row shows "Disabled" and no request reaches it.

- **UJ-4. A layout change is caught before the audience sees it.**
  A retailer's product page changes (for the demo, a simulated change). The scheduled health check looks up the known EANs, extraction fails for that retailer, and the check fails visibly, naming the retailer and the EAN. That failure starts the self-healing story (spec §12, criterion 3).

## 3. Glossary

- **EAN** — An EAN-8 or EAN-13 product code: 8 or 13 digits, the last of which is a GS1 check digit.
- **Retailer** — A Romanian online shop listed in `docs/source-register.md`.
- **Source register** — `docs/source-register.md`. The source of truth for each Retailer's status. Only the owner changes a status.
- **Approved retailer** — A Retailer whose source-register status is `Approved YYYY-MM-DD`. Every other status (Not checked, Proposed, Rejected, Retired) is blocked.
- **Adapter** — The per-Retailer code that resolves an EAN to a product page and extracts the facts from it. One Adapter per Retailer.
- **Lookup** — One request by a Shopper for one EAN. A Lookup produces one Result row per Approved retailer.
- **Result row** — The row for one Retailer in the comparison table. It has a Row status and, when found, a Price, an Availability, a product link and a Fetch time.
- **Row status** — One of: `Found`, `Not found` (the Retailer does not stock or cannot resolve the EAN), `Unavailable` (timeout, error, Block or an unparseable page), `Disabled` (the Kill switch is off).
- **Price** — An amount in RON, VAT included, extracted from the product page. A Price that is missing or cannot be parsed is shown as "unavailable", never as 0.
- **Availability** — One of: In stock, Out of stock, Unknown.
- **Fetch time** — When the Retailer's page was actually fetched. A cached Result row keeps its original Fetch time.
- **Lowest price** — The smallest valid Price among `Found` Result rows whose Availability is not Out of stock.
- **Cache** — Short-lived in-memory storage of Result rows keyed by Retailer and EAN. It holds only the S-7 facts (price, currency, availability, Fetch time, URL) plus the Row status.
- **Kill switch** — A per-Retailer on/off setting in a config file the app reloads without a deploy.
- **Health check** — A scheduled Lookup of the Known EANs that fails visibly when an Adapter cannot extract the expected facts.
- **Known EANs** — A list, kept in the repo, of EANs per Retailer that are expected to be `Found`. Used by the Health check and as the demo EANs.
- **Block** — A Retailer refusing us: HTTP 403, a CAPTCHA or bot-challenge page, or a robots.txt that now disallows a path our Adapter uses.
- **Fallback video** — A pre-recorded video of UJ-1 and UJ-2 working against Approved retailers, used on stage if the live demo fails.

## 4. Features and functional requirements

### 4.1 EAN entry

**Description:** One input box on one page. The Shopper types or pastes an EAN. The page checks the format and the check digit before any Lookup, and the server checks again (S-11). Realizes UJ-1.

#### FR-1: Enter and validate an EAN

The Shopper can enter an EAN-8 or EAN-13 and start a Lookup only when it is valid. Realizes UJ-1.

**Acceptance criteria:**

- **AC-1.1** An input of exactly 8 or 13 digits with a correct GS1 check digit is accepted and starts a Lookup.
- **AC-1.2** An input with a length other than 8 or 13 digits, or with any non-digit character after trimming leading and trailing whitespace, shows an error message that names the problem (wrong length or non-digits). No Lookup is made.
- **AC-1.3** An input of the right length with a wrong check digit shows an error message that says the check digit is wrong. No Lookup is made.
- **AC-1.4** Leading and trailing whitespace in a pasted value is ignored [ASSUMPTION].
- **AC-1.5** The server rejects an invalid EAN with a 400 response and contacts no Retailer, even when the page's own check is bypassed (S-11).
- **AC-1.6** The Lookup can be started with the Enter key as well as with a button.

**Out of scope:** UPC-A, ISBN-only codes, text search, camera scanning.

### 4.2 Price lookup

**Description:** For a valid EAN the server asks every Approved retailer whose Kill switch is on, in parallel. Each Adapter resolves the EAN to a product page by a method that the Retailer's terms and robots.txt allow, fetches that one page and extracts the facts. Results are cached briefly. Realizes UJ-1, UJ-2, UJ-3.

#### FR-2: Look up every Approved retailer in parallel

The system looks up the EAN at every Approved retailer whose Kill switch is on, at the same time. Realizes UJ-1, UJ-2.

**Acceptance criteria:**

- **AC-2.1** A Lookup produces exactly one Result row per Approved retailer, including Disabled ones, and no row for any other Retailer.
- **AC-2.2** Requests to different Retailers run concurrently: with two Retailers each answering in 2 s, the Lookup completes in under 3 s, not 4 s.
- **AC-2.3** Each Retailer has its own timeout. A Retailer that does not answer in time gets Row status `Unavailable`; the other rows are unaffected.
- **AC-2.4** A Lookup makes at most one product-page fetch per Retailer per EAN, plus at most one resolution request where the Retailer's resolution method needs one (S-3). Retries of the same request after an error (AC-N3.4) are not extra fetches; they are capped at 2 per request and stay within the per-Retailer timeout [ASSUMPTION: cap of 2].

#### FR-9: Contact only Approved retailers (compliance gate)

The system contacts a Retailer only while the Source register marks it Approved. Realizes UJ-3.

**Acceptance criteria:**

- **AC-9.1** The app's retailer configuration lists, for each enabled Adapter, the Retailer it serves. A CI check fails when any enabled Adapter's Retailer is not Approved in the Source register (S-1).
- **AC-9.2** A Retailer that is Not checked, Proposed, Rejected or Retired is never contacted by a Lookup or by the Health check, and has no Result row.
- **AC-9.3** No code path, flag or environment variable lets a Lookup contact a Retailer that is not Approved.
- **AC-9.4** An Adapter may be built for a Retailer that is not Approved, but only from saved fixture pages (T-2); it ships disabled, and the CI check in AC-9.1 fails if it is enabled. Its tests never contact the Retailer (T-3).

Setting a Retailer to Approved is an owner action in the Source register, not an AC; agents may draft a row but never set Approved (S-1). See §7.1.

#### FR-10: Resolve an EAN to a product page

Each Adapter can find the product page for an EAN using only a method its Retailer allows. Realizes UJ-1.

**Acceptance criteria:**

- **AC-10.1** Each Adapter declares its resolution method (Retailer search, an external EAN catalog, or a curated EAN-to-URL list) in the retailer configuration, and a CI check fails when an enabled Adapter has no declared method or the method is not one of these three. The choice itself is recorded in an ADR (D-2).
- **AC-10.2** No Adapter requests a path that the Retailer's robots.txt disallows for our user agent; for evoMAG this means no search pages (S-4).
- **AC-10.3** When a Retailer cannot resolve the EAN by its method, the Result row has Row status `Not found`, not `Unavailable`.
- **AC-10.4** Every Known EAN resolves to a product page at the Retailers it is listed for.
- **AC-10.5** When the resolution method is search, the EAN is URL-encoded before it is placed in the request (S-11).

**Notes:** The first release promises the Shopper a Price for the Known EANs at every Approved retailer, and for any other EAN only where a Retailer's allowed method can resolve it [ASSUMPTION]. Options and trade-offs are in `addendum.md`; the choice is Loredan's (Architect) ADR.

#### FR-6: Cache results briefly

The system keeps each Result row for a short time so repeated Lookups do not hit the Retailers again. Realizes UJ-2.

**Acceptance criteria:**

- **AC-6.1** A second Lookup for the same EAN within the cache lifetime (default 15 minutes, configurable) makes no request to any Retailer whose row is cached.
- **AC-6.2** A cached Result row shows its original Fetch time, not the time of the second Lookup.
- **AC-6.3** `Found` and `Not found` rows are cached. `Unavailable` rows are not cached, so the next Lookup tries again [ASSUMPTION].
- **AC-6.4** After the cache lifetime ends, the next Lookup fetches again.
- **AC-6.5** The cache is in memory; a restart empties it. No database is used.

### 4.3 Comparison table

**Description:** One table, one row per Approved retailer, the Lowest price highlighted, and each failure contained to its own row. Realizes UJ-1, UJ-2.

#### FR-3: Show the facts for each Retailer

The Shopper sees, for each Retailer, the Price, Availability, product link and Fetch time. No product name is shown (see §0, S-7). Realizes UJ-1.

**Acceptance criteria:**

- **AC-3.1** A `Found` row shows the Retailer name, Price formatted in RON with VAT included (for example `1.299,99 lei`), Availability, a link to the product page and the Fetch time.
- **AC-3.2** The product link points to the Retailer's own product page and opens in a new tab.
- **AC-3.3** Fetch time is shown in Romanian local time (Europe/Bucharest) [ASSUMPTION].
- **AC-3.4** Prices from structured data are used first, then meta tags, then Retailer-specific selectors (spec §8 extraction order).
- **AC-3.5** A Price in a currency other than RON is not shown as a Price; the row shows the price as unavailable [ASSUMPTION].
- **AC-3.6** No product name, description or image is extracted into a Result row, cached or logged (S-7). A test asserts that a cached row holds only Row status, price, currency, availability, Fetch time and URL.

#### FR-4: Highlight the lowest price

The Shopper sees which Retailer is cheapest. Realizes UJ-1.

**Acceptance criteria:**

- **AC-4.1** The row with the Lowest price is highlighted visually and with a text label (for example "Lowest price"), so the highlight does not rely on colour alone.
- **AC-4.2** When several rows tie for the Lowest price, all of them are highlighted.
- **AC-4.3** A row whose Price is unavailable, or whose Row status is not `Found`, is never highlighted.
- **AC-4.4** An Out of stock row is not eligible for the Lowest price [ASSUMPTION].
- **AC-4.5** When no row is eligible, nothing is highlighted.

#### FR-5: Contain failures to their own row

A Retailer that does not stock the product, or fails, says so in its own row without failing the Lookup. Realizes UJ-2.

**Acceptance criteria:**

- **AC-5.1** A Retailer that does not stock the EAN shows Row status `Not found`.
- **AC-5.2** A Retailer that times out, returns an error, is Blocked or returns a page that cannot be parsed shows Row status `Unavailable`.
- **AC-5.3** When every Retailer fails, the page still renders the table, with one row per Approved retailer and a message that no prices were found.
- **AC-5.4** A failing Retailer never produces an HTTP 5xx for the Lookup as a whole.
- **AC-5.5** A missing or unparseable Price is shown as "unavailable", never as 0 or as an empty cell.

### 4.4 Operations

**Description:** The pieces that keep the live demo safe: a Kill switch per Retailer, a Health check that catches layout changes, and a stop on any Block. Realizes UJ-3, UJ-4.

#### FR-7: Turn a Retailer off without a deploy

An operator can turn any Retailer off or on by editing a config file. Realizes UJ-3.

**Acceptance criteria:**

- **AC-7.1** Each Approved retailer has a Kill switch in a config file that the app reloads without a restart or a deploy (S-8).
- **AC-7.2** Within 60 seconds of a Kill switch being turned off, no new request goes to that Retailer and its Result row shows Row status `Disabled` [ASSUMPTION: 60 s].
- **AC-7.3** Turning a Kill switch back on restores the Retailer within 60 seconds.
- **AC-7.4** A Kill switch cannot enable a Retailer that is not Approved (FR-9 wins).
- **AC-7.5** A config file that cannot be parsed leaves the last good settings in force and logs an error.

#### FR-8: Health check of every Adapter

A scheduled job looks up the Known EANs and fails visibly when an Adapter breaks. Realizes UJ-4.

**Acceptance criteria:**

- **AC-8.1** The Health check runs on a schedule, at least once a day, and can also be started by hand [ASSUMPTION: daily].
- **AC-8.2** It looks up each Retailer's Known EANs and checks that each one is `Found` with a valid Price, an Availability and a product link.
- **AC-8.3** When any check fails, the run ends with a failure status that the owner and the presenter can see without logging in to the VM, and its output names the Retailer, the EAN and what was missing. Where it runs is the ADR in §9 question 4.
- **AC-8.7** The Health check fetches each enabled Retailer's robots.txt and fails for that Retailer, as a Block (FR-11), when it disallows a path the Adapter uses for our user agent (S-4).
- **AC-8.4** A simulated layout change (a saved product page with its structured data and selectors altered) makes both the Adapter's tests and the Health check fail.
- **AC-8.5** The Health check obeys the same rules as a Lookup: Approved and enabled Retailers only, rate and concurrency limits, and the honest user agent (FR-9, NFR-3).
- **AC-8.6** Each Approved retailer has at least two Known EANs [ASSUMPTION].

#### FR-11: Stop on a Block

When a Retailer blocks us, the system stops contacting it and flags the Block. Realizes UJ-2.

**Acceptance criteria:**

- **AC-11.1** On a Block (HTTP 403, a CAPTCHA or bot-challenge page, or the robots.txt case in AC-8.7), the system stops sending requests to that Retailer until an operator clears the flag, and the Retailer's rows show `Unavailable` (S-6).
- **AC-11.2** A Block is logged at warning level or higher with the Retailer and the time, and makes the next Health check fail for that Retailer.
- **AC-11.3** The system never retries a Block through another IP, a proxy, a different user agent or altered headers (S-6).
- **AC-11.4** HTTP 429 is not a Block: the system honors `Retry-After` and backs off (NFR-3).

## 5. Cross-cutting non-functional requirements

#### NFR-1: Responsiveness (spec §7)

- **AC-N1.1** For an uncached Lookup, the full table renders within 5 seconds of the request, because each Retailer has a timeout that ends its wait before then (for example 4 s) [ASSUMPTION: 4 s].
- **AC-N1.2** A fully cached Lookup returns within 1 second, measured at the server [ASSUMPTION].
- **AC-N1.3** While the Lookup runs, the page shows that it is loading.

#### NFR-2: Accuracy (spec §7)

- **AC-N2.1** The product never shows a Price it did not extract from the Retailer's page for that EAN.
- **AC-N2.2** A missing or unparseable Price is shown as "unavailable", never as 0, and is never the Lowest price (same as AC-5.5 and AC-4.3).
- **AC-N2.3** A page whose structured data shows a different EAN from the one requested is treated as `Not found` (where the page shows an EAN) [ASSUMPTION].

#### NFR-3: Polite fetching (spec §8; S-2 to S-7)

- **AC-N3.1** Only public pages are fetched, while logged out. No account is created, no login is made and no terms are accepted (S-2).
- **AC-N3.2** No crawling: no links followed, no pagination, no prefetching of other products (S-3).
- **AC-N3.3** Each Retailer has a rate limit and a concurrency limit taken from the Source register (for example cel.ro: 1 request per 2 s, concurrency 1). A request that would break them is not sent; when the wait would break NFR-1, the row shows `Unavailable` (S-5).
- **AC-N3.4** `Retry-After` is honored; errors are retried with exponential backoff and a cap, within the per-Retailer timeout (S-5).
- **AC-N3.5** Every request carries one honest user agent with a contact URL. A test asserts that it contains none of `bot`, `robot`, `crawl` and `spider`, in any case (S-6).
- **AC-N3.6** No proxies, CAPTCHA solving, fingerprint spoofing or header impersonation (S-6).
- **AC-N3.7** Only price, currency, availability, timestamp and URL are kept in the Cache and logs; no product names, descriptions or images are stored there (S-7). Recorded test fixtures have images and long descriptions stripped (T-2).
- **AC-N3.8** These rules are enforced in one shared fetch client that every Adapter must use, not left to each Adapter.

#### NFR-4: Accessibility (spec §7)

- **AC-N4.1** The EAN input has a visible label, and error messages are tied to the input so screen readers announce them.
- **AC-N4.2** The whole journey works with the keyboard only, with a visible focus indicator.
- **AC-N4.3** Text and the Lowest price highlight meet WCAG 2.2 AA contrast (4.5:1 for normal text).
- **AC-N4.4** The comparison table uses real table markup with column headers.
- **AC-N4.5** An automated accessibility check (for example axe) reports no serious or critical violations on the page.

#### NFR-5: Privacy (spec §7; S-9)

- **AC-N5.1** No accounts, sign-in or cookies that identify a user.
- **AC-N5.2** Application and reverse-proxy logs record the EAN and never the client IP address or the client user agent string (S-9, C-9).
- **AC-N5.3** No personal data is stored anywhere.
- **AC-N5.4** No third-party analytics or tracking scripts on the page [ASSUMPTION].

#### NFR-6: Demo reliability (spec §7, §12)

- **AC-N6.1** The Known EANs list contains at least one EAN listed for two or more Approved retailers; a test checks the list.
- **AC-N6.2** The end-to-end test (T-4) covers UJ-1 and UJ-2 against stubbed Retailers, including one Retailer timing out and a repeated Lookup served from the Cache.

The live run, the Fallback video and the green Health check before the talk are in §7.1.

#### NFR-7: Transport, hosting and supply-chain security (S-10, S-12, S-13)

- **AC-N7.1** The site is served only over HTTPS; port 80 only redirects to HTTPS (S-13).
- **AC-N7.2** No secrets are committed to the repo, images or logs; CI runs a secret scan (S-10).
- **AC-N7.3** Dependencies are pinned and CI runs a vulnerability scan that fails on high or critical findings (S-12) [ASSUMPTION: high threshold].

## 6. Non-goals

- Not a mobile app, and no camera barcode scanning.
- No accounts, watchlists, alerts, push notifications or price history.
- No payments or monetization.
- No text search by product name.
- No retailers outside Romania.
- No AI extraction while the app runs.
- Not a crawler or a price database: it never fetches more than one Lookup needs.
- No adoption or usage targets; this is a demo vehicle [ASSUMPTION].

## 7. First-release scope

**In:** FR-1 to FR-11 and NFR-1 to NFR-7; the Known EANs list; the Fallback video; a disabled Altex Adapter built from saved pages only (AC-9.4), never contacting altex.ro until Altex is Approved.

**Out:** everything in §6. Live lookups at any Retailer that is not Approved at release time. Altex stays Not checked on purpose so the compliance-gate demo has a Retailer to move live.

### 7.1 Release checklist (not ACs; checked by the owner, not by tests)

- **RC-1** The owner has set at least two Retailers to Approved in the Source register (S-1).
- **RC-2** The journey works live against at least two Approved retailers with a Known EAN that both stock.
- **RC-3** A Fallback video of UJ-1 and UJ-2 against Approved retailers is recorded and playable offline.
- **RC-4** The latest Health check is green before the talk.
- **RC-5** The EAN-resolution ADR exists and is in `docs/adr/README.md` (D-2).

## 8. Success metrics

**Primary (spec §12 demo criteria)**

- **SM-1 EAN to comparison:** the presenter types a real EAN live and sees Prices from at least two Retailers. Validates FR-1 to FR-5, NFR-6.
- **SM-2 Compliance gate:** a Retailer moves from Not checked to Approved before its Adapter is allowed to run (Altex by default; fallback in §9 question 6). Validates FR-9.
- **SM-3 Self-healing adapter:** a simulated layout change fails the tests and the Health check; that failure starts an agent that proposes a fix; the tests pass and a human approves it. Validates FR-8.
- **SM-4 Automated SDLC:** each phase starts from the previous phase's output and leaves a git tag and artifacts to show.

**Secondary (product integrity and process, spec §13)**

- **SM-5** Zero false Prices: no Price of 0 or Price for the wrong product in tests, Health check runs or the demo. Validates NFR-2.
- **SM-6** Uncached Lookups render within 5 s. Validates NFR-1.
- **SM-7** Defects found in review and tests compared with defects found after merge, per epic.
- **SM-8** No stale planning artifacts at the end of each phase.
- **SM-9** Time per phase and per story, and the number of human interventions.

**Counter-metrics (do not optimize)**

- **SM-C1** Number of Retailers: do not add Retailers to look impressive by relaxing the compliance gate. Counterbalances SM-1.
- **SM-C2** Cache hit rate: do not lengthen the cache lifetime to make the demo faster at the cost of stale Prices. Counterbalances SM-6.
- **SM-C3** Speed: do not loosen rate limits, timeouts or backoff to hit 5 s. Counterbalances SM-6.

## 9. Open questions

1. **EAN resolution method per Retailer (spec §11).** Retailer search, external EAN catalog or curated EAN-to-URL list. Owner: Loredan (Architect), as an ADR (D-2). Not blocking: FR-10 holds for any of them.
2. **Which Retailers get Approved (spec §11).** evoMAG and cel.ro are Proposed. Owner: Adrian (owner). Blocking for release (NFR-6), not for architecture or stories.
3. **Is cel.ro's search route allowed by its robots.txt?** Needs a source-register check before cel.ro can use search. Owner: Veronel (Security & Compliance), drafting the row.
4. **Where the Health check runs** (GitHub Actions against production, or on the VM) and how a failure starts the self-healing agent. Owner: Pacala (DevOps) and Loredan (Architect). Not blocking: AC-8.3 states only the outcome.
5. **Amend S-7 to allow storing the product name?** Spec §6 FR-3 shows a product name; S-7 does not allow storing it, so the first release omits it (§0). If the owner wants it back, Veronel (Security & Compliance) drafts an S-7 amendment for owner approval, then FR-3 and AC-3.6 change. Owner: Adrian. Not blocking.
6. **Altex terms and the SM-2 fallback.** The source register notes Altex affiliate terms that ban automated taking of information, so the owner may not approve Altex. Fallback for SM-2: move any other Not checked Retailer (or a Proposed one, evoMAG or cel.ro, left un-Approved until the talk) through the gate live. Owner: Adrian, with Veronel (Security & Compliance). Blocking for the demo only, not for architecture or stories.

## 10. Assumptions index

- §4.1 AC-1.4 — Leading and trailing whitespace in a pasted EAN is ignored.
- §4.2 FR-10 note — The release promises Prices for Known EANs at every Approved retailer, and for other EANs only where an allowed method can resolve them.
- §4.2 AC-6.3 — `Unavailable` rows are not cached.
- §4.3 AC-3.3 — Fetch time is shown in Europe/Bucharest time.
- §4.3 AC-3.5 — A non-RON Price is shown as unavailable.
- §4.3 AC-4.4 — Out of stock rows are not eligible for the Lowest price.
- §4.4 AC-7.2 — Kill switch takes effect within 60 s.
- §4.4 AC-8.1 — Health check runs at least daily.
- §4.4 AC-8.6 — At least two Known EANs per Approved retailer.
- §5 AC-N1.1 — Per-Retailer timeout around 4 s.
- §5 AC-N1.2 — Cached Lookup within 1 s.
- §5 AC-N2.3 — An EAN mismatch on the page means `Not found`.
- §4.2 AC-2.4 — Retries after an error are capped at 2 per request.
- §5 AC-N7.3 — The vulnerability scan fails on high or critical findings.
- §5 AC-N5.4 — No third-party analytics.
- §6 — No adoption or usage targets.
