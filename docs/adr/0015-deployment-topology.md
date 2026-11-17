# 0015. Run one `prod` environment: Caddy, web and backend in Docker Compose on one VM, deployed and operated only by GitHub Actions

- **Status:** Proposed
- **Date:** 2026-11-17
- **Deciders:** Loredan (Architect); Pacala (DevOps) implements
- **Stage / story:** stage 4, run 20261117-174705-full
- **Rules touched:** I-1 to I-9, S-8, S-10, S-13; FR-7, NFR-7; spine AD-15

## Context

Spec §10 and I-1 to I-9 fix one Azure Linux VM in the EU, Docker Compose with web, backend and a TLS reverse proxy, Terraform, OIDC, and deploys only from GitHub Actions. The Kill switch must change without a deploy (S-8), and agents never SSH to the VM (I-7).

## Options considered

| Option | Pros | Cons |
|---|---|---|
| A. Caddy as reverse proxy with automatic HTTPS on the VM's Azure DNS label; images in GHCR tagged by git SHA; `deploy` and `kill-switch` workflows over the deploy key | No certificate handling; one origin; operator actions are audited workflow runs | Depends on Let's Encrypt reachability on ports 80 and 443 |
| B. nginx + certbot | Familiar | Certificate renewal to run and monitor |
| C. Operator edits `runtime.yaml` over SSH | No workflow | Breaks I-7 (SSH only from deploy source) and leaves no audit trail |

## Decision

Option A. Services: `proxy` (Caddy 2, `/api/*` → `backend:8080`, else → `web:8080`, port 80 redirects, `/data` on a volume so certificates persist), `web` (static build), `backend` (one instance, `runtime.yaml` and `blocks.json` mounted). The VM pulls images using the workflow's short-lived `GITHUB_TOKEN`, logs out afterwards and stores no token. Workflows: `ci`, `terraform-plan` (I-2), `deploy` (on merge to `main`), `kill-switch` (workflow_dispatch: retailer, `allow` / `stop` / `clear-block`; rewrites `runtime.yaml` only, through a temp file and `mv`), `health-check` (ADR 0016).

## Consequences

- No staging environment; CI uses stubs (T-3, T-4).
- Region, VM size and DNS label are Pacala's (DevOps) choice within I-3 and I-8.
- The NSG keeps 80, 443 public and SSH from the deploy source only (I-3).

## Compliance

S-13: TLS only, 80 redirects. S-10: no long-lived secrets on the VM or in images; OIDC for Azure. S-8: the Kill switch changes without a deploy.
