---
title: "Product Brief Addendum: Price Checker"
created: 2026-11-12
updated: 2026-11-12
---

# Addendum: Price Checker

Depth that supports the brief but belongs to downstream documents: the PRD, the architecture, and the source register.

## Landscape scan (2026-11-12)

How each claim was checked: [V] = checked at the primary source; [S] = taken from search snippets or third-party pages; [?] = not confirmed.

### Romanian price tracking

| Service | Form | Barcode scan | Alerts | Notes |
|---|---|---|---|---|
| Compari.ro (Árukereső group) | Website, plus an app page | Advertised [S] | Advertised [S] | Not clear the app is still live [?]; the site launched a marketplace model in July 2025 [S] |
| Pricy.ro | Browser extension | No | Yes [S] | Claims to cover 200–1000+ stores |
| Istoric Preturi | Browser extension (also Firefox Android) | No | Target price [S] | Covers eMAG and others; last update Aug 2024 |
| xPrice | Browser extension | No | History and coupons [S] | eMAG, Altex, Flanco and others |
| price.ro, ShopMania | Website | [?] | [?] | Live, but what they offer is unclear |

### The retailers' own apps

- **eMAG** [V, App Store RO, v3.76.0, Nov 2026]: sends price-drop and back-in-stock alerts for favourites. It has image search ("Snap!") but no barcode scanning.
- **Altex**: has had an app since 2021, with Favorites [S]. Whether it sends price alerts is [?].
- **PC Garage**: no app or alerts found [?].

### Comparables abroad

- **Keepa** (iOS/Android): scan a barcode in a store; target-price and back-in-stock alerts. Amazon only.
- **ShopSavvy**: barcode scanning and alerts across many retailers. Focused on the US.
- **Google Shopping**: price tracking with alerts; Lens recognises a product from a photo rather than a barcode. Whether it is available in Romania is [?].
- **CamelCamelCamel**: history and alerts for Amazon only.

### Legal and terms-of-service signals (not legal advice)

- **eMAG terms** [V], https://www.emag.ro/help/termeni-si-conditii/
  - 4.2(j) prohibits using "any robot, algorithm or other automated means to extract data from the Site".
  - 6.4: content, prices included, may be used for personal, non-commercial purposes only.
  - 3.2: eMAG may impose a captcha on unusual traffic.
- **Altex and PC Garage**: their terms and robots.txt were not checked. This must be done before either is marked Approved in the source register.
- **Affiliate feeds as a sanctioned route**: Profitshare lists eMAG and PC Garage as advertisers [S].
- **30-day prior-price rule**: the Omnibus Directive, transposed in Romania by HG 686/2022 [S]. ANPC fined eMAG 50,000 lei over 30-day reference prices after Black Friday 2021 [S]. The CJEU ruling in Aldi Süd (C-330/23, 2024) says an announced discount must be measured against the lowest price of the previous 30 days.
  - These obligations apply to traders, not to comparison apps.
  - Even so, alerts and displays should use the app's own observed history.

## Sources

- https://www.compari.ro/static/aplicatie_pe_mobil.html
- https://chromewebstore.google.com/detail/pricy/jnpfnacconjipomhfkphknjfmcnhagpb
- https://addons.mozilla.org/addon/istoric-preturi/
- https://apps.apple.com/ro/app/emag-ro/id935571974
- https://apps.apple.com/app/keepa-price-tracker/id1518541385
- https://shopsavvy.com/app
- https://profitshare.com/en/advertiser-signup
- https://curia.europa.eu/jcms/upload/docs/application/pdf/2024-09/cp240152en.pdf
- https://www.wall-street.ro/articol/Companii/278750/anpc-a-amendat-cu-50-000-de-lei-emag-pentru-nereguli-de-black-friday.html

## Retailer terms and robots.txt check (2026-11-12)

Not legal advice. [V] = read at the source; [S] = search snippet only; [?] = could not be confirmed.

| Retailer | Anti-scraping clause in terms | robots.txt | Structured data | EAN on page | Verdict |
|---|---|---|---|---|---|
| evomag.ro | None. §6 is a general copyright clause [V] | Product pages allowed; search (`/*?q=`, `/*?sn.q=`) disallowed [V] | schema.org microdata (price, RON, availability, sku, mpn) [V] | No [V] | Permissive. **Chosen to replace eMAG** |
| cel.ro | None [V] | Product pages allowed. User agents containing `robot`, `spider`, `crawl`, `bot-` or `bot/` get `Disallow: /` [V] | JSON-LD Product and Offer [V] | Yes [V] | Permissive. **Backup** |
| vexio.ro | None naming robots; "affecting server performance" counts as fraud [V] | Product pages allowed; `/search` disallowed; Crawl-delay 1 [V] | [?] Cloudflare challenge | [?] | Permissive on paper; unreliable to reach |
| flanco.ro | "Abusive use" clause [S] | Product pages allowed; `ai-train=no` [V] | [?] 403 | [?] | Unclear |
| pcgarage.ro | Terms unreadable (403) [?] | Product pages allowed; `/alerta-pret*` and the `ChangeDetection` user agent blocked [V] | [?] | [?] | Unclear |
| altex.ro | Intellectual-property clause only [S]; its affiliate terms ban automated taking of information [S] | Fetch failed [?] | [?] | [?] | Unclear |

Sources:
- https://www.evomag.ro/pagini/termeni-de-utilizare/
- https://www.cel.ro/index.php?main_page=conditions
- https://www.vexio.ro/info/despre-noi/termeni-si-conditii/
- https://www.flanco.ro/termene-si-conditii
- https://www.pcgarage.ro/info/comenzi/termeni-si-conditii/
- https://altex.ro/termeni-utilizare/pag/
