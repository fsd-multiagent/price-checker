# Epic 3 Context: Live on the VM and operable without SSH

<!-- Generated from planning artifacts. Regenerate with compile-epic-context if planning docs change. -->

## Goal

Run the site over HTTPS on a single Azure VM, deploy it automatically on merge to `main` through GitHub Actions only, and let an operator stop a retailer, clear a Block, and see a daily Health check fail visibly, all without logging in to the VM. Infra stories are owned by Pacala (DevOps); `terraform apply` is an owner action, never run by an agent.

## Stories

- Story 3.1: Terraform for the production VM
- Story 3.2: Container images, Compose and Caddy
- Story 3.3: Image build and deploy workflow
- Story 3.4: Kill-switch workflow
- Story 3.5: Backend health profile
- Story 3.6: Health-check workflow and hand-off

## Requirements & Constraints

- Production is reproducible: VM, network and DNS defined in Terraform, with a plan in CI; changed only by reviewed code.
- HTTPS only; port 80 redirects; no committed secrets (CI secret scan); pinned base images and dependencies.
- Privacy: no client IPs, user agents or request URIs in any log. Tomcat access log off, Caddy without access log and its own logs dropping remote IP, headers and URI, no NSG flow logs, no network diagnostics on the VM that could hold client IPs.
- Operator controls a retailer (allow / stop / clear-block) without SSH; changes take effect within 60 s.
- A Block stops all traffic to that retailer until an operator clears it; a 429 is not a Block (honor Retry-After).
- Daily Health check (and manual run) fails visibly outside the VM, naming retailer, EAN and missing fact; blocked retailers fail without fetching.
- Exactly one backend instance in production; one `prod` environment only.

## Technical Decisions

- One Azure Linux VM in an EU region. Docker Compose services: `proxy` (Caddy 2, automatic HTTPS, `/api/*` to backend:8080, else web:8080), `web` (static SPA), one `backend`. Same origin, no CORS.
- Images built by GitHub Actions, pushed to GHCR tagged with the git SHA, pulled on the VM with the short-lived `GITHUB_TOKEN`, then logged out.
- Only GitHub Actions reaches the VM by SSH: `deploy` (on merge to `main`) and `kill-switch` (workflow_dispatch: retailer + allow/stop/clear-block; workflow_call from health-check only: runner-block with reason, runner-retry-after with time).
- SSH restriction (key-only sshd, deploy user limited to deploy/kill-switch commands, NSG rule for GitHub-hosted runners via just-in-time OIDC or a recorded accepted risk) is Pacala's decision, recorded in a new ADR from `docs/adr/0000-template.md` and indexed in `docs/adr/README.md` (D-2).
- Config files with one writer each: VM `runtime.yaml` (kill-switch workflow only; backend polls every 15 s; per-retailer `fetching`, `notBefore`, `clearGeneration`, `runnerBlock`) and VM `blocks.json` (BlockStore only), both on VM volumes. The deploy script never writes them. The kill-switch script holds `flock`, edits only that retailer's field via temp file and `mv`, keeps other fields, re-reads to verify; `clear-block` increments `clearGeneration` and removes `runnerBlock`.
- Health check: `health-check.yml` on a GitHub runner (cron 04:00 Europe/Bucharest + manual) reads production `/api/status` with `curl`, writes a runner `runtime.yaml`, runs the backend built from `main` in `health` profile (no web server, no-op cache, fresh `blocks.json`, rate intervals doubled, concurrency 1), writes a report plus `PageStripper`-stripped pages. A separate hand-off job (`needs`, `if: always()`, the only one with the deploy secret) reads the runner `blocks.json` and calls `kill-switch`; `adapter-broken` issues are opened per failing retailer (label `blocked` for a Block).
- Gate commands for backend and web enter AGENTS.md `sdlc-commands` only via a stage 9 PR; no story here edits that block.
- Standards to cite: I-1 to I-9, S-8, S-10, S-12, T-2, T-3, D-2.

## Cross-Story Dependencies

- 3.1 has no dependencies; 3.3 needs 3.1, 3.2 and Epic 1 story 1.9 (CI); 3.2 needs 1.8 and 2.8.
- 3.4 needs 2.7 (Retry-After/BlockStore) and 3.3.
- 3.5 needs 2.2 (PageStripper) and 2.8; 3.6 needs 2.9 (status endpoint), 3.4 and 3.5.
- Epic 4 story 4.2 depends on 3.5.
