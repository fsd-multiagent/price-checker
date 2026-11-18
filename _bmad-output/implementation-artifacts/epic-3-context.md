# Epic 3 Context: Live on the VM and operable without SSH

<!-- Generated from planning artifacts. Regenerate with compile-epic-context if planning docs change. -->

## Goal

Run the site over HTTPS on one Azure VM, deploy it from `main` through GitHub Actions only, and let an operator stop or allow a retailer, clear a Block, and see a daily Health check fail visibly, all without logging in to the VM. The Health check catches a broken Adapter (layout change) or a Block before the audience sees it. Infra stories are owned by Pacala (DevOps); `terraform apply` is an owner action, never run by an agent.

## Stories

- Story 3.1: Terraform for the production VM
- Story 3.2: Container images, Compose and Caddy
- Story 3.3: Image build and deploy workflow
- Story 3.4: Kill-switch workflow
- Story 3.5: Backend health profile
- Story 3.6: Health-check workflow and hand-off

## Requirements & Constraints

- Production is reproducible: VM, network and DNS in Terraform with a plan in CI; changed only by reviewed code. One `prod` environment, exactly one backend instance.
- HTTPS only (port 80 redirects); no secrets in the repo or logs; base images pinned by digest or exact tag.
- Privacy: no client IP, user agent, headers or request URI in any log (no Caddy access log, no NSG flow logs or network diagnostics).
- Operator controls each retailer (allow / stop / clear-block) without SSH; production picks the change up within one poll.
- A Block (403, challenge page, robots.txt disallowing an adapter path) stops all traffic to that retailer until an operator clears it, and makes the next Health check fail for it. A 429 is a pause (`notBefore`), never a Block.
- Health check runs daily and by hand; it looks up every Known EAN (at least two per Approved retailer) and requires each to be `FOUND` with a valid price, availability and product URL. Any failure ends the run non-zero and names retailer, EAN and the missing fact, visible without the VM.
- The Health check obeys the same rules as a Lookup: Approved and enabled retailers only, rate and concurrency limits, the honest user agent, robots.txt.

## Technical Decisions

- VM runs Docker Compose: `proxy` (Caddy 2, automatic HTTPS, `/api/*` to `backend:8080`, else `web:8080`), `web` (static SPA), one `backend`. Same origin, no CORS. `runtime.yaml` and `blocks.json` live on VM volumes.
- Images are built by GitHub Actions, pushed to GHCR tagged with the git SHA, pulled on the VM with the short-lived `GITHUB_TOKEN`, then logged out. Only GitHub Actions reaches the VM by SSH (`deploy`, `kill-switch`); the SSH restriction is recorded in an ADR (D-2).
- One writer per file: `runtime.yaml` only by the kill-switch script (holds `flock`, edits only its retailer's field via temp file and `mv`, keeps every other field, re-reads to verify); `blocks.json` only by `BlockStore`. Deploy never writes either. `clear-block` increments `clearGeneration` and removes `runnerBlock`; `runner-block` / `runner-retry-after` (called only by the health-check workflow) set `runnerBlock` / `notBefore`.
- Health profile (backend, package `health`): may depend only on `lookup`, `registry` and `fetch` (ArchUnit arrows). Under profile `health` it runs no web server, uses a no-op cache, a fresh `blocks.json`, the same lookup core, register and fetch client, each retailer's rate interval doubled and concurrency 1. It contacts only Approved retailers allowed by the runner's `runtime.yaml` (with copied `notBefore`, so a production Retry-After is waited out and reported as skipped, not failed). It checks robots.txt for every adapter path, writes a report (retailer, EAN, missing fact, `blocked` flag) plus each page passed through `fetch.PageStripper` (T-2), and exits non-zero on any failure. Known EANs come from `config/known-eans.yaml` through `registry`.
- Health-check workflow: cron 04:00 Europe/Bucharest plus manual; reads production `/api/status` with `curl` (fails if unreachable); fails without fetching any retailer that is `blocked` or has an open `adapter-broken` issue labelled `blocked`; writes the runner `runtime.yaml` for the others. A separate hand-off job (`needs`, `if: always()`, the only holder of the deploy secret) reads the runner's `blocks.json` (not the report) and calls `kill-switch`; then one `adapter-broken` issue per failing retailer is opened or updated (`blocked` label for a Block). A missing or unparseable report or `blocks.json` also fails and opens an issue.
- Tests never contact real retailers: stub servers and `*.example` domains (T-3); no retailer domain or selector literals in backend main code.
- Standards to cite: I-1 to I-9, S-8, S-10, S-12, T-2, T-3, D-2.

## Cross-Story Dependencies

- 3.1 has none; 3.2 needs 1.8 and 2.8; 3.3 needs 1.9, 3.1 and 3.2; 3.4 needs 2.7 and 3.3.
- 3.5 needs 2.2 (PageStripper) and 2.8 (lookup execution and cache); it builds on the existing `FetchClient`, `RobotsCache`, `BlockStore`, `RuntimeSwitch` and `Registry`.
- 3.6 needs 2.9 (`/api/status`), 3.4 and 3.5. Epic 4 story 4.2 (simulated layout change caught by the Health check) depends on 3.5.
