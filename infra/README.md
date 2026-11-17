# infra

Terraform for the single `prod` environment (ADR 0015): one Ubuntu 24.04 VM in
an EU region, its VNet, an NSG that allows only TCP 80 and 443 from the
Internet, a static Standard public IP with the DNS label `price-checker-prod`
(Caddy's hostname `price-checker-prod.westeurope.cloudapp.azure.com`), and a
resource-group budget alert (I-8). SSH has no standing allow rule (ADR 0018).

Not here on purpose: NSG flow logs, Network Watcher, diagnostic settings and
VM boot diagnostics (AD-14, ADR 0017). CI fails if any appear.

## What CI does

`.github/workflows/terraform-plan.yml` runs on every PR: `fmt -check`,
`validate`, `terraform test` (mocked provider, no Azure access), the AD-14
grep, and, when the Azure repository variables are set, `terraform plan` over
OIDC with the plan in the job summary and as an artifact (I-2). It never applies.

## Local checks (no Azure access needed)

```sh
terraform -chdir=infra fmt -check -recursive
terraform -chdir=infra init -backend=false
terraform -chdir=infra validate
terraform -chdir=infra test
```

## One-time bootstrap (owner only)

Agents do not do these steps (I-2, I-7).

1. Create a state storage account and container in an EU region, outside this
   configuration (it holds the state for it). Disable public blob access and
   shared-key access; blob leases give locking (I-4).
2. Create an Entra ID app registration with federated credentials for this
   repository (`pull_request`, `ref:refs/heads/main`). Grant it `Reader` on the
   subscription and `Storage Blob Data Contributor` on the state container, so
   PR plans can read and lock state. The identity used for `apply` (the owner)
   needs `Contributor` on the subscription. No client secret (I-5, S-10).
3. Set repository variables: `AZURE_CLIENT_ID`, `AZURE_TENANT_ID`,
   `AZURE_SUBSCRIPTION_ID`, `TF_STATE_RESOURCE_GROUP`,
   `TF_STATE_STORAGE_ACCOUNT`, `TF_STATE_CONTAINER`, `TF_STATE_KEY`,
   `ADMIN_SSH_PUBLIC_KEY`, `DEPLOY_SSH_PUBLIC_KEY`; and the secret
   `BUDGET_CONTACT_EMAIL`. The deploy private key goes in the `DEPLOY_SSH_KEY`
   secret (story 3.3).

## Apply (owner only)

Review the PR's plan, then from a clean checkout of `main`, signed in with
`az login`, pass the same backend settings with `-backend-config` and the
variables with `TF_VAR_*` environment variables (never a committed
`.tfvars`), and run init, plan and apply yourself.

## Notes

- The VM's `custom_data` (cloud-init) is in `ignore_changes`; changing
  `cloud-init.yaml.tftpl` does not recreate the VM. To roll it out, replace the
  VM deliberately with `-replace=azurerm_linux_virtual_machine.app`.
- cloud-init sets Docker's `local` log driver, capped at 5 × 10 MB per
  container, so the AD-14 stdout logs cannot fill the 30 GB disk (ADR 0020).
- A rule named `jit-ssh-*` showing as drift in a plan means a deploy workflow
  failed to remove its temporary SSH rule (ADR 0018); the next apply removes it.
