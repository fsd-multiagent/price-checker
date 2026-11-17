---
title: "Product Brief Addendum: Price Checker"
created: 2026-11-17
updated: 2026-11-17
---

# Addendum: Price Checker

This file holds depth that supports the brief but belongs in downstream documents: the PRD, the architecture and the source register.

## Prior research, carried forward

The full landscape scan and the retailer terms and robots.txt check from 2026-11-12 are in `../brief-price-checker-2026-11-12/addendum.md`. That brief was written for the v1 mobile app and is superseded, but its research still applies. The points that matter for v2 are below.

- **Retailer facts.** The live status of each retailer is in `docs/source-register.md`, which is the source of truth.

  | Retailer | Status on 2026-11-17 | Facts that shape EAN lookup |
  |---|---|---|
  | evoMAG | Proposed | Its robots.txt disallows search (`/*?q=`, `/*?sn.q=`). Its pages carry schema.org microdata but no EAN. |
  | cel.ro | Proposed | Its pages carry JSON-LD Product and Offer data and show the EAN. Its robots.txt blocks user agents containing `robot`, `spider`, `crawl`, `bot-` and `bot/`. Its search route has not been checked. |
  | Altex | Not checked | Its affiliate terms ban automated taking of information [S]. |
  | PC Garage | Not checked | Its terms page returned 403. |

- **eMAG** was dropped because its terms (4.2(j)) ban automated extraction.
- **Comparable products.** Romanian price tracking happens mostly in browser extensions (Pricy, Istoric Preturi, xPrice) and on Compari.ro. None of them is a reason to change v2 scope. The brief makes no claim to a market moat: the differentiator is the agent-built SDLC, not the product.

## Rejected alternative: v1 mobile app

The owner replaced the v1 scope on 2026-11-12 with the v2 POC. v1 was a Flutter app with barcode scanning, a watchlist, alerts, push notifications, price history, PostgreSQL and Azure Container Apps.

The reason: the app is a pretext for learning multi-agent orchestration, and a smaller product keeps the effort on orchestration. The v1 spec is archived in `docs/archive/initial-spec-v1-mobile.md`.

## Notes for the PRD and the architecture

- **EAN resolution.** For the PRD, the options in spec §11 map to different shopper promises:

  | Option | What the shopper gets | Constraints |
  |---|---|---|
  | Retailer search | Any EAN | Only where robots.txt allows search |
  | External EAN catalog | Any EAN | Adds a third-party source, which also needs a source-register review |
  | Curated EAN-to-URL list | Only the listed EANs | Simplest and most reliable on stage |

  The PRD should say which promise the first release makes.
- **Health check.** The health check (FR-8) needs a few known EANs per retailer. The demo EANs and the health-check EANs can be the same list.
- **Fallback video.** The pre-recorded video (spec §7) is a release deliverable. It should be recorded once the journey works against the approved retailers.
