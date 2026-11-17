# Infrastructure standard

- **Followed by:** Pacala (DevOps)
- **Verified by:** Veronel (Security & Compliance), plus `terraform plan` in CI
- **Status:** seed. Loredan (Architect) confirms or changes it at stage 4.

- **I-1** Every Azure resource is defined in Terraform under `infra/`. Nothing is created or changed by hand in the portal.
- **I-2** Every PR runs `terraform fmt -check`, `validate` and `plan`, and the plan is attached to the PR. **Only a human runs `terraform apply`.** Agents never do.
- **I-3** The app runs on one Linux VM in an EU region, with a network security group that allows only 443, 80 (redirect) and SSH from the deploy source.
- **I-4** Terraform state is kept in a remote Azure Storage backend with locking. State files are never committed.
- **I-5** GitHub Actions authenticates to Azure through OIDC. No long-lived cloud credentials are stored anywhere.
- **I-6** The VM runs Docker Compose with the web app, the backend and a reverse proxy that handles TLS. Image tags are pinned, never `latest`.
- **I-7** Deployment happens only from GitHub Actions, after a merge to `main`, using the SSH deploy key. Agents never open SSH sessions to the VM.
- **I-8** Every resource is tagged `project=price-checker` and `env=<env>`. Azure Budgets has a cost alert set at the owner's ceiling.
- **I-9** Kill switches and retailer config live in a mounted config file that the backend reloads, so they can be changed without a deploy (`security-compliance.md` S-8).
- **I-10** The health check runs on a schedule as a GitHub Actions cron job. When it fails, it opens an `adapter-broken` issue with the failing fixture attached.
