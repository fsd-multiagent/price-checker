# 0016. Run the Health check as the backend in `health` mode on a GitHub Actions runner, gated by production's `/api/status`

- **Status:** Proposed
- **Date:** 2026-11-17
- **Deciders:** Loredan (Architect); Pacala (DevOps) implements
- **Stage / story:** stage 4, run 20261117-174705-full (PRD §9 question 4)
- **Rules touched:** I-10, T-3, T-6, S-1 to S-6; FR-8, FR-11, AC-8.1 to 8.7, AC-11.2; spine AD-16

## Context

The Health check must run daily and by hand, fail visibly without a VM login, obey the same rules as a Lookup (AC-8.5), fail for blocked retailers (AC-11.2) and for robots.txt changes (AC-8.7). I-10 puts it in a GitHub Actions cron job that opens an `adapter-broken` issue with the failing fixture attached, which starts the self-healing story (SM-3).

## Options considered

| Option | Pros | Cons |
|---|---|---|
| A. Runner builds the backend and runs it with the `health` profile; reads production `GET /api/status` first | Same code path and fetch client; fixture saved on the runner; matches I-10; no new endpoint that triggers fetches | Runner's rate limiter is separate from production's |
| B. Scheduled job inside the backend; runner reads the result | Shares production's limiter and Blocks | Fixture capture and manual start need an authenticated trigger endpoint |
| C. Runner calls production `/api/lookup` for each Known EAN | Tests the real deploy | Cannot capture fixtures; fills the production cache |

## Decision

Option A. `health-check.yml` (daily cron at 04:00 Europe/Bucharest, and workflow_dispatch) reads production `/api/status` with `curl` (fails if unreachable). It fails, without fetching, every retailer that is `blocked` or has an open `adapter-broken` issue labelled `blocked`. It writes a runner `runtime.yaml` that allows only the other `enabled` ones and copies each retailer's `notBefore`, so the runner also waits out a production `Retry-After` (reported as skipped, not failed). It builds the backend from `main` and runs it with the `health` profile: no web server, a no-op cache, a fresh `blocks.json` (production Blocks have already come through `/api/status`), and each retailer's rate interval doubled with concurrency 1. Java does no HTTP except through the fetch client; the workflow does the status call, the Block hand-off and issue handling with `curl`, the `kill-switch` workflow and `gh`. For every Known EAN it runs the `lookup` core through the same fetch client, checks `FOUND` with a valid price, availability and URL, and checks robots.txt for each adapter path. On failure it writes a report (retailer, EAN, missing fact, `blocked` flag) and uploads the page as an artifact after `fetch.PageStripper` (removes scripts except JSON-LD product data, styles, images, iframes, comments, and description text beyond 200 characters; unit tested).

**A Block seen by the runner persists until an operator clears it.** The hand-off is a separate job (`needs` the check job, `if: always()`) and the only one holding the deploy secret, so the job that fetches and parses pages never sees it (S-10). It reads the runner's `blocks.json` artifact, which `BlockStore` writes at the moment of each Block or 429, so it works even if the backend crashed later. For every retailer the run Blocked (403, challenge page, robots.txt disallow), the workflow calls the `kill-switch` workflow (`workflow_call`, action `runner-block`, with the reason). For every retailer with a future `notBefore` it calls `runner-retry-after` with that time, so production also waits (AC-11.4). That writes `runnerBlock: { blockedAt, reason }` for the retailer into production `runtime.yaml` (ADR 0009). The backend picks it up within one 15 s poll: `BlockStore.isBlocked` is true, the fetch client refuses, rows show `UNAVAILABLE`, and `/api/status` reports `blocked: true`, so every later Health check run fails that retailer without fetching it. Only the operator's `clear-block` removes it. Then, with `if: always()`, the workflow opens or updates one `adapter-broken` issue per failing retailer, labelled `blocked` for a Block, including when the hand-off failed; the next run fails and skips any retailer with such an open issue. A missing or unparseable runner `blocks.json` or report fails the job and opens an issue for the operator. Any failed step fails the job.

## Consequences

- Production stops sending to a retailer that Blocked the runner within 15 s of the hand-off, and later runs do not fetch it again until an operator runs `clear-block` (S-6, AC-11.1, AC-11.2).
- `runtime.yaml` keeps one writer: the `kill-switch` workflow, now with `workflow_call`-only actions, serialized on the VM with `flock` (ADR 0015) so concurrent operator and health-check runs cannot lose a write. Only the hand-off job holds the deploy secret.
- Runner and production limiters are independent, so a run can overlap with production Lookups. Mitigation: a quiet hour, the doubled interval and concurrency 1 on the runner. Residual risk: during a run, a retailer can briefly get one runner request and one production request at the same time, more than a register `concurrency 1`. At a few requests a day the architect proposes accepting it; Veronel (Security & Compliance) flags it for the owner.
- A `Retry-After` is shared both ways: production's `notBefore` reaches the runner through `/api/status`, and the runner's reaches production through `runner-retry-after` (ADR 0008).
- The residual concurrency overlap needs the owner's sign-off; until then it is an open question, not an accepted risk.
- The failing fixture becomes the first test of the fix (T-6).

## Compliance

Same S-1 to S-6 enforcement as a Lookup, because it is the same fetch client and bundled register. T-3: CI tests still never call a retailer; only this scheduled job does.
