# 0020. Rotate container logs on the VM with Docker's `local` driver, capped at 5 × 10 MB per container

- **Status:** Proposed
- **Date:** 2026-11-17
- **Deciders:** Pacala (DevOps)
- **Stage / story:** stage 6 (`stage:infra`, run `20261117-174705-full`)
- **Rules touched:** I-1, I-6, S-9, NFR-5; AD-14, AD-15

## Context

AD-14 (ADR 0017) makes every service log JSON lines to stdout. On the VM, Docker stores stdout with its default `json-file` driver, which never rotates. The VM has a 30 GB OS disk (`infra/main.tf`), and a full disk would stop the backend from writing `blocks.json` and Caddy from renewing certificates. Logs also hold EANs and outcomes, so keeping them only for a short, bounded time suits S-9 and NFR-5. Nothing in the architecture sets a size or a retention period, so this small decision is recorded here.

## Options considered

| Option | Pros | Cons |
|---|---|---|
| A. Leave the default `json-file` driver | Nothing to configure | Grows without limit; the disk eventually fills |
| B. Set `logging:` per service in `deploy/compose.yaml` | Visible next to each service | A service that leaves it out is unbounded; needs every Compose change to repeat it |
| C. Set the `local` driver with `max-size`/`max-file` in `/etc/docker/daemon.json` through cloud-init | One host default that covers every container, including one-off `docker run`s; `local` is compressed and `docker logs` still works | Changes only reach a new VM, because `custom_data` is in `ignore_changes` |

## Decision

Option C. cloud-init writes `/etc/docker/daemon.json` with `"log-driver": "local"`, `max-size` `10m` and `max-file` `5`, so each container keeps at most about 50 MB of logs. This is defined in Terraform (I-1), and `infra/tests/plan.tftest.hcl` checks it.

## Consequences

- Disk use for logs is bounded: three services × 50 MB.
- Older log lines are dropped, so a long investigation needs `docker logs` soon after the event. This is enough for a demo.
- Compose files (story 3.2) can still override `logging:` for a single service, but should not need to.
- An already-provisioned VM gets this only when it is replaced (`-replace=azurerm_linux_virtual_machine.app`), as described in `infra/README.md`.

## Compliance

S-9 and NFR-5: logs stay on the VM and are now deleted after a bounded size, never shipped elsewhere. The AD-14 field allowlist is unchanged. No ports, secrets or retailer traffic are affected (S-10, S-13, S-1 to S-8 untouched).
