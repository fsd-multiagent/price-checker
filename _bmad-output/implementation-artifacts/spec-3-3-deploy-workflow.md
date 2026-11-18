---
title: 'Image build and deploy workflow'
type: 'feature'
created: '2026-11-18'
status: 'done'
baseline_revision: 'faf2e6723308bad3376521c36800c65996821f5a'
review_loop_iteration: 0
followup_review_recommended: false
context:
  - '{project-root}/docs/adr/0018-ssh-restriction-jit-nsg.md'
  - '{project-root}/docs/adr/0015-deployment-topology.md'
warnings: []
deferred:
  - summary: >-
      Caddy keeps the old Caddyfile after a deploy: mv swaps the inode under the file bind mount and Caddy does not reload.
    evidence: |-
      Review pass 2026-11-18; verified by reading the diff; deferred because the run budget ran out.
    location: >-
      deploy/scripts/deploy bundle loop
    severity: medium
  - summary: >-
      App dir gets the new compose.yaml/Caddyfile before the pull; a failed pull leaves it out of step with the running containers.
    evidence: |-
      Review pass 2026-11-18; verified by reading the diff; deferred because the run budget ran out.
    location: >-
      deploy/scripts/deploy bundle loop
    severity: medium
  - summary: >-
      Deploy reports success without checking the app is up (no up --wait or /api/status probe); no rollback.
    evidence: |-
      Review pass 2026-11-18; verified by reading the diff; deferred because the run budget ran out.
    location: >-
      deploy/scripts/deploy up -d
    severity: medium
  - summary: >-
      The SSH probe hides host-key or auth failures (stderr dropped, exit 255 retried as 'did not connect').
    evidence: |-
      Review pass 2026-11-18; verified by reading the diff; deferred because the run budget ran out.
    location: >-
      deploy.yml deploy step probe
    severity: low
  - summary: >-
      Workflow shell lacks pipefail and checks: a failed OIDC curl or nsg rule list is not caught clearly.
    evidence: |-
      Review pass 2026-11-18; verified by reading the diff; deferred because the run budget ran out.
    location: >-
      deploy.yml Azure login and open steps
    severity: low
  - summary: >-
      Concurrent pushes: an older SHA could deploy after a newer one; the shared prod-ssh group can replace a pending kill-switch run (story 3.4 must decide).
    evidence: |-
      Review pass 2026-11-18; verified by reading the diff; deferred because the run budget ran out.
    location: >-
      deploy.yml concurrency
    severity: medium
  - summary: >-
      Untested script branches: empty token line (65), symlink or missing bundle member, missing app dir, member-only extraction, install script.
    evidence: |-
      Review pass 2026-11-18; verified by reading the diff; deferred because the run budget ran out.
    location: >-
      DeployWorkflowTest.java
    severity: medium
  - summary: >-
      Runner IP comes from api.ipify.org (unpinned third party); not recorded in an ADR; no ADR for stdin token or bundle transport or owner install bootstrap (D-2).
    evidence: |-
      Review pass 2026-11-18; verified by reading the diff; deferred because the run budget ran out.
    location: >-
      deploy.yml open step
    severity: low
---

<intent-contract>

## Intent

**Problem:** Nothing builds the images from story 3.2 or puts them on the VM, so production cannot run reviewed code (I-1, I-7, S-10). The forced-command dispatcher `pc-dispatch` from story 3.1 expects `/opt/price-checker/bin/deploy`, which does not exist yet.

**Approach:** Add `.github/workflows/deploy.yml` (push to `main`): one job builds `backend` and `web` images from the repo root, tags them with the full git SHA and pushes them to GHCR with `GITHUB_TOKEN`; a second job logs in to Azure over OIDC, opens the `jit-ssh-*` NSG rule for the runner's `/32` (ADR 0018), and runs `ssh deploy@<fqdn> deploy <sha> <fqdn>`, sending the registry user, the `GITHUB_TOKEN` and a tar of `deploy/compose.yaml` + `deploy/Caddyfile` on stdin; it always removes the rule and the key. Add `deploy/scripts/deploy` (the VM script) and `deploy/scripts/install` (owner one-time bootstrap).

## Boundaries & Constraints

**Always:** the registry token travels only on stdin (`docker login --password-stdin`, never argv, never a file outside a temp `DOCKER_CONFIG` that is removed on exit); `docker logout ghcr.io` runs right after `compose pull` and again on any exit; secrets reach `run:` steps only through `env:`; actions pinned to commit SHAs (S-12) — only `actions/checkout` is used, Azure login is `az login --federated-token` with the runner's OIDC token; the deploy script validates its two arguments (40-hex SHA, DNS name) before reading stdin, extracts only `compose.yaml` and `Caddyfile` from the tar, sets `IMAGE_TAG`, `SITE_ADDRESS`, `BACKEND_UID`/`BACKEND_GID` (the deploy user's ids) and fails when `/opt/price-checker/state` is missing or not writable; deploy and the future kill-switch share the concurrency group `prod-ssh` because both use NSG priority 100.

**Never:** the deploy script writes, creates or deletes `runtime.yaml` or `blocks.json`, or anything under `/opt/price-checker/config` or `/opt/price-checker/state` (AD-9); no `set -x`; no `latest` tag; no `terraform apply`; no change to `infra/` (bootstrap is the owner's `install` script); no smoke test, image pruning or rollback (not in the ACs).

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Happy deploy | valid SHA + FQDN, stdin user/token/tar, writable state dir | login (token on stdin) → compose pull → logout → compose up -d; exit 0; temp docker config removed | — |
| Pull fails | fake `docker compose pull` exits 1 | non-zero exit, `docker logout ghcr.io` still runs, no `up` | trap on EXIT |
| Bad SHA | `deploy latest x.example` | exit 64 before reading stdin, no docker call | usage message |
| State dir missing | no `$PC_ROOT/state` | non-zero exit, no docker call, message names `install` | — |
| Existing operator files | `config/runtime.yaml`, `state/blocks.json` present | byte-identical after run; no new files in either dir | — |

</intent-contract>

## Code Map

- `infra/cloud-init.yaml.tftpl:58-88` -- `pc-dispatch`: runs `/opt/price-checker/bin/<deploy|kill-switch>` with args matching `^[A-Za-z0-9._:/@=+-]+$`; stdin passes through. `/opt/price-checker/bin` is root 0755, so the deploy user cannot install its own script -> owner bootstrap.
- `infra/cloud-init.yaml.tftpl:97-98` -- creates `bin` and `config` (deploy:deploy 0750); no `state` dir -> `install` creates it.
- `infra/outputs.tf` -- `fqdn`, `resource_group_name`, `nsg_name` -> repo variables `PROD_FQDN`, `PROD_RESOURCE_GROUP`, `PROD_NSG_NAME`.
- `infra/README.md:41-45` -- names secret `DEPLOY_SSH_KEY` for story 3.3.
- `deploy/compose.yaml` -- images `ghcr.io/fsd-multiagent/price-checker-{backend,web}:${IMAGE_TAG}`, needs `SITE_ADDRESS`, `BACKEND_UID/GID`; `./Caddyfile` relative bind.
- `backend/Dockerfile`, `web/Dockerfile` -- build context is the repo root (story 3.2).
- `backend/src/test/java/ro/pricechecker/DeployConfigTest.java` -- style to follow (SnakeYAML, paths relative to `backend/`, `@Tag` per AC).
- `backend/build.gradle.kts:99-104` -- test `inputs`; add `../.github/workflows/deploy.yml`.
- `.github/workflows/ci.yml:36` -- pinned `actions/checkout` SHA to reuse.

## Tasks & Acceptance

**Execution:**
- `backend/src/test/java/ro/pricechecker/DeployWorkflowTest.java` -- write first: YAML checks of the workflow and runs of `deploy/scripts/deploy` under bash with a fake `docker` on `PATH`, `PC_ROOT`/`PC_APP_DIR` under `@TempDir` -- AC 3.3.1-3.3.3, T-1.
- `deploy/scripts/deploy` -- VM deploy script as described -- AC 3.3.1, 3.3.2.
- `deploy/scripts/install` -- owner one-time root bootstrap: install `deploy` to `/opt/price-checker/bin/deploy` (root 0755), create `/opt/price-checker/state` deploy:deploy 0750 -- makes the script reachable by `pc-dispatch`.
- `deploy/scripts/README.md` -- owner setup: bootstrap, repo variables, `DEPLOY_SSH_KEY`, deploy OIDC identity with NSG rule write/delete.
- `.github/workflows/deploy.yml` -- workflow as described -- AC 3.3.1, 3.3.3.
- `backend/build.gradle.kts` -- add the workflow to test inputs.

**Acceptance Criteria:**
- Given a push to `main`, when `deploy.yml` runs, then images `ghcr.io/fsd-multiagent/price-checker-{backend,web}:<github.sha>` are pushed (job permission `packages: write`), and the deploy job (`packages: read`, `id-token: write`) passes `GITHUB_TOKEN` on ssh stdin; the script logs in with `--password-stdin`, pulls, logs out, then starts (3.3.1).
- Given the deploy script, when it runs or fails, then `runtime.yaml` and `blocks.json` are untouched and the script text never names them (3.3.2).
- Given the workflow, when inspected, then no `run:` interpolates `${{ secrets.* }}` or `${{ github.token }}`, the only secrets are `DEPLOY_SSH_KEY` and `GITHUB_TOKEN`, no `set -x`, and the token never appears in a docker argv (3.3.3).

## Spec Change Log

## Review Triage Log

### 2026-11-18 — Review pass
- verdicts: 41 findings from 4 layers (blind 11, edge 12, gap 4+1, intent 13 table rows treated as 1 descriptive note) — triaged as grouped entries below; high 0, medium 5, low 3, false 0, maybe-false 0, rest rejected
- findings:
  - `[medium]` `[defer]` Caddyfile inode swap / no reload (edge) — deferred, see frontmatter.
  - `[medium]` `[defer]` bundle installed before pull succeeds (blind, edge) — deferred.
  - `[medium]` `[defer]` no health wait or rollback (blind, edge) — deferred; not in ACs.
  - `[medium]` `[defer]` concurrency ordering and shared prod-ssh group (blind, edge) — deferred to story 3.4.
  - `[medium]` `[defer]` untested script branches (gap x3, blind, edge tar exit code) — deferred.
  - `[low]` `[defer]` probe hides host-key errors; pipefail and OIDC or rule-list checks; octet and host-key format validation; trap before mkdir (edge, blind) — deferred.
  - `[low]` `[defer]` ipify dependency and missing ADR for the deploy choices (blind) — deferred to Aglaia (Knowledge Keeper) or Pacala (DevOps).
  - `[low]` `[reject]` no workflow_dispatch redeploy, no CI gating via workflow_run, no GitHub environment, no image scan (blind) — beyond ACs (C-1); main is protected by the required `ci` check.
  - `[low]` `[reject]` AGENTS.md not updated (blind) — agent-context file; stage 9 Knowledge Keeper.
  - `[low]` `[reject]` local-action pin parse in test (edge) — no local actions used.
  - `[low]` `[reject]` stale-rule sweep vs owner apply mid-deploy (blind) — owner action; a leaked rule shows as plan drift (ADR 0018).
  - `[low]` `[reject]` intent audit: proof at stand-in surfaces and tests outside touches — the only way the tests gate can run them (T-8); live path needs owner setup.

## Design Notes

Stdin carries `user\ntoken\n` then the tar; bash `read` on a pipe consumes byte by byte, so `tar -x -f -` gets the rest intact. The actor goes on stdin because `github-actions[bot]` fails `pc-dispatch`'s charset. The bundle travels with each deploy so compose/Caddyfile changes need no owner step; only a change to the `deploy` script itself needs the owner to rerun `install`.

## Verification

**Commands:**
- `backend/gradlew -p backend check` -- expected: passes, including `DeployWorkflowTest`.
- `bash -n deploy/scripts/deploy deploy/scripts/install` -- expected: no syntax errors.

## Auto Run Result

Status: done

- Summary: `.github/workflows/deploy.yml` builds `price-checker-{backend,web}` tagged with the full SHA, pushes them to GHCR, opens the ADR 0018 JIT NSG rule over OIDC, pins the VM host key (required `PROD_SSH_HOST_KEY`), and runs the forced command `deploy <sha> <fqdn>`, sending the user, the `GITHUB_TOKEN` and the compose bundle on stdin. The VM script logs in with `--password-stdin` in a temporary `DOCKER_CONFIG`, pulls, logs out, then runs `up -d`, and never touches the operator files.
- Files: `.github/workflows/deploy.yml` (workflow); `deploy/scripts/deploy` (VM script); `deploy/scripts/install` (owner one-time root bootstrap: bin, state, app dirs); `deploy/scripts/README.md` (owner setup); `backend/src/test/java/ro/pricechecker/DeployWorkflowTest.java` (9 AC tests); `backend/build.gradle.kts` (test inputs).
- Review: 0 patches; 8 deferred (frontmatter `deferred`); rejections with reasons in the triage log. The orchestrator change after implementation made the host-key pin required instead of trust-on-first-use.
- Follow-up review recommended: false (no patches this pass).
- Verification: `backend/gradlew -p backend check` passed (DeployWorkflowTest 9/9, 0 skipped); `bash -n` on both scripts passed. No real Actions or VM run.
- Residual risks: the deferred items, mainly that the Caddyfile is not reloaded on deploy and the deploy is reported green without a health check. Owner setup is required (install script, variables, secret, NSG-scoped OIDC role).
