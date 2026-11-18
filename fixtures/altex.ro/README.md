# altex.ro fixtures

Hand-written, synthetic pages for the Altex Adapter tests (T-2, AC 4.1.1). None was fetched from altex.ro: the register Status is `Not checked`, so the domain is never contacted (S-1). The pages hold facts only (price, currency, availability, EAN), never a product name, description or image (S-7). The class names (`Price-current`, `Status-stock`, `Product-ean`) are invented for the selector step.

| File | Purpose | Expected outcome |
|---|---|---|
| `product-jsonld.html` | JSON-LD `Product`, plus conflicting meta and selector prices that must lose | `Found` 129999 RON, IN_STOCK, EAN 4006381333931 |
| `product-meta.html` | Open Graph product meta tags only, plus a conflicting selector price | `Found` 129999 RON, IN_STOCK, EAN 4006381333931 |
| `product-selectors.html` | Altex markup only; price in `lei` | `Found` 129999 RON, IN_STOCK, EAN 4006381333931 |
| `no-product.html` | No product facts | `Unavailable(UNPARSEABLE)` |
| `unparseable-price.html` | JSON-LD with price `la cerere` | `Found` with `priceMinor` null (C-7) |
