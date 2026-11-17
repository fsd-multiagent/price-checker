---
title: "Product Brief: Price Checker"
status: draft
created: 2026-11-17
updated: 2026-11-17
source: docs/initial-spec.md (v2, 2026-11-12)
supersedes: _bmad-output/planning-artifacts/briefs/brief-price-checker-2026-11-12/brief.md
owner: Adrian (product owner, sole approver)
author: Pompiliu (Analyst), headless, run 20261117-174705-full
---

# Product Brief: Price Checker

## Summary

Price Checker is a small website for shoppers in Romania. You type a product's EAN and get one table with the product's current price at each approved Romanian online retailer. The cheapest price is highlighted.

The product does two jobs, in this order:

1. **It is the worked example for a demo.** The demo shows an automated, end-to-end SDLC run by specialized AI agents under BMAD. It is presented first in an internal talk and then at a conference.
2. **It is a real product.** It runs live against real retailers, because that is what makes the demo credible.

When a choice is between more features and a simpler, clearer demo, the simpler demo wins (spec §1).

## The problem

A shopper has a product's EAN and wants to know what it costs at several online retailers right now. Today the only way is to search each retailer's site by hand. Price Checker replaces those searches with one input box and one comparison table.

## Who it serves

| User | What success looks like |
|---|---|
| Shopper | Types an EAN and, within about 5 seconds, sees a trustworthy price per retailer, or a clear "not found" or "unavailable" |
| Presenter | The journey works live on stage against real retailers, and every SDLC phase leaves something to show on screen |

The presenter is a first-class user. Demo reliability is a product requirement, not a nice-to-have.

## The experience

1. The user opens the website and types or pastes an EAN-8 or EAN-13.
2. The site checks the format and the check digit. Bad input gets a clear error message, and no lookup is made.
3. The site looks the EAN up at every approved retailer in parallel. It then shows one row per retailer: product name, price in RON (VAT included), availability, a link to the product page, and when the row was fetched. The lowest price is highlighted.
4. A retailer that does not stock the product, times out or fails says so in its own row. One failing retailer never breaks the page. A missing or unparseable price shows as unavailable, never as 0.

Behind the page there are three supporting pieces:

- **A short cache** (for example 15 minutes). Repeated lookups, including demo rehearsals, do not hit the retailers again.
- **A per-retailer kill switch** that works without a deploy.
- **A scheduled health check** that looks up known EANs. It fails visibly when a retailer changes its page layout.

## Responsible fetching

How the product reads retailer pages is part of what the talk shows (spec §8). These rules are mandatory:

- **Compliance gate.** A retailer is contacted only after `docs/source-register.md` marks it Approved. A retailer qualifies only if it can be looked up by EAN without breaking its terms or its robots.txt.
- **Minimal fetching.** The product fetches public pages only, while logged out, with one lookup per retailer per EAN and no crawling. Each retailer has rate and concurrency limits. The product honors Retry-After and backs off on errors.
- **Honesty.** The user agent is honest and carries a contact URL. Its name avoids `bot`, `robot`, `crawl` and `spider`, because cel.ro's robots.txt blocks them. The product uses no proxies, no CAPTCHA solving and no fingerprint spoofing. If a retailer blocks it, fetching stops and the block is flagged.
- **Facts only.** The product stores price, currency, availability, timestamp and URL. It extracts in this order: embedded structured data, then meta tags, then retailer-specific selectors.
- **Privacy.** There are no accounts and no personal data. Logs record EANs, never IP addresses.

## Success criteria

**Demo (the primary measure, spec §12)**

1. **EAN to comparison.** The presenter types a real EAN live and sees prices from at least two retailers.
2. **Compliance gate.** A retailer moves from Not checked to Approved before its adapter is allowed to run.
3. **Self-healing adapter.** A simulated layout change makes the tests and the health check fail. That failure triggers an agent, which proposes a fix. The tests pass, and a human approves the fix.
4. **Automated SDLC.** Each phase starts from the previous phase's output, and each leaves a git tag and artifacts that can be shown on screen.

**Product integrity**

- Results arrive within about 5 seconds, or a per-retailer timeout cuts the wait short.
- The product never shows a false price: a missing price is never shown as 0 or as the cheapest.
- The site meets basic WCAG 2.2 AA: labels, keyboard use and contrast.

**Process quality (spec §13)**

- Fewer defects: defects caught in review and tests, compared with defects found after merge, per epic.
- Better docs: no stale planning artifacts at the end of a phase.
- Predictability: time per phase and per story, and how often a human had to step in.

There are no adoption or usage targets, because the app is a demo vehicle [ASSUMPTION].

## Scope

**In the first release:** FR-1 to FR-8 from the spec. That covers EAN entry and validation, the parallel lookup, the comparison table with the lowest price highlighted, per-retailer failure handling, the short cache, kill switches and the adapter health check. The release also includes a pre-recorded fallback video for the stage.

**Out:**

- A mobile app or camera barcode scanning
- Accounts, watchlists, alerts, push notifications and price history
- Payments and monetization
- Text search by product name
- Retailers outside Romania
- AI extraction while the app runs

## Decisions already made

These come from spec §10 and are not reopened here:

- **Frontend:** a React single-page app.
- **Backend:** Java 21 with Spring Boot, behind a contract-first REST API (`api/openapi.yaml`).
- **Hosting:** one Azure Linux VM in an EU region, running Docker Compose with the frontend, the backend and a TLS reverse proxy.
- **Data:** no database. Results are cached in memory, and kill switches live in a config file the app reloads.
- **Infrastructure:** Terraform, with a plan on every PR and an apply only after a human approves it.
- **CI/CD:** GitHub Actions deploys to the VM on every merge to `main`.
- **Repository:** one monorepo.

## Risks and open questions

The biggest risk is whether the demo has enough retailers. The demo needs at least two Approved retailers. Today none are Approved. evoMAG and cel.ro are Proposed, and Altex and PC Garage are Not checked.

- **How to resolve an EAN at each retailer (spec §11, open).** The options are the retailer's own search where robots.txt allows it, an external EAN catalog, or a curated EAN-to-URL list for the demo. Two known facts constrain the choice:
  - evoMAG disallows search in its robots.txt and does not show the EAN on its pages, so it needs a curated mapping or a catalog.
  - cel.ro shows the EAN on its pages, but its search route has not been checked against robots.txt.

  For architecture to decide: the simplest option that keeps the demo working is probably a curated EAN-to-URL list for the demo EANs, possibly combined with search where it is allowed [ASSUMPTION]. That option weakens the "any EAN" story for the shopper, so the trade-off needs an ADR (D-2).
- **Which retailers pass the compliance gate (spec §11, open).** Only the owner can approve a retailer. Planning can proceed with evoMAG and cel.ro as the expected pair [ASSUMPTION]. Altex stays Not checked on purpose, so the compliance-gate demo has a retailer to move live (source register note).
- **Stage risk.** Retailer layouts, blocks or network trouble on stage. The cache, the health check and the fallback video are the mitigations.

Supporting detail (landscape notes, retailer terms and robots.txt findings) is in `addendum.md`.
