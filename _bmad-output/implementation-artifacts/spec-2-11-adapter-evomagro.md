---
title: 'evoMAG Adapter from synthetic fixtures'
type: 'feature'
created: '2026-11-18'
status: 'done'
baseline_revision: '37236ecaa18be8277bca478f5094e7cd5d87fcf1'
review_loop_iteration: 0
followup_review_recommended: true
context:
  - '{project-root}/docs/standards/coding.md'
  - '{project-root}/docs/standards/testing.md'
warnings: []
deferred:
  - summary: >-
      Microdata step reads the first Product scope and the first itemprop=price anywhere inside it,
      so a nested related product or a multi-valued itemtype could give the wrong facts or none.
    evidence: |-
      Unverified (maybe-false): synthetic fixtures have no nested items; settle it against real
      stripped evoMAG pages recorded in story 2.13.
    location: >-
      backend/src/main/java/ro/pricechecker/retailer/evomagro/EvomagPage.java
    severity: medium (unverified)
  - summary: >-
      StubAdapters reads a real adapter's id by instantiating it with a no-argument constructor;
      an adapter with constructor arguments or one built by an @Bean factory would break it.
    evidence: |-
      Test-only infrastructure; holds for EvomagAdapter today. Revisit when 2.10 (cel.ro) lands
      and both stories touch StubAdapters.
    location: >-
      backend/src/test/java/ro/pricechecker/lookup/StubAdapters.java
    severity: low
---

<intent-contract>

## Intent

**Problem:** `evomag.ro` is configured in `config/retailers.yaml` but has no adapter, so no evoMAG product page can be turned into price, availability and link (story 2.11, FR-3).

**Approach:** Add one Spring bean `EvomagAdapter` in `ro.pricechecker.retailer.evomagro` that GETs only the curated product URL through `RetailerHttp` and extracts facts in the order JSON-LD → meta tags → its own selectors (schema.org microdata, which the register notes evoMAG uses), tested against hand-written synthetic fixtures in `fixtures/evomag.ro/`.

## Boundaries & Constraints

**Always:** price as integer bani or `null`, never 0 (C-7); keep only price, currency, availability, URL and GTIN (S-7); each extraction step a separate tested function (C-6); test names/tags carry the AC id (T-1); tests read fixtures only from `fixtures/evomag.ro/` through a fake `RetailerHttp` (T-2, T-3); `retailers.yaml` keeps `evomag.ro` `adapter: disabled` (AC-9.4).

**Never:** contact evomag.ro or any network; build a search URL or request anything other than the given product URL (S-4, AC-10.2); change `docs/source-register.md`, `config/*.yaml` or `extract`; handle deadlines, cache, logging or config in the adapter (AD-7); copy real retailer content into fixtures.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| JSON-LD page | `product-jsonld.html` (Product/Offer 1299.99 RON InStock, gtin13) | `Found(PageFacts(129999, RON, IN_STOCK, productUrl, gtin))` | none |
| Meta-only page | `product-meta.html` (`product:price:amount` 849.00) | `Found` priceMinor 84900 from meta step | none |
| Microdata page | `product-microdata.html` (itemprop price 2499.90, OutOfStock) | `Found` priceMinor 249990, OUT_OF_STOCK, from own selectors | none |
| Order | `product-mixed.html` (JSON-LD 100.00 and microdata 200.00) | JSON-LD wins: 10000 | none |
| No facts | `no-product.html` | `Unavailable(UNPARSEABLE)` | none |
| Unparseable price | `unparseable-price.html` (microdata price text "la cerere", RON, InStock) | `Found` with `priceMinor` null, currency RON | none |
| GET fails | `RetailerHttp.get` throws `IOException` | `Unavailable(HTTP_ERROR)` | caught in adapter |

</intent-contract>

## Code Map

- `backend/src/main/java/ro/pricechecker/adapter/{RetailerAdapter,RetailerHttp,AdapterOutcome,PageFacts,Availability,UnavailableReason}.java` -- read-only contract (AD-7).
- `backend/src/main/java/ro/pricechecker/extract/{StructuredData,MetaTags,Prices,ExtractedFacts}.java` -- reuse `fromJsonLd`, `fromMetaTags`, `Prices.toMinor(String)`; both helpers return `Optional.of` even with an all-null result, so the adapter decides fallback (2.1 deferred note). `SchemaAvailability` is package-private: map microdata availability by suffix after last `/` in the adapter.
- `backend/src/main/java/ro/pricechecker/lookup/AdapterCatalog.java` -- fails startup unless adapter ids match `retailers.yaml` one-to-one; `retailerId()` must be `evomag.ro`.
- `backend/src/test/java/ro/pricechecker/lookup/StubAdapters.java` -- registers a no-op adapter for every `retailers.yaml` id; picked up by `@SpringBootTest` scan (`AppConfigTest`, `ApprovalBypassTest`). With a real `evomag.ro` bean this duplicates the id, so it must skip ids already served by a real `RetailerAdapter` bean definition.
- `backend/src/test/java/ro/pricechecker/arch/ArchitectureRules.java` -- `RETAILER_ALLOWED = adapter, ean, extract, fetch`; `RetailerTextScan` exempts only `retailer/<id>/`, so selectors and the domain literal live there.
- Tests run with working dir `backend/`; repo files via `Path.of("..")` (see `BundledConfigTest`).

## Tasks & Acceptance

**Execution:**
- `fixtures/evomag.ro/{product-jsonld,product-meta,product-microdata,product-mixed,no-product,unparseable-price}.html` + `README.md` -- synthetic, facts only, no images/descriptions; README states they are synthetic until 2.13 -- T-2, AC 2.11.1/2.
- `backend/src/main/java/ro/pricechecker/retailer/evomagro/EvomagPage.java` -- package-private steps `structuredData`, `metaTags`, `ownSelectors` (microdata in the `itemtype` Product scope: `price` content-or-text, `priceCurrency`, `availability` href-or-content, `gtin13`), and `extract(html, url)` choosing the outcome -- C-6.
- `backend/src/main/java/ro/pricechecker/retailer/evomagro/EvomagAdapter.java` -- `@Component`, `retailerId()` = `evomag.ro`, one `http.get(productUrl)`, `IOException` → `Unavailable(HTTP_ERROR)` -- AD-7.
- `backend/src/main/java/ro/pricechecker/retailer/evomagro/package-info.java` -- package doc.
- `backend/src/test/java/ro/pricechecker/lookup/StubAdapters.java` -- skip ids that a real adapter bean already serves.
- `backend/src/test/java/ro/pricechecker/retailer/evomagro/{EvomagPageTest,EvomagAdapterTest}.java` -- matrix rows plus each step on its own; fake `RetailerHttp` that records URIs and serves fixture files -- AC 2.11.1–2.11.3.

**Acceptance Criteria:**
- Given the adapter and a fixture-serving `RetailerHttp`, when `fetchAndExtract` runs, then it requests exactly the product URL once and no URL with a query (`?q=`, `?sn.q=`) or `/search` path (AC 2.11.3).
- Given the bundled `Registry`, when read, then `evomag.ro` is present with `enabled()` false (AC 2.11.3).
- Given `@SpringBootTest` contexts, when they start, then `AdapterCatalog` sees exactly one `evomag.ro` adapter.

## Spec Change Log

## Review Triage Log

### 2026-11-18 — Review pass
- verdicts: 32 findings — high 0, medium 3, low 14, false 11, maybe-false 4
- findings:
  - `[low]` `[patch]` (blind) StubAdapters instantiates interface-typed definitions — now skips interfaces and abstract classes.
  - `[medium]` `[patch]` (blind) `availabilityOf` diverges from shared `SchemaAvailability` (`:`, spaces, `oos`) — now cuts at last `:`, strips non-letters, accepts `oos`; test with `schema:InStock` added.
  - `[low]` `[reject]` (blind) microdata reads only `gtin13` — register says evoMAG pages carry no EAN; fix adds branches.
  - `[false]` `[reject]` (blind) adapter does not check URL host/query — URLs come only from curated `known-eans.yaml`; host and robots.txt disallow (search) are enforced in the fetch client (AD-6, stories 2.4/2.5), not adapters.
  - `[false]` `[reject]` (blind) `ean` unused — EAN-on-page comparison is normalization in `lookup` (story 2.8); adapter returns `eanOnPage`.
  - `[low]` `[defer]` (blind) timeout mapped to HTTP_ERROR — no fetch client yet; timeout classes live in `java.net.http`, forbidden outside `fetch`; revisit in 2.4. (Recorded only in log; low.)
  - `[low]` `[reject]` (blind) steps not merged across — spec Design Notes choose whole-step selection; merging adds logic.
  - `[maybe-false]` `[defer]` (blind) nested items in Product scope — see `deferred` (needs real pages, 2.13).
  - `[false]` `[reject]` (blind) inline-HTML tests break "fixtures only" — inline strings read no files and no network; T-3 holds.
  - `[low]` `[reject]` (blind) `testsReadOnlyEvomagFixtures` is weak — all file reads go through `Fixtures`; a scan rule adds machinery.
  - `[low]` `[reject]` (blind) HTML parsed three times — microseconds against a 4000 ms deadline (same call as 2.1).
  - `[low]` `[reject]` (blind) no direct StubAdapters test — `EvomagWiringTest` and every `@SpringBootTest` exercise it.
  - `[maybe-false]` `[defer]` (edge) nested itemscope price — grouped with nested-items entry above.
  - `[maybe-false]` `[defer]` (edge) recommended Product earlier in DOM — grouped with nested-items entry.
  - `[low]` `[reject]` (edge) multi-valued/trailing-slash itemtype — unlikely on synthetic scope; grouped note in deferred item.
  - `[medium]` `[patch]` (edge) availability mapping divergence — same fix as above.
  - `[false]` `[reject]` (edge) RuntimeException escapes adapter — `LookupService` catches RuntimeException and marks the row UNAVAILABLE (C-8).
  - `[low]` `[defer]` (edge) HttpTimeoutException → HTTP_ERROR — same as blind timeout row.
  - `[false]` `[reject]` (edge) search URL guard in adapter — same refutation as blind URL row.
  - `[low]` `[defer]` (edge) StubAdapters breaks for constructor args — see `deferred`.
  - `[maybe-false]` `[reject]` (edge) `@Bean` factory adapter has null class name — no such adapter exists; if true only low.
  - `[medium]` `[patch]` (verification-gap) content-vs-text precedence unpinned — added `ac_2_11_1_ownSelectorsStepPrefersContentOverText`.
  - `[low]` `[patch]` (verification-gap) no-price fallback order unpinned — added `ac_2_11_2_withoutPriceFirstRecognizedStepWins`.
  - `[medium]` `[patch]` (verification-gap other) availability copy diverges — same fix as above.
  - `[low]` `[reject]` (verification-gap other) multi-valued itemtype — same as edge row.
  - `[false]` `[reject]` (intent) most matrix assertions below `fetchAndExtract` — `fetchAndExtract` only adds one GET; Found and HTTP-error paths are tested through it.
  - `[false]` `[reject]` (intent) curated binding not tested — curated resolution lives in `lookup`/`registry` (1.6), outside this story.
  - `[low]` `[reject]` (intent) disabled bean never called untested — `LookupService` returns DISABLED before the adapter (1.6 tests).
  - `[false]` `[reject]` (intent) fixtures-only checked as a constant — same as blind inline row.
  - `[false]` `[reject]` (intent) StubAdapters edit outside touches — required so `@SpringBootTest` contexts start with a real adapter bean.
  - `[false]` `[reject]` (intent) own selectors are generic microdata — the register names schema.org microdata as evoMAG's markup.
  - `[false]` `[reject]` (intent) spec not in reviewed diff — excluded on purpose; it is the claims file.

## Design Notes

Outcome choice: compute the three steps in order; take the first whose `priceMinor` is non-null; otherwise the first "recognized" result (any of price, currency, GTIN non-null or availability not UNKNOWN) with `priceMinor` null; otherwise `Unavailable(UNPARSEABLE)`. `eanOnPage` = the step's GTIN (evoMAG pages carry none per register, so usually null). `url` = the requested product URL.

## Verification

**Commands:**
- `backend/gradlew -p backend check` -- expected: BUILD SUCCESSFUL (Spotless, `-Werror`, ArchUnit, text scan, tests).

## Auto Run Result

- Summary: `EvomagAdapter` (`@Component`, id `evomag.ro`) GETs only the given product URL through `RetailerHttp` and extracts JSON-LD → meta tags → microdata (own selectors) via `EvomagPage`; first step with a price wins, else first step with any fact (price null), else `Unavailable(UNPARSEABLE)`; `IOException` → `Unavailable(HTTP_ERROR)`. `retailers.yaml` unchanged (`adapter: disabled`).
- Files: `retailer/evomagro/{EvomagAdapter,EvomagPage,package-info}.java`; tests `retailer/evomagro/{EvomagPageTest,EvomagAdapterTest,EvomagWiringTest,Fixtures}.java`; `lookup/StubAdapters.java` (skips ids served by a real adapter bean); `fixtures/evomag.ro/` six synthetic pages + README.
- Review: 4 patches (2 medium: availability mapping aligned with shared `SchemaAvailability`; content-over-text precedence test; 2 low: fallback-order test, StubAdapters skips interfaces/abstract), 2 items deferred (nested microdata scope, StubAdapters no-arg constructor), timeout classification noted for 2.4, the rest rejected with reasons in the triage log.
- Follow-up review recommended: true — two medium patches; unverified risk: the microdata step's availability mapping was rewritten after review and only checked against synthetic markup.
- Verification: `backend/gradlew -p backend check` — BUILD SUCCESSFUL after patches (EvomagPageTest 18, EvomagAdapterTest 5, EvomagWiringTest 1, all pass).
- Residual risks: real evoMAG markup may differ from the synthetic fixtures (story 2.13); story 2.10 (cel.ro) likely edits `StubAdapters.java` too, so expect a merge conflict there.
