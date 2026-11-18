# infra/

Terraform for the single `prod` environment: one Ubuntu 24.04 VM in an EU region, VNet, NSG, static public IP with a DNS label, and a resource-group budget (ADR 0015, ADR 0018, ADR 0020, I-1 to I-8). Terraform 1.x, azurerm 4.x. Bootstrap and owner-only apply steps are in `README.md`.

## Commands (from the repo root, no Azure access needed)

| Check | Command |
|---|---|
| Format | `terraform -chdir=infra fmt -check -recursive` |
| Init (no backend) | `terraform -chdir=infra init -backend=false -input=false` |
| Validate | `terraform -chdir=infra validate` |
| Tests (mocked provider) | `terraform -chdir=infra test` |

The orchestrator's `terraform_plan` gate runs these four. The real `terraform plan` runs only in `.github/workflows/terraform-plan.yml`, over OIDC, once the owner sets the `AZURE_*` and `TF_STATE_*` repository variables. Agents never run `apply` or `destroy` (I-2, guard G1).

## Files

| File | Holds |
|---|---|
| `versions.tf`, `providers.tf` | Version pins; partial `azurerm` backend and provider, both `use_oidc` (I-4, I-5) |
| `variables.tf` | `location` (EU list, I-3), `env` (only `prod`), `vm_size`, `dns_label`, SSH public keys, budget settings |
| `main.tf` | All resources |
| `cloud-init.yaml.tftpl` | Docker log rotation (ADR 0020), sshd hardening, `deploy` user with forced command `pc-dispatch` (ADR 0018) |
| `outputs.tf` | `fqdn`, `public_ip`, `resource_group_name`, `nsg_name` (the deploy workflows need the last two for the JIT rule) |
| `tests/plan.tftest.hcl` | `terraform test` runs, named with AC or rule IDs (T-1) |

## Conventions

- Tag every resource with `local.tags` (`project`, `env`) (I-8). Names follow `<type>-price-checker-<env>`, for example `vm-price-checker-prod`.
- NSG rules are inline in `azurerm_network_security_group.app`, so a leaked `jit-ssh-*` rule shows as drift. Never add a standing SSH allow rule (ADR 0018, S-13).
- Never add flow logs, Network Watcher, diagnostic settings, Log Analytics, data collection rules, VM extensions or a `boot_diagnostics` block (I-11). The workflow greps `*.tf` and `*.tftpl` for them.
- Variables come from `TF_VAR_*` environment variables. Never commit `.tfvars`, state or keys (I-4, S-10); agents never read them.
- The VM image `version = "latest"` is a Marketplace image version, not a container tag; I-6 does not apply to it.

## Known pitfalls

- `custom_data` is in `ignore_changes`. Editing `cloud-init.yaml.tftpl` shows no plan diff and does not reach the running VM; the owner rolls it out by replacing the VM (`-replace=azurerm_linux_virtual_machine.app`).
- Changing `admin_ssh_public_key` replaces the VM. Rotating the deploy key is invisible to plan for the same `custom_data` reason. No rotation procedure exists yet (deferred to story 3.3).
- `cloud-init.yaml.tftpl` is a `templatefile()`: write a literal `${` as `$${` and `%{` as `%%{`.
- `budget_start_date` must be the first of a month (`YYYY-MM-01T00:00:00Z`); its later changes are ignored.
- The tests assert the cloud-init and `pc-dispatch` as text only. They have never run on a VM.
