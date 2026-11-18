# evomag.ro fixtures

These pages are **synthetic**: hand-written for story 2.11, not recorded from evomag.ro. They stay synthetic until story 2.13 records real pages (AC 2.11.1 assumption).

They hold facts only (S-7, T-2): price, currency, availability and GTIN. No product names, descriptions, images or other retailer content. The GTIN is the GS1 example code `5901234123457`.

| File | What it exercises |
|------|-------------------|
| `product-jsonld.html` | JSON-LD `Product`/`Offer`: 1299.99 RON, InStock, gtin13 (step 1, structured data) |
| `product-microdata.html` | schema.org microdata only: 2499.90 RON, OutOfStock (step 1, structured data) |
| `product-meta.html` | Open Graph product meta tags only: 849.00 RON (step 2, meta tags) |
| `product-mixed.html` | JSON-LD 100.00 and microdata 200.00: JSON-LD wins |
| `product-meta-microdata.html` | Meta tags 300.00 and microdata 400.00: microdata wins (C-6) |
| `no-product.html` | No product facts at all: `Unavailable(UNPARSEABLE)` |
| `unparseable-price.html` | Microdata with price text "la cerere", RON, InStock: `priceMinor` null |

Tests read these files through a fake `RetailerHttp`; nothing contacts the retailer (T-3).
