# Source register

- **Owner:** Adrian (the only person who approves a retailer)
- **Maintained by:** Aglaia (Knowledge Keeper), who changes facts only; rows drafted by Veronel (Security & Compliance)
- **Read by:** the guard hook (`.claude/hooks/guard.py`, rule G4), Veronel (Security & Compliance) at stages 3, 4 and 7, and the app's retailer config
- **Sources:** `docs/initial-spec.md` §4 and §8; research in `_bmad-output/planning-artifacts/briefs/brief-price-checker-2026-11-12/addendum.md`

Not legal advice. This register records what was checked, when, and who decided.

## How a retailer gets approved

1. Veronel (Security & Compliance) or Claude checks the retailer's terms, robots.txt, structured data, and whether an EAN can be looked up. Each fact is marked [V] (read at the source), [S] (search snippet) or [?] (not confirmed).
2. If every check passes, the row's Status becomes `Proposed`. Otherwise it stays `Not checked`, or becomes `Rejected` with a reason.
3. **Only the owner** changes Status to `Approved YYYY-MM-DD`, by editing this file by hand. Agents are blocked from doing it (guard rule G5, `security-compliance.md` S-1).
4. Until a row says `Approved`, every network call to that domain from an agent session is blocked (guard rule G4). The app must not fetch from it either (S-1).
5. To stop using a retailer, set Status to `Retired YYYY-MM-DD` and turn on its kill switch (S-8). Never delete a row.

| Status | Meaning |
|---|---|
| `Not checked` | No complete check yet. Blocked |
| `Proposed` | All checks passed; waiting for the owner. Blocked |
| `Approved YYYY-MM-DD` | The owner approved it. Adapters may run |
| `Rejected` | Fails a check; the reason is in Notes. Blocked |
| `Retired YYYY-MM-DD` | Was approved, no longer used. Blocked |

## Retailers

The guard reads the `Domain` and `Status` columns. Keep one domain per row, with no scheme and no `www.`.

| Retailer | Domain | Status | Terms | robots.txt | Structured data | EAN lookup | Limits | Checked | Notes |
|---|---|---|---|---|---|---|---|---|---|
| evoMAG | `evomag.ro` | Proposed | No anti-scraping clause; §6 is general copyright [V] | Product pages allowed; search (`/*?q=`, `/*?sn.q=`) disallowed [V] | schema.org microdata: price, RON, availability, sku, mpn [V] | **Not via search** (disallowed) and no EAN on page [V]. Only through a curated EAN-to-URL list or an external catalog (open question D5) | 1 request / 2 s, concurrency 1 | 2026-11-12 | Replaces eMAG. Approve only if the Architect picks an EAN route that never calls the disallowed search |
| cel.ro | `cel.ro` | Proposed | No anti-scraping clause [V] | Product pages allowed. User agents containing `robot`, `spider`, `crawl`, `bot-` or `bot/` get `Disallow: /` [V] | JSON-LD Product and Offer [V] | EAN shown on the product page [V]. Search route not yet checked against robots.txt [?] | 1 request / 2 s, concurrency 1 | 2026-11-12 | User agent must avoid the blocked substrings (S-6) |
| Altex | `altex.ro` | Not checked | IP clause only [S]; affiliate terms ban automated taking of information [S] | Fetch failed [?] | [?] | [?] | — | 2026-11-12 | Keep for the live demo of the compliance gate (spec §12, criterion 2) |
| PC Garage | `pcgarage.ro` | Not checked | Terms page returned 403 [?] | Product pages allowed; `/alerta-pret*` and the `ChangeDetection` user agent blocked [V] | [?] | [?] | — | 2026-11-12 | Listed on Profitshare as an affiliate advertiser [S] |
| Vexio | `vexio.ro` | Not checked | "Affecting server performance" counts as fraud [V] | Product pages allowed; `/search` disallowed; Crawl-delay 1 [V] | [?] Cloudflare challenge | [?] | — | 2026-11-12 | Unreliable to reach |
| Flanco | `flanco.ro` | Not checked | "Abusive use" clause [S] | Product pages allowed; `ai-train=no` [V] | [?] returned 403 | [?] | — | 2026-11-12 | Unclear |
| eMAG | `emag.ro` | Rejected | Clause 4.2(j) bans automated extraction; 6.4 limits prices to personal, non-commercial use [V] | Not needed | — | — | — | 2026-11-12 | Decision D2 in `docs/automation-plan.md`. Do not re-check |

## Still needed before any adapter runs

- A contact URL for the user agent (`docs/automation-plan.md` §6, S-6).
- At least two `Approved` rows (spec §4).
- The Architect's EAN lookup route (D5). It may change the evoMAG row.
