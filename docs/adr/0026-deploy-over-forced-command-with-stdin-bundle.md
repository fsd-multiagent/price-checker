# 0026. Deploy by sending the registry token and the compose bundle on ssh stdin to a forced command, and fail the deploy unless the containers stay up

- **Status:** Proposed
- **Date:** 2026-11-18
- **Deciders:** Marghioala (Developer), for Pacala (DevOps) and Loredan (Architect)
- **Stage / story:** 3-3-deploy-workflow
- **Rules touched:** I-1, I-5, I-6, I-7, S-10, S-12, AD-9; ADR 0015, ADR 0018

## Context

Story 3.3 needs a workflow that puts SHA-tagged images on the single VM (ADR 0015). SSH is open only through a just-in-time NSG rule for the runner's /32, and the `deploy` user runs only the forced command `pc-dispatch` (ADR 0018), which accepts a short argument charset. The VM must pull private GHCR images, but no long-lived registry credential may sit on the VM (S-10), and the operator files `runtime.yaml` and the Block file must never be written by a deploy (AD-9).

## Options considered

| Option | Pros | Cons |
|---|---|---|
| A. Token, user and a tar of `deploy/compose.yaml` + `deploy/Caddyfile` on ssh stdin; VM script logs in with `--password-stdin` in a temp `DOCKER_CONFIG` and logs out after the pull | Token never on argv or disk beyond the run; compose and Caddy changes ship with each deploy; fits the forced-command charset | Changes to the VM `deploy` script itself still need the owner to rerun `install` |
| B. Long-lived read-only GHCR PAT stored on the VM | Simple VM script | Standing secret on the VM (S-10); rotation burden |
| C. `scp` the files, then a separate ssh for the deploy | Familiar | Forced command blocks scp; two connections through the JIT rule |

## Decision

Option A. The VM script (`deploy/scripts/deploy`) is installed once by the owner (`deploy/scripts/install`, root-owned `/opt/price-checker/bin`). It keeps the new bundle in its temp dir until `compose pull` passes, then moves it into `/opt/price-checker/app`, runs `compose up -d --wait`, recreates the Caddy proxy only when the Caddyfile changed, and fails when a container restarts or stops within a settle time (default 20 s). The runner finds its public IPv4 from `api.ipify.org`, falling back to `checkip.amazonaws.com`. Every workflow that opens a `jit-ssh-*` rule uses the concurrency group `prod-ssh`, because the deploy job deletes stale `jit-ssh-*` rules; a test checks this.

## Consequences

- A failed pull leaves the app directory and the running containers in step; a backend that fails its start checks turns the deploy red.
- No rollback: a red deploy leaves the new containers in place (restarting) until the next push or an owner action.
- The deploy depends on one of two public IP echo services; the IP only scopes the NSG rule, the deploy key is still required.
- Story 3.4 (kill switch) must join the `prod-ssh` group.

## Compliance

S-10: the registry token reaches the VM only on stdin, lives in a temporary docker config removed on exit, and the session is logged out after the pull. S-12: the only action is `actions/checkout`, pinned to a SHA. AD-9: the script never names or writes the operator files and only checks that the state directory exists. No retailer is contacted.
