# cel.ro fixtures

Pages for the cel.ro adapter tests (T-2). Each page holds facts only (S-7): price, currency,
availability and EAN. No names, descriptions, images, links or other content.

## Recorded pages (story 2.12)

`recorded-<ean>.html` were fetched from cel.ro on 2026-11-18, one per cel.ro Known EAN in
`config/known-eans.yaml`, through `FetchClient` (robots.txt checked, rate limit, user agent
`price-checker/dev (+contact URL)`), then passed through `PageStripper.strip`. The stripped page
is still the whole site layout (about 85 KB), so only its fact elements were kept, verbatim
except whitespace: the Product JSON-LD reduced to `@context`, `@type`, `sku`, `mpn` and the
offer's `@type`, `priceCurrency`, `price` and `availability`; the `.pret` price block; and the
`.elem.stoc` stock block.

What the recorded pages show:

- JSON-LD `Product` and `Offer` carry price, `RON` and availability. There is no `gtin13` and no
  EAN anywhere on the page, so `eanOnPage` stays `null` and the row stays `FOUND`.
- `#product-price` holds only the number; its sibling `.moneda` holds `Lei cu TVA`.
- `#info_stoc` (the stock element) is empty in the served HTML; cel.ro fills it with a script.
- No `.product-ean` element exists. The adapter keeps that selector as an `[ASSUMPTION]`.
- The curated URLs first in `known-eans.yaml` answered `301` to a new slug; the file now holds
  the targets, because a redirect hop needs a second rate slot and missed the 4 s deadline.

| File | What it covers |
|------|----------------|
| `recorded-5099206027282.html` | Recorded: 79 RON, in stock (JSON-LD); selector price `79` + `Lei cu TVA` |
| `recorded-0740617261219.html` | Recorded: 388 RON, in stock (JSON-LD); selector price `388` + `Lei cu TVA` |

## Synthetic pages (story 2.10)

Hand-written edge cases that real pages cannot show on demand. They use the selectors checked
against the recorded pages (`#product-price`, `#info_stoc`) and the assumed `.product-ean`. The
EAN `5941234567899` is made up. `product-jsonld.html` and `layout-changed.html` stay synthetic
because the cross-retailer tests (`LayoutChangeTest`, `LayoutChangeHealthCheckTest`) expect a
GTIN in them.

| File | What it covers |
|------|----------------|
| `product-jsonld.html` | JSON-LD Product + Offer: 1299.99 RON, in stock, gtin13 |
| `product-meta.html` | Open Graph product meta tags only: 849.50 RON, out of stock |
| `product-selectors.html` | No JSON-LD or meta: price `1.299,99 lei`, stock text, EAN element |
| `product-bad-price.html` | JSON-LD offer price `la cerere` (unparseable, price stays empty) |
| `product-mixed.html` | JSON-LD (1299.99 RON, in stock, no EAN) beats conflicting meta; EAN only in a labelled selector element |
| `product-meta-selectors.html` | Meta price 849.50 RON beats selector price `999,00 lei`; stock and EAN only in selectors |
| `product-selectors-bad-price.html` | Selectors only: price text `Pret la cerere` (unparseable), out of stock, EAN |
| `no-product.html` | Plain page with no product facts |
| `layout-changed.html` | Simulated layout change (story 4.2): JSON-LD offer price key renamed to `pretCurent` and `#product-price` renamed; currency, stock and EAN remain, so the price is missing. Only tests that expect the failure extract from it (`LayoutChangeTest`, `LayoutChangeHealthCheckTest`); the fixture facts-only scan also reads it |
