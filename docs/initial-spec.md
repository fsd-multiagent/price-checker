# Price Checker: initial spec (v2, simplified POC)

This is the product intent for the BMAD planning workflows. The Analyst and PM agents read it first, and everything in the PRD should trace back to a section here.

- **Owner:** Adrian (product owner, sole approver)
- **Version:** v2, 2026-11-12. It replaces v1, a mobile app with alerts, which is archived in `docs/archive/initial-spec-v1-mobile.md`.

---

## 1. Context

Price Checker is a proof of concept for learning, and then demonstrating, an **automated end-to-end SDLC run by orchestrated, specialized AI agents** (BMAD). It is shown first in an internal talk and then at a conference.

The app is the pretext. It is kept deliberately small so that the effort goes into orchestration, not product scope. When a choice is between more features and a simpler, clearer demo, choose the simpler demo.

## 2. Problem and value

A shopper in Romania has a product's EAN barcode number and wants to know what it costs at several online retailers right now, without searching each site by hand. Price Checker gives them one input box. They type an EAN and get a comparison table.

## 3. Users

| User | Need |
|---|---|
| Shopper | "What does this EAN cost at each retailer right now?" |
| Presenter (demo) | A flow that works reliably on stage and tells a clear story |

## 4. Retailers

- Candidates: evoMAG (evomag.ro), cel.ro, Altex (altex.ro), PC Garage (pcgarage.ro).
- A retailer is used only after it is marked **Approved** in `docs/source-register.md`.
- A retailer qualifies only if it can be **looked up by EAN** without breaking its terms or its robots.txt.
- The first release needs **at least two** approved retailers.
- Research to date is in `_bmad-output/planning-artifacts/briefs/brief-price-checker-2026-11-12/addendum.md`:
  - evoMAG disallows search in its robots.txt and does not show the EAN on its pages.
  - cel.ro shows the EAN on its pages.
  - Altex and PC Garage have not been verified.
- How to resolve an EAN to a product page at each retailer is an **open architecture question** (see §11).

## 5. User journey

1. The user opens the website and types or pastes an EAN (8 or 13 digits).
2. The site validates the EAN. A wrong format or a failed check digit gives a clear error message.
3. The site shows one row per approved retailer, with these columns: product name, price in RON, availability, and a link to the product page. The cheapest price is highlighted. Each row shows when it was fetched.
4. If a retailer does not have the product, or cannot be reached, its row says so. One failing retailer never breaks the page.

## 6. Functional requirements

| ID | Requirement |
|---|---|
| FR-1 | Enter an EAN-8 or EAN-13, validated by format and check digit |
| FR-2 | Look the EAN up at every approved retailer, in parallel |
| FR-3 | Show product name, price (RON, VAT included), availability, product link and fetch time for each retailer |
| FR-4 | Highlight the lowest price |
| FR-5 | Show "not found" or "unavailable" per retailer without failing the whole request |
| FR-6 | Cache each result for a short time (for example 15 minutes) so repeated lookups do not hit the retailers again |
| FR-7 | A per-retailer kill switch that works without a deploy |
| FR-8 | An adapter health check: a scheduled lookup of known EANs that fails visibly when a retailer's page layout changes |

## 7. Non-functional requirements

| Area | Requirement |
|---|---|
| Responsiveness | Results within about 5 seconds, or a per-retailer timeout |
| Accuracy | A missing or unparseable price is shown as unavailable, never as 0 |
| Politeness | The fetching limits in §8 are mandatory |
| Accessibility | Basic WCAG 2.2 AA: labels, keyboard use, contrast |
| Privacy | No user accounts and no personal data. The app logs only EANs, not IP addresses |
| Demo reliability | The journey works live against real approved retailers, with a pre-recorded fallback video |

## 8. Fetching limits

1. Only retailers marked Approved in `docs/source-register.md`.
2. Only public pages, fetched while logged out. One lookup per retailer per EAN, with no crawling.
3. Rate and concurrency limits per retailer. Honor Retry-After and back off on errors.
4. An honest user agent with a contact URL. Its name must avoid the substrings `bot`, `robot`, `crawl` and `spider`, because cel.ro's robots.txt blocks them. No proxies, no CAPTCHA solving, no fingerprint spoofing. If a retailer blocks us, stop and flag it.
5. Store facts only: price, currency, availability, timestamp and URL.

Extraction order: embedded structured data (JSON-LD or microdata), then meta tags, then retailer-specific selectors.

## 9. Out of scope

- A mobile app and barcode scanning with a camera
- User accounts, watchlists, alerts, push notifications and price history
- Payments and monetization
- Text search by product name
- Retailers outside Romania
- AI extraction while the app runs

## 10. Technical decisions already made

| Area | Decision |
|---|---|
| Frontend | React (single-page app) |
| Backend | Java 21, Spring Boot. The REST API is contract-first: `api/openapi.yaml` |
| Hosting | **One Azure Linux VM** in an EU region, running Docker Compose (frontend, backend, reverse proxy with TLS) |
| Data | No database required. Results are cached in memory, and kill switches live in a config file the app reloads |
| Infrastructure | Terraform in this repo for the VM, network and DNS. Plan on every PR; apply only after human approval |
| CI/CD | GitHub Actions. On merge to `main`, build the images and deploy them to the VM |
| Repo | One monorepo |

## 11. Open questions (for PRD and architecture)

- [ ] How to resolve an EAN at each retailer: through the retailer's own search (where robots.txt allows it), through an external EAN catalog, or through a curated EAN-to-URL list for the demo.
- [ ] Which two or more retailers pass the compliance gate.

## 12. Demo success criteria

1. **EAN to comparison:** type a real EAN live and see prices from at least two retailers.
2. **Compliance gate:** a retailer moves from Not checked to Approved before its adapter is allowed to run.
3. **Self-healing adapter:** a simulated layout change makes the tests and the health check fail. That failure triggers an agent, which proposes a fix. The tests pass, and a human approves the fix.
4. **Automated SDLC:** each phase is triggered by the output of the phase before it, and each one leaves a git tag and artifacts that can be shown on screen.

## 13. Quality goals

| Goal | Measure |
|---|---|
| Fewer defects | Defects found in review and tests, compared with defects found after merge, per epic |
| Better docs | No stale planning artifacts at the end of a phase |
| Predictability | Time per phase and per story; how often a human had to intervene |
