# Price Checker: initial spec

Product intent for the BMAD planning workflows. The Analyst and PM agents read this first;
everything in the PRD should trace back to a section here. Owner: Adrian (product owner, sole approver).

---

## 1. Context

Price Checker is an example app built to demonstrate the BMAD framework and a team of specialized
AI agents (internal team talk, then a conference talk). It should still be specified and built as a
real, working product: the realism is what makes the demo credible. When a choice is between more
features and a clearer demo, choose the clearer demo.

## 2. Problem and value

Shoppers in Romania often see a product, in a physical store or online, and want to buy it at the
best price, but prices at the major online retailers change frequently and checking them by hand is
tedious. Price Checker lets a shopper scan a product's barcode once, see its current prices at
several Romanian online retailers, and get a push notification when the price drops or the item
comes back in stock.

## 3. Users

| User | Situation | Need |
|---|---|---|
| In-store shopper | Standing in a store with the product in hand | "Is this cheaper online right now?" |
| Patient buyer | Wants a product but not at today's price | "Tell me when it drops below X." |
| Presenter (demo) | Showing the app live on stage | Reliable flows that work every time, with a clear story |

## 4. Retailers (first release)

- eMAG (emag.ro): retailer and marketplace. Track two prices per product:
  - **featured offer**: the main price on the product page; drives alerts
  - **lowest offer** and its seller, using only what the product page shows
- Altex (altex.ro)
- PC Garage (pcgarage.ro)

A retailer is fetched only after it is marked Approved in `docs/source-register.md`.
If one is excluded, it is replaced with another Romanian retailer.

## 5. User journeys

### J1. Scan and compare
1. User opens the app and taps Scan.
2. The camera reads the product barcode (EAN).
3. The app shows the product found at each approved retailer: name, current price, retailer,
   last-checked time; for eMAG, the featured and the lowest offer.
4. If the match is uncertain, the user confirms which product is the right one.
5. If no retailer has the product, the app offers to paste a product link instead.

### J2. Paste a link (fallback)
1. User copies a product link from a supported retailer's site or app and pastes it,
   or shares it to Price Checker.
2. The app recognizes the retailer and product and shows it as in J1.

### J3. Follow and set an alert
1. From a product, the user taps Follow.
2. The user picks one or more alert rules: price at or below a target, price drop of at least X%,
   back in stock.
3. The product appears in the watchlist.

### J4. Receive an alert
1. When a scheduled check finds a change that meets a rule, the user gets a push notification.
2. Tapping it opens the product: current price, previous price, retailer, last-checked time,
   price history.
3. A button opens the product on the retailer's site.

### J5. Manage the watchlist
1. The user sees all followed products with their current best price and last-checked time.
2. The user can edit alert rules, unfollow a product, or delete all their data.

## 6. Functional requirements

| ID | Requirement |
|---|---|
| FR-1 | Scan EAN barcodes with the phone camera |
| FR-2 | Resolve an EAN to product pages at approved retailers; cache the mapping for all users |
| FR-3 | Accept a pasted or shared product link from a supported retailer |
| FR-4 | Show, per retailer: product name, price in RON, availability, last-checked time, link |
| FR-5 | For eMAG, show the featured offer and the lowest offer with its seller |
| FR-6 | Follow and unfollow products |
| FR-7 | Alert rules: target price, percentage drop, back in stock |
| FR-8 | Push notifications on Android and iOS |
| FR-9 | Price history per product and retailer |
| FR-10 | Delete all of the user's data from inside the app |
| FR-11 | Check followed products a few times a day; one check per product regardless of followers |

## 7. Non-functional requirements

| Area | Requirement |
|---|---|
| Freshness | Each followed product checked a few times a day; last-checked time always visible |
| Accuracy | A price change above a set threshold (e.g. 50%) is re-checked before any alert; a missing price is a failure, never "free" |
| Politeness | Fetching limits in section 8 are mandatory |
| Accessibility | WCAG 2.2 AA basics: screen-reader labels, text scaling, contrast, touch targets |
| Privacy | GDPR baseline; minimal data (section 9) |
| Reliability for demo | Core journeys J1, J3, J4 work on a real phone against real approved retailers |
| Currency and locale | Prices in RON with VAT included |

## 8. Fetching limits

1. Only retailers marked Approved in `docs/source-register.md`.
2. Public pages only, fetched logged out; never create accounts or accept terms to fetch.
3. Only followed products, plus one search per new barcode; no catalog crawling.
4. One fetch per product per check, however many users follow it.
5. Per-retailer rate and concurrency limits; honor Retry-After; back off on errors; conditional requests.
6. Honest user agent with a contact URL; no proxies, CAPTCHA solving or fingerprint spoofing. If blocked, stop and flag.
7. Store facts only (price, currency, availability, seller, timestamp, URL); no copied descriptions or images.
8. A kill switch per retailer that works without a deploy.

Extraction order: embedded structured data → meta tags → retailer-specific selectors → AI extraction as a validated fallback.

## 9. Data and privacy

- No user accounts or passwords: users are identified by an app-generated device ID (assumption; see open questions).
- Stored per user: device ID, push token, followed products, alert rules.
- Consent screen at first launch; privacy policy; in-app deletion of all data.
- All data in one EU Azure region.
- Store listings: Apple privacy labels, Google Data safety form; camera permission explained.

## 10. Out of scope

- Payments, purchases or checkout of any kind
- Monetization (ads, affiliate links, tips)
- Retailers outside Romania
- Searching retailers' catalogs by text
- Web app; tablet-optimized layouts
- User accounts with passwords or social login

## 11. Technical decisions already made

| Area | Decision |
|---|---|
| Mobile app | Flutter (Android and iOS) |
| Backend | Java 21, Spring Boot |
| API | Contract-first, `api/openapi.yaml`; generated Spring interfaces and Dart client |
| Database | Azure Database for PostgreSQL Flexible Server |
| Hosting | Azure Container Apps (API) and a scheduled Container Apps job (price checks), new dedicated subscription |
| Infrastructure | Terraform in this repo; plan on PR, apply after approval |
| Push | Firebase Cloud Messaging for Android and iOS |
| CI/CD | GitHub Actions with OIDC to Azure |
| Repo | One monorepo |

## 12. Demo success criteria

1. **Scan to alert:** scan a real product, follow it, trigger a check, receive a push alert.
2. **Compliance gate:** a retailer moves from Not checked to Approved before its adapter may run.
3. **Self-healing adapter:** a simulated layout change fails tests and the health monitor; an agent proposes the fix; tests pass; the human approves.
4. Every BMAD phase leaves a git tag and artifacts that can be shown on screen.

## 13. Quality goals

| Goal | Measure |
|---|---|
| Fewer defects | Defects found in review and tests vs. after merge, per epic |
| Better docs | Share of stories that updated docs or registers; no stale planning artifacts at phase end |
| Predictability | Time per BMAD phase and per story |

## 14. Open questions

- [ ] App language: Romanian, English, or both? (A conference audience suggests English UI with Romanian prices.)
- [ ] Confirm the account model: anonymous device ID only, or optional email for recovery?
- [ ] Limits per user: maximum followed products and alert rules?
- [ ] Price history: how far back to show (e.g. 30 or 90 days)?
