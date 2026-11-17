# 0018. Restrict SSH to a just-in-time NSG rule opened by the workflow over OIDC, a key-only sshd and a forced-command deploy user

- **Status:** Proposed
- **Date:** 2026-11-17
- **Deciders:** Pacala (DevOps); the owner accepts
- **Stage / story:** story 3.1 (Terraform for the production VM)
- **Rules touched:** I-3, I-5, I-7, S-10, S-13; spine AD-14, AD-15; ADR 0015

## Context

I-3 allows SSH only from the deploy source, and I-7 deploys only from GitHub Actions over the deploy key. GitHub-hosted runners use large, changing IP ranges, so a fixed source prefix is either too wide or wrong. ADR 0015 left the mechanism to Pacala (DevOps): key-only sshd, a deploy user limited by forced command, and either a just-in-time NSG rule or an owner-accepted risk.

## Options considered

| Option | Pros | Cons |
|---|---|---|
| A. No standing SSH rule; the `deploy` and `kill-switch` workflows add a priority-100 rule for the runner's `/32` through OIDC and delete it in an `always()` step | Port 22 closed except during a workflow run; uses the OIDC identity already needed (I-5) | Out-of-band NSG change; a failed cleanup leaves a rule until the next run or apply; the workflow identity needs write access to the NSG |
| B. Allow 22 from GitHub's published Actions ranges | No runtime NSG changes | Thousands of prefixes, change often; effectively open to any Actions user |
| C. Allow 22 from the Internet and rely on key-only sshd (accepted risk) | Simplest | Exposed to scanning and any future sshd flaw; needs owner sign-off |
| D. Self-hosted runner or Azure Bastion | Stable source / no public 22 | Extra VM or Bastion cost; more to run |

## Decision

Option A. Terraform defines no SSH allow rule and an explicit `deny-ssh-internet` rule at priority 4000. The workflows add `jit-ssh-<run_id>` (priority 100, TCP 22, source the runner's `/32`) and remove it in an `always()` step. The NSG rules are inline in Terraform, so a leaked `jit-ssh-*` rule shows in the next plan and an apply removes it. On the VM, cloud-init sets sshd to key-only, no root login, `AllowUsers` the admin and `deploy` users, no forwarding; the `deploy` key is `restrict,command="/usr/local/bin/pc-dispatch"`, and the dispatcher accepts only `deploy` and `kill-switch` with arguments from a safe character set, running `/opt/price-checker/bin/<name>`.

## Consequences

- Story 3.3 delivers `/opt/price-checker/bin/deploy` and `kill-switch`, and the workflow steps that open and close the rule. The workflow's OIDC identity needs `Microsoft.Network/networkSecurityGroups/securityRules/write` and `/delete` on this NSG only (a custom role scoped to the NSG); the PR plan identity stays read-only.
- The `deploy` user is in the `docker` group, which is root-equivalent; the forced command is what limits it, so the dispatcher must stay strict.
- Owner break-glass access: temporarily add a rule for their own IP via the portal or CLI, which the next plan shows as drift. This is the one accepted exception to I-1.
- `custom_data` is ignored after creation, so sshd hardening changes need a deliberate VM replacement.

## Compliance

S-10: no long-lived cloud credentials; the NSG change uses OIDC, and only the deploy private key lives as a GitHub secret. S-13: only 80 (redirect) and 443 are permanently open. AD-14/S-9: no flow logs or diagnostics are added to observe SSH traffic.
