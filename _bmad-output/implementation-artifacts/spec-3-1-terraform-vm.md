---
title: 'Story 3.1: Terraform for the production VM'
type: 'feature'
created: '2026-11-17'
status: 'done'
baseline_revision: 'f7ec8cf8dad712430554513604a3b1d589d674f9'
review_loop_iteration: 0
followup_review_recommended: true
context:
  - '{project-root}/docs/standards/infrastructure.md'
  - '{project-root}/docs/adr/0015-deployment-topology.md'
warnings: []
deferred:
  - summary: >-
      One OIDC identity with Storage Blob Data Contributor serves both PR plans and main, so a PR that edits infra/ can read or write state.
    evidence: |-
      infra/README.md bootstrap step 2 grants one app both federated subjects; ADR 0018 text says the PR plan identity stays read-only. Fix needs the owner's Entra setup (split identities or plan only after review).
    location: >-
      infra/README.md
    severity: medium
  - summary: >-
      JIT SSH rule priority 100 collides when deploy and kill-switch overlap, and an owner apply deletes a live jit-ssh rule mid-deploy.
    evidence: |-
      ADR 0018 fixes one priority; NSG rules are inline in main.tf. Story 3.3 owns the workflows and must add a shared concurrency group or per-run priority and document no-apply-during-deploy.
    location: >-
      docs/adr/0018-ssh-restriction-jit-nsg.md
    severity: medium
  - summary: >-
      Deploy key rotation is invisible to plan (custom_data ignored) and admin key rotation replaces the VM.
    evidence: |-
      main.tf lifecycle ignore_changes = [custom_data]; admin_ssh_key forces replacement in azurerm 4.x. Needs a rotation procedure, likely with story 3.3 deploy scripts.
    location: >-
      infra/main.tf
    severity: medium
  - summary: >-
      The orchestrator tests gate does not run terraform fmt/validate/test; only the GitHub workflow does.
    evidence: |-
      AGENTS.md sdlc-commands lists only orchestrator pytest and is read from origin/main (T-8); adding `terraform -chdir=infra test` needs an owner change on main.
    location: >-
      AGENTS.md
    severity: low
  - summary: >-
      Real terraform plan has never run; CI skips it until the owner bootstraps state storage, OIDC and repository variables.
    evidence: |-
      terraform-plan.yml plans only when vars.AZURE_CLIENT_ID is set. AC 3.1.1 is met by mocked terraform test until then.
    location: >-
      .github/workflows/terraform-plan.yml
    severity: medium
---

<intent-contract>

## Intent

**Problem:** Production (one Azure Linux VM, its network and DNS name) is not defined anywhere, so it cannot be reproduced or reviewed, and no CI job plans it (I-1, I-2).

**Approach:** Add Terraform under `infra/` for one `prod` VM in an EU region with a public IP DNS label for Caddy, an NSG allowing 80 and 443 and no standing SSH rule, a cloud-init that hardens sshd and creates a forced-command deploy user, plus a `terraform-plan` workflow (fmt, validate, `terraform test` with a mocked provider, then plan over OIDC) and ADR 0018 recording the SSH restriction.

## Boundaries & Constraints

**Always:** azurerm 4.x and Terraform 1.x (spine); every resource tagged `project=price-checker`, `env=prod` (I-8); remote `azurerm` backend with partial config supplied from repository variables, OIDC only (I-4, I-5, S-10); actions pinned to commit SHAs (S-12); tests named with AC IDs (T-1).

**Never:** run `terraform apply`/`destroy` (I-2); commit state, tfvars or secrets; add NSG flow logs, `azurerm_network_watcher*`, `azurerm_monitor_diagnostic_setting` or VM boot diagnostics (AD-14); a standing NSG rule allowing SSH from the internet; Compose, Caddyfile or deploy scripts (stories 3.2, 3.3); edit `AGENTS.md` (its gate block is read from `origin/main`).

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Default plan | no var overrides | VM in `westeurope`, DNS label `price-checker-prod`, NSG allows TCP 80 and 443 from Internet, denies 22 from Internet | No error expected |
| Non-EU region | `location = "eastus"` | validation fails | variable validation error |
| PR without Azure vars | `vars.AZURE_CLIENT_ID` empty | fmt, validate, test run; plan step skipped with a summary notice | job stays green |
| Flow logs added | any `.tf` containing flow-log or diagnostic resources | CI step fails | grep check exits 1 |

</intent-contract>

## Code Map

- `docs/adr/0015-deployment-topology.md` -- leaves the SSH mechanism to Pacala (DevOps) at stage 6: key-only sshd, forced-command deploy user, JIT NSG rule via OIDC or accepted risk.
- `_bmad-output/planning-artifacts/architecture/architecture-price-checker-2026-11-17/ARCHITECTURE-SPINE.md` AD-14, AD-15, line 221 (Terraform 1.x, azurerm 4.x), line 305 (region/size/label are DevOps choices).
- `.github/workflows/ci.yml` -- reuse its pinned `actions/checkout` SHA and header-comment style.
- `docs/adr/README.md` -- index table to extend; `docs/adr/0000-template.md` -- ADR shape.

## Tasks & Acceptance

**Execution:**
- `infra/versions.tf`, `infra/providers.tf` -- pin Terraform/azurerm, partial `azurerm` backend with `use_oidc`, provider with `use_oidc` -- I-4, I-5.
- `infra/variables.tf` -- location (EU-validated), vm_size, dns_label, admin key, deploy key, budget amount/email -- I-3, I-8.
- `infra/main.tf` -- RG, VNet, subnet, NSG (80, 443 allow; 22 deny at low priority), NSG association, static Standard public IP with `domain_name_label`, NIC, Ubuntu 24.04 VM without boot diagnostics, RG budget alert -- AC 3.1.1, 3.1.2, I-8.
- `infra/cloud-init.yaml.tftpl` -- Docker + Compose, sshd key-only/no root/AllowUsers, deploy user with forced command dispatcher -- ADR 0018.
- `infra/outputs.tf`, `infra/README.md` -- FQDN/IP outputs; bootstrap and owner-only apply instructions.
- `infra/tests/plan.tftest.hcl` -- `terraform test` with `mock_provider` asserting AC 3.1.1 and region validation.
- `.github/workflows/terraform-plan.yml` -- fmt, validate, test, flow-log grep (AC 3.1.2), OIDC plan to job summary and artifact (I-2).
- `docs/adr/0018-ssh-restriction-jit-nsg.md`, `docs/adr/README.md` -- AC 3.1.3.

**Acceptance Criteria:**
- Given `infra/`, when `terraform test` runs, then the plan has one Linux VM in an EU region, a public IP with a DNS label, and an NSG allowing 80 and 443 with no rule allowing 22 from the internet (3.1.1).
- Given `infra/`, when CI greps it, then no flow-log, network-watcher or diagnostic-setting resource exists and the VM has no boot diagnostics (3.1.2).
- Given the SSH choice, when the ADR index is read, then ADR 0018 is listed (3.1.3).

## Spec Change Log

## Review Triage Log

### 2026-11-17 — Review pass
- verdicts: 44 findings — high 0, medium 11, low 22, false 3, maybe-false 0, plus 8 descriptive intent-alignment observations logged as reject
- findings:
  - Blind Hunter:
    - `[medium]` `[defer]` PR plan identity can write state — needs owner Entra setup; deferred.
    - `[medium]` `[defer]` JIT rule priority collision between overlapping deploy/kill-switch — workflows belong to story 3.3; deferred.
    - `[medium]` `[defer]` owner apply deletes live jit-ssh rule — grouped with the collision; deferred to 3.3.
    - `[medium]` `[defer]` deploy key rotation invisible to plan — deferred (rotation procedure).
    - `[medium]` `[patch]` budget default 20 below Standard_B2s monthly cost — default raised to 50.
    - `[low]` `[patch]` budget_start_date unvalidated — regex validation added and tested.
    - `[medium]` `[patch]` tests miss cloud-init and budget — runs adr_0018_cloud_init_... and i_8_budget_alert_notifies_owner added.
    - `[low]` `[reject]` NSG test ignores destination_port_ranges/wildcards — no such rule exists; guarding hypothetical future rules adds complexity.
    - `[medium]` `[patch]` pc-dispatch passes `-` and `..` arguments — rejected now.
    - `[low]` `[patch]` AD-14 grep too narrow — added log analytics, data collection rules, VM extensions.
    - `[false]` `[reject]` workflow runs for every PR — I-2 requires every PR; plan takes the lease only when vars are set and lock-timeout handles queueing.
    - `[low]` `[defer]` orchestrator gate lacks terraform test — AGENTS.md is agent-context, read from origin/main; deferred.
  - Edge Case Hunter:
    - `[medium]` `[patch]` dispatcher `-`/`..` args — same root cause as above; patched.
    - `[low]` `[reject]` multi-line SSH_ORIGINAL_COMMAND drops trailing lines — callers are our own workflows; extra guard not worth it.
    - `[low]` `[reject]` quote in deploy key comment breaks YAML — key is owner-supplied; rare.
    - `[medium]` `[patch]` admin key validation accepts ECDSA that Azure rejects — admin regex narrowed to ed25519/rsa.
    - `[medium]` `[defer]` admin key rotation replaces VM — grouped with key rotation; deferred.
    - `[medium]` `[defer]` deploy key rotation — grouped; deferred.
    - `[false]` `[reject]` dynamic "boot_diagnostics" bypasses grep — pattern matches `boot_diagnostics` followed by space, so `dynamic "boot_diagnostics"` does not match but `boot_diagnostics =` does; the test run asserts boot_diagnostics empty on the plan, which catches a dynamic block.
    - `[low]` `[reject]` partial repo vars cause a failing plan — owner bootstrap is one documented step; failure is loud.
    - `[low]` `[reject]` fork/Dependabot PRs fail OIDC — vars are unavailable to forks so the plan is skipped; Dependabot not configured.
    - `[low]` `[reject]` workflow_dispatch from non-main branch not federated — fails loudly; owner-only action.
    - `[low]` `[reject]` failed plan not written to summary — the log shows it; extra steps not worth it.
    - `[low]` `[reject]` summary fence/size — plan for 9 resources is small.
    - `[medium]` `[defer]` apply during deploy deletes JIT rule — grouped with 3.3 deferral.
    - `[low]` `[patch]` budget_start_date — same root cause as above; patched.
    - `[low]` `[reject]` README hostname hardcodes westeurope — README also points to the fqdn output; region default is westeurope.
  - Verification Gap:
    - `[medium]` `[patch]` cloud-init forced command and sshd hardening untested — test run added on decoded custom_data.
    - `[medium]` `[patch]` budget and NSG association not asserted — budget run added; association ids are unknown at plan, and its existence is fixed by source (reject that half as low).
    - `[low]` `[defer]` orchestrator gate — same as Blind Hunter; deferred.
  - Intent Alignment (descriptive):
    - `[medium]` `[defer]` real plan skipped until bootstrap — recorded as deferred and assumption.
    - `[low]` `[reject]` "one VM" not counted — one resource block; adequate.
    - `[low]` `[reject]` certificate issuance not exercised — runtime concern of story 3.2.
    - `[low]` `[reject]` JIT mechanism itself untested — belongs to story 3.3.
    - `[false]` `[reject]` 3.1.2 enforced on source not plan — the boot-diagnostics assertion runs on the plan, and the grep covers resource types; nothing Azure-default enables flow logs.
    - `[low]` `[reject]` ADR 0018 is Proposed pending owner — consistent with ADRs 0006–0017.
    - `[low]` `[reject]` budget/cloud-init beyond narrow reading — story cites I-1 to I-9; within touches.
    - `[low]` `[reject]` gate surface — same as deferred gate item.

## Design Notes

SSH: no standing allow rule. The `deploy` and `kill-switch` workflows (story 3.3 onward) add a priority-100 NSG rule for the runner's `/32` through OIDC and delete it in an `always()` step; the Terraform deny rule at priority 4000 makes the default explicit. The temporary rule is out-of-band drift by design and is named `jit-ssh-*` so the plan shows it if it ever leaks. The forced-command dispatcher accepts only `deploy` and `kill-switch` and runs `/opt/price-checker/bin/<name>`; story 3.3 delivers those scripts.

## Verification

**Commands:**
- `terraform -chdir=infra fmt -check -recursive` -- expected: exit 0
- `terraform -chdir=infra init -backend=false` then `terraform -chdir=infra validate` -- expected: valid
- `terraform -chdir=infra test` -- expected: all pass

## Auto Run Result

Status: done

**Summary:** Terraform under `infra/` for one Ubuntu 24.04 VM in `westeurope` with a static public IP DNS label, an NSG allowing 80/443 with an explicit SSH deny and no standing SSH rule, cloud-init hardening sshd and a forced-command `deploy` user, a resource-group budget alert, OIDC remote backend; `terraform-plan` workflow (fmt, validate, mocked `terraform test`, AD-14 grep, OIDC plan when configured); ADR 0018 for SSH restriction.

**Files:** `infra/{versions,providers,variables,main,outputs}.tf` (resources), `infra/cloud-init.yaml.tftpl` (VM bootstrap), `infra/tests/plan.tftest.hcl` (9 runs), `infra/README.md` (bootstrap, owner-only apply), `infra/.gitignore`, `infra/.terraform.lock.hcl`, `.github/workflows/terraform-plan.yml`, `docs/adr/0018-ssh-restriction-jit-nsg.md`, `docs/adr/README.md`.

**Review:** patched 7 entries (medium 5, low 2); deferred 5 (see frontmatter); rejected findings and reasons are in the triage log. Patches were applied by Marghioala (Developer) directly to save run budget instead of re-engaging the implementation subagent.

**Follow-up review recommended:** true. Unverified risk: the `pc-dispatch` script and sshd drop-in are asserted as text only and have never executed on a VM; the real OIDC plan has never run.

**Verification:** `terraform -chdir=infra fmt -check -recursive` pass; `init -backend=false` + `validate` valid; `terraform -chdir=infra test` 9 passed, 0 failed; AD-14 grep returns nothing on the tree and catches a planted flow-log resource.

**Residual risks:** owner bootstrap required before CI plans; story 3.3 must implement the JIT NSG rule with a shared concurrency group and narrow NSG-scoped role.
