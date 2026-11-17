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

Option A. `health-check.yml` (daily cron and workflow_dispatch) reads production `/api/status` with `curl` (fails if unreachable), fails every `blocked` retailer, and writes a runner `runtime.yaml` that allows only `enabled` ones. It builds the backend from `main` and runs it with the `health` profile: no web server, a no-op cache, an empty `blocks.json`. Java does no HTTP except through the fetch client; the workflow does the status call and issue handling with `curl` and `gh`. For every Known EAN it runs the `lookup` core through the same fetch client, checks `FOUND` with a valid price, availability and URL, and checks robots.txt for each adapter path. On failure it writes a report (retailer, EAN, missing fact), uploads the stripped page as an artifact and opens or updates one `adapter-broken` issue per retailer.

## Consequences

- Runner and production limiters are independent; at one run a day and a few EANs, the combined rate stays far below the register's limits.
- A Block seen by the runner fails the check and opens an issue; production learns of it only when the operator runs `kill-switch off`.
- The failing fixture becomes the first test of the fix (T-6).

## Compliance

Same S-1 to S-6 enforcement as a Lookup, because it is the same fetch client and bundled register. T-3: CI tests still never call a retailer; only this scheduled job does.
