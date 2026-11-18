# evomag.ro fixtures

## Recorded pages (story 2.13)

`real-<ean>.html` are the live product pages of the evoMAG Known EANs in `config/known-eans.yaml`, recorded on 2026-11-18 by `backend/src/test/java/ro/pricechecker/fixtures/FixtureRecorder.java` through the shared fetch client (Approved check, robots.txt, rate limit, user agent), then `PageStripper.strip` and the recorder's `factsOnly` step. They keep only the page's own fact markup (S-7, T-2): one schema.org Product microdata scope with `sku`, `gtin`, `mpn` and a nested Offer (`priceCurrency`, `price`, `availability`, `itemCondition`). Names, descriptions, images, ratings, reviews (with reviewer names) and links are dropped. The live pages have no JSON-LD Product and no product meta tags, so the microdata step gives the facts.

| File | Facts |
|------|-------|
| `real-5099206027282.html` | Logitech M185: 59.99 RON, InStock, `gtin` 5099206027282 |
| `real-0740617263442.html` | Kingston A400 480GB: 499.99 RON, InStock, `gtin` 740617263442 (12-digit UPC-A; the Known EAN has the leading zero) |

To record again (owner-run, never in CI, T-3): `PRICE_CHECKER_RECORD_FIXTURES=evomag.ro backend/gradlew -p backend test --tests '*FixtureRecorder'`. It overwrites `real-<ean>.html` for every Known EAN; a Known EAN whose page is gone (the first A400 120GB URL redirected to its category page) then fails `EvomagRealPagesTest` until `config/known-eans.yaml` is corrected.

## Synthetic pages (story 2.11)

The other pages are **synthetic**: hand-written for story 2.11 to cover branches the real pages cannot show (JSON-LD, meta tags, step precedence, no product, unparseable price, layout change). They hold facts only: price, currency, availability and GTIN. The GTIN is the GS1 example code `5901234123457`.

| Synthetic file | What it exercises |
|------|-------------------|
| `product-jsonld.html` | JSON-LD `Product`/`Offer`: 1299.99 RON, InStock, gtin13 (step 1, structured data) |
| `product-microdata.html` | schema.org microdata only: 2499.90 RON, OutOfStock (step 1, structured data) |
| `product-meta.html` | Open Graph product meta tags only: 849.00 RON (step 2, meta tags) |
| `product-mixed.html` | JSON-LD 100.00 and microdata 200.00: JSON-LD wins |
| `product-meta-microdata.html` | Meta tags 300.00 and microdata 400.00: microdata wins (C-6) |
| `no-product.html` | No product facts at all: `Unavailable(UNPARSEABLE)` |
| `unparseable-price.html` | Microdata with price text "la cerere", RON, InStock: `priceMinor` null |
| `layout-changed.html` | Simulated layout change (story 4.2): JSON-LD offer price key and microdata `itemprop="price"` renamed to `pretCurent`; currency, availability and GTIN remain, so the price is missing. Only tests that expect the failure extract from it (`LayoutChangeTest`, `LayoutChangeHealthCheckTest`); the fixture facts-only scan also reads it |

Tests read these files through a fake `RetailerHttp`; nothing contacts the retailer (T-3).
