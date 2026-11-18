# cel.ro fixtures

Synthetic, hand-written pages for `CelroAdapterTest` (story 2.10, T-2). They are not recorded
from cel.ro: the domain is not `Approved` yet, so nothing was fetched. Story 2.12 replaces them
with recorded pages.

Each page holds facts only (S-7): price, currency, availability and EAN. No names,
descriptions, images or other content. The EAN `5941234567899` is made up.

The cel.ro selectors used in `product-selectors.html` (`#product-price`, `.stoc_produs`,
`.product-ean`) are an `[ASSUMPTION]` until 2.12 checks them against real pages.

| File | What it covers |
|------|----------------|
| `product-jsonld.html` | JSON-LD Product + Offer: 1299.99 RON, in stock, gtin13 |
| `product-meta.html` | Open Graph product meta tags only: 849.50 RON, out of stock |
| `product-selectors.html` | No JSON-LD or meta: price `1.299,99 lei`, stock text, EAN element |
| `product-bad-price.html` | JSON-LD offer price `la cerere` (unparseable, price stays empty) |
| `product-mixed.html` | JSON-LD (1299.99 RON, in stock, no EAN) beats conflicting meta; EAN only in a labelled selector element |
| `no-product.html` | Plain page with no product facts |
