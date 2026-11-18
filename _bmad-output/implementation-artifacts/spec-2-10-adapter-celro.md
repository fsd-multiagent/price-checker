---
title: 'cel.ro Adapter from synthetic fixtures'
type: 'feature'
created: '2026-11-18'
status: 'done'
baseline_revision: '37236ecaa18be8277bca478f5094e7cd5d87fcf1'
review_loop_iteration: 0
followup_review_recommended: false
context:
  - '{project-root}/docs/standards/coding.md'
  - '{project-root}/docs/standards/testing.md'
warnings: []
deferred: []
---

<intent-contract>

## Intent

**Problem:** No real adapter exists; cel.ro product pages cannot yet be turned into price, availability and link, so cel.ro can never appear in the table once Approved (story 2.10, FR-3).

**Approach:** Add a `CelroAdapter` Spring bean in `ro.pricechecker.retailer.celro` that GETs the curated product URL through the given `RetailerHttp` and extracts facts in C-6 order (JSON-LD via `StructuredData`, then `MetaTags`, then its own cel.ro selectors), tested only against hand-written synthetic fixtures in `fixtures/cel.ro/`.

## Boundaries & Constraints

**Always:** price in integer bani via `extract.Prices` (C-7: unparseable → `null`, never 0); `PageFacts.url` is the requested product URL; `eanOnPage` is the page's GTIN or `null`; each field (price, currency, availability, gtin) is taken from the first step that supplies it; cel.ro domain/selectors live only in `retailer/celro/` (C-5); fixtures hold facts only (T-2, S-7); tests carry `AC-2.10.x` tags (T-1).

**Never:** open a network connection or use jsoup `connect` (C-12, T-3); handle deadlines, cache, logging or config in the adapter (AD-7); compare `eanOnPage` with the request (normalization is story 2.8); change `config/retailers.yaml` (cel.ro stays `adapter: disabled`, AC-9.4); touch `docs/source-register.md`.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| JSON-LD page | `product-jsonld.html`: Product + Offer, price `"1299.99"`, RON, InStock, gtin13 | `Found(PageFacts(129999, "RON", IN_STOCK, url, gtin))` | none |
| Meta-only page | `product-meta.html`: OG `product:price:*`, availability out of stock | `Found` with meta facts, `OUT_OF_STOCK` | none |
| Selectors-only page | `product-selectors.html`: no JSON-LD/meta; price text `1.299,99 lei` in cel.ro price element, stock text, EAN element | `Found` with price 129999, currency RON, availability from stock text | none |
| Unparseable price | `product-bad-price.html`: JSON-LD offer price `"la cerere"` | `Found` with `priceMinor == null` | none |
| No product facts | `no-product.html`: plain page | `Unavailable(UNPARSEABLE)` | none |
| Transport failure | `RetailerHttp.get` throws `IOException` | `Unavailable(HTTP_ERROR)` | caught in adapter |

</intent-contract>

## Code Map

- `backend/src/main/java/ro/pricechecker/adapter/{RetailerAdapter,RetailerHttp,AdapterOutcome,PageFacts,Availability,UnavailableReason}.java` -- read-only contract (AD-7). `RetailerHttp.get(URI)` throws `IOException`; `fetchAndExtract` declares no checked exception.
- `backend/src/main/java/ro/pricechecker/extract/StructuredData.java` `fromJsonLd(String)` and `MetaTags.java` `fromMetaTags(String)` -- steps 1 and 2, return `Optional<ExtractedFacts>`; `Prices.toMinor(String)` for selector price text; `ExtractedFacts.Availability` maps by name to `adapter.Availability`. `SchemaAvailability` is package-private: map cel.ro stock text in the adapter.
- `backend/src/main/java/ro/pricechecker/lookup/AdapterCatalog.java` -- requires adapter ids == `retailers.yaml` ids one-to-one; production startup already fails until 2.11 adds evomag.ro (unchanged by this story).
- `backend/src/test/java/ro/pricechecker/lookup/StubAdapters.java` -- test `@Configuration` (component-scanned in `@SpringBootTest`) registering a no-op adapter for every `retailers.yaml` id; would collide with the real cel.ro bean (duplicate id) unless it skips ids served by real adapters.
- `backend/src/test/java/ro/pricechecker/arch/{ArchitectureRules,RetailerTextScan}.java` -- `retailer.<id>` may use only `adapter`, `ean`, `extract`, `fetch`; domain/selector literals allowed only under `retailer/<id>/`.
- `config/retailers.yaml` -- cel.ro `adapter: disabled`; read via `new Registry().retailers()` (`RetailerConfig`).
- Tests run with working dir `backend/`; repo files are read as `Path.of("../...")` (see `EanTest`).

## Tasks & Acceptance

**Execution:**
- `fixtures/cel.ro/*.html` + `fixtures/cel.ro/README.md` -- add the five synthetic fixtures in the matrix and a note that they are hand-written until 2.12 -- T-2.
- `backend/src/main/java/ro/pricechecker/retailer/celro/CelroAdapter.java` (+ `package-info.java`) -- `@Component`, public no-arg constructor, `retailerId()` = `cel.ro`; `fetchAndExtract` GETs once, then `extract(html, url)`; separate `fromSelectors(html)` step for cel.ro selectors -- C-6, AD-7.
- `backend/src/test/java/ro/pricechecker/retailer/celro/CelroAdapterTest.java` -- matrix cases through `fetchAndExtract` with a fake `RetailerHttp` that serves files only from `../fixtures/cel.ro/` and records requested URLs; a test that `retailers.yaml` keeps cel.ro disabled -- AC 2.10.1-2.10.3.
- `backend/src/test/java/ro/pricechecker/lookup/StubAdapters.java` -- skip ids already served by a real `RetailerAdapter` in `ro.pricechecker.retailer` (classpath scan, no-arg instantiation) so Spring test contexts keep exactly one adapter per id.

**Acceptance Criteria:**
- Given the adapter tests, when they run, then every page comes from `fixtures/cel.ro/`, `RetailerHttp.get` is called exactly once with the product URL, and no other I/O happens (AC 2.10.3).
- Given `@SpringBootTest` contexts, when they start, then `AdapterCatalog` holds the real cel.ro adapter and a stub only for evomag.ro.
- Given `backend/gradlew -p backend check`, when run, then Spotless, ArchUnit, text scan and all tests pass.

## Spec Change Log

## Review Triage Log

## Design Notes

cel.ro selectors are synthetic (`[ASSUMPTION]` until 2.12 records real pages): price `#product-price`, stock `.stoc_produs` ("în stoc" → IN_STOCK, "stoc epuizat"/"indisponibil" → OUT_OF_STOCK, else UNKNOWN), EAN `.product-ean`. Currency on the selector step is `RON` only when the price text carries `lei`/`RON`; otherwise `null` (never a guess, C-7). A page is `UNPARSEABLE` when all three steps give no price, no currency, `UNKNOWN` availability and no gtin.

## Verification

**Commands:**
- `backend/gradlew -p backend check` -- expected: BUILD SUCCESSFUL

### 2026-11-18 — Review pass

- verdicts: 32 findings — high 0, medium 1, low 8, false 13, maybe-false 2 (8 intent-alignment observations logged as low/false)
- findings:
  - `[low]` `[patch]` BH1 negated ("nu este în stoc") or diacritic-free stock text misread — `stockOf` now NFC-normalizes, treats a standalone `nu` with `stoc` as OUT_OF_STOCK and accepts `in stoc`; test `ac_2_10_1_negatedOrPlainStockText`.
  - `[medium]` `[patch]` BH2 field-by-field merge untested — added `fixtures/cel.ro/product-mixed.html` and `ac_2_10_1_earlierStepWinsAndLaterStepFillsMissingFields`.
  - `[low]` `[reject]` BH3 StubAdapters needs a no-arg adapter constructor — adapters take no dependencies by AD-7 (RetailerHttp is a parameter); fixing adds reflection branches for a case nobody meets yet.
  - `[low]` `[reject]` BH4 StubAdapters scan matches non-`@Component` classes — every adapter in `retailer.<id>` is a bean by AD-7; extra filter is added complexity for an unseen case.
  - `[low]` `[reject]` BH5 context test hard-codes `cel.ro`, `evomag.ro` — intended: story 2.11 must update it deliberately when evomag gets its real adapter.
  - `[false]` `[reject]` BH6 no rule stops `Jsoup.connect` in the adapter — `ArchitectureRules` already forbids `Jsoup.connect` and HTTP clients outside `fetch` (lines 139-167).
  - `[low]` `[patch]` BH7 labelled EAN text ("EAN: …") dropped — selector step now keeps digits only; covered by the mixed fixture.
  - `[false]` `[reject]` BH8 empty/null body may throw — `StructuredData`, `MetaTags` and `fromSelectors` all return empty on null/empty, giving UNPARSEABLE.
  - `[low]` `[patch]` BH9 `[ASSUMPTION]` marked only on the price selector — comment now covers all three selectors.
  - `[low]` `[reject]` BH10 `Availability.valueOf` by name — both enums are project-owned with identical constants; a rename breaks compile-adjacent tests immediately; unlikely in everyday use.
  - `[false]` `[reject]` BH11 spec file not in diff — by design, the spec is the claims file given to the edge-case layer.
  - `[false]` `[reject]` EC1 RuntimeException from `http.get` escapes — `LookupService` catches `Exception` per retailer (C-8); the fetch-client error contract is story 2.4.
  - `[low]` `[reject]` EC2 price and currency from different steps — follows the spec's field-wise rule; cel.ro pages are RON-only.
  - `[false]` `[reject]` EC3 price with null currency — normalization in story 2.8 turns null/non-RON currency into FOUND without price (epic context).
  - `[false]` `[reject]` EC4 gtin differing from requested EAN returned as Found — EAN-mismatch → NOT_FOUND is normalization (story 2.8); spec Never forbids it here.
  - `[false]` `[reject]` EC5 page with only gtin/availability is Found — spec I/O matrix defines UNPARSEABLE as no facts at all.
  - `[low]` `[patch]` EC6 negated stock text — same fix as BH1.
  - `[low]` `[patch]` EC7 diacritic-free/decomposed `î` — same fix as BH1 (NFC + `in stoc`).
  - `[maybe-false]` `[reject]` EC8 nested old/new price inside `#product-price` — synthetic selector; real markup is checked in 2.12; would only be low.
  - `[low]` `[patch]` EC9 labelled EAN — same fix as BH7.
  - `[low]` `[reject]` EC10 `valueOf` mapping — same as BH10.
  - `[low]` `[reject]` EC11 no-arg constructor — same as BH3.
  - `[low]` `[reject]` EC12 non-bean classes in scan — same as BH4.
  - `[maybe-false]` `[reject]` EC13 real adapter outside `ro.pricechecker.retailer` — ArchUnit/C-5 keep adapters in `retailer.<id>`; would only be low.
  - `[false]` `[reject]` EC14 tests run outside `backend/` — Gradle runs tests from the project dir; existing tests (`EanTest`, `BundledConfigTest`) use the same `../` convention.
  - `[medium]` `[patch]` VG1 merge precedence untested — same fix as BH2.
  - `[false]` `[reject]` IA1 purpose is Lookup-level but tests are adapter-level — cel.ro stays `disabled` (AC 2.10.3), so a Lookup would show DISABLED; Lookup wiring is story 2.12.
  - `[false]` `[reject]` IA2 step order never tested against itself — fixed by BH2 patch.
  - `[false]` `[reject]` IA3 UNPARSEABLE threshold reading — spec chose "no facts at all"; changing it is a spec edit.
  - `[false]` `[reject]` IA4 "read only fixtures" holds by construction — T-3 is met: test double is the only `RetailerHttp`; arch rules forbid other HTTP.
  - `[low]` `[reject]` IA5 meta/selector fixtures not in register — marked `[ASSUMPTION]` in README and code until 2.12.
  - `[low]` `[reject]` IA6 `StubAdapters.java` outside `touches` — required so test contexts keep one adapter per id; recorded in Code Map.

## Auto Run Result

- **Summary:** Added `CelroAdapter` (`retailer.celro`) that GETs the curated URL once via `RetailerHttp` and merges JSON-LD → meta → cel.ro selectors field by field into `PageFacts` (bani, currency, availability, URL, EAN); `IOException` → `Unavailable(HTTP_ERROR)`, nothing found → `Unavailable(UNPARSEABLE)`. cel.ro stays `adapter: disabled`.
- **Files:**
  - `backend/src/main/java/ro/pricechecker/retailer/celro/CelroAdapter.java`, `package-info.java` — the adapter.
  - `backend/src/test/java/ro/pricechecker/retailer/celro/CelroAdapterTest.java` — 12 tests, AC-2.10.1–2.10.3, fixtures only.
  - `backend/src/test/java/ro/pricechecker/retailer/celro/CelroAdapterContextTest.java` — context holds real cel.ro adapter, stub for evomag.ro.
  - `backend/src/test/java/ro/pricechecker/lookup/StubAdapters.java` — stubs only ids no real adapter serves.
  - `fixtures/cel.ro/*.html`, `README.md` — six synthetic fact-only pages.
- **Review:** patches applied: 9 rows (2 medium, 7 low → 4 distinct fixes); deferred: none; rejected: 23 (reasons in triage log).
- **Follow-up review recommended:** false (patched: high 0, medium 1 entry, low 3 entries).
- **Verification:** `backend/gradlew -p backend check` — BUILD SUCCESSFUL after patches (Spotless, compile, ArchUnit, text scan, all tests; cel.ro tests 12 + 1 pass).
- **Residual risks:** selectors and stock wording are synthetic until story 2.12; the real app still fails `AdapterCatalog` startup until story 2.11 adds evomag.ro (pre-existing).
