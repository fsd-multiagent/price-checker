# Deploy scripts (story 3.3)

`.github/workflows/deploy.yml` runs on every push to `main`. It builds the
`backend` and `web` images, tags them with the full git SHA, pushes them to
GHCR and then runs `ssh deploy@<fqdn> deploy <sha> <fqdn>` through the
just-in-time NSG rule (ADR 0015, ADR 0018). On the VM the forced command
`pc-dispatch` runs `/opt/price-checker/bin/deploy`, which logs in to GHCR with
the workflow's `GITHUB_TOKEN` on stdin, pulls, logs out and runs
`docker compose up -d` in `/opt/price-checker/app`. It never touches the
files under `/opt/price-checker/config` or `/opt/price-checker/state` (AD-9).

| File | Holds |
|---|---|
| `deploy` | The VM script. Arguments: a 40-hex SHA and a DNS name. stdin: registry user line, token line, then a tar of `compose.yaml` and `Caddyfile`. |
| `install` | One-time root bootstrap: installs `deploy` as `/opt/price-checker/bin/deploy` (root, 0755) and creates `config`, `state` and `app` (`deploy:deploy`, 0750). |

## Owner setup (agents do not do these steps, I-2, I-7)

1. Bootstrap the VM once, and again whenever `deploy/scripts/deploy` changes:
   copy `deploy/scripts/` to the VM over your admin access (ADR 0018
   break-glass) and run `sudo ./install`. Changes to `deploy/compose.yaml` and
   `deploy/Caddyfile` need no owner step: each deploy sends them.
2. Repository variables, from `terraform output`: `PROD_FQDN` (`fqdn`),
   `PROD_RESOURCE_GROUP` (`resource_group_name`), `PROD_NSG_NAME`
   (`nsg_name`). The deploy job is skipped while `PROD_FQDN` is empty.
3. Required: `PROD_SSH_HOST_KEY`, the VM's host key as `ssh-ed25519 AAAA...`
   (read it from `/etc/ssh/ssh_host_ed25519_key.pub` during break-glass access).
   The deploy fails without it, so `GITHUB_TOKEN` never goes to an unverified host.
4. Repository secret `DEPLOY_SSH_KEY`: the private key matching
   `DEPLOY_SSH_PUBLIC_KEY` (infra). Workflows read no other secret than it and
   `GITHUB_TOKEN`.
5. Deploy OIDC identity: add a federated credential for
   `repo:<owner>/<repo>:ref:refs/heads/main` to the app registration in
   `AZURE_CLIENT_ID`, and grant it a custom role scoped to the NSG only with
   `Microsoft.Network/networkSecurityGroups/read`,
   `Microsoft.Network/networkSecurityGroups/securityRules/read`,
   `/securityRules/write` and `/securityRules/delete` (ADR 0018). The PR plan
   identity stays read-only; if it shares the app registration, the plan job
   gains the same NSG rights, so a separate app registration for deploys is
   cleaner.
6. GHCR: the first push creates the packages `price-checker-backend` and
   `price-checker-web` under the organisation; give the repository access to
   them if the organisation does not link packages to repositories by default.
