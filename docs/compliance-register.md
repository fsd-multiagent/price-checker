# Compliance register

- **Maintained by:** Aglaia (Knowledge Keeper), from rows that Veronel (Security & Compliance) drafts at stage 7
- **Verified by:** Veronel (Security & Compliance) at stages 3, 4 and 7
- **Purpose:** link every compliance requirement to the control that enforces it and the evidence that the control works. If a row has no evidence by the end of epic 1, the epic is not done.

## Columns

- **Requirement:** where the obligation comes from.
- **Control:** the rule ID in `docs/standards/` and where it is enforced.
- **Evidence:** a test, a CI job, a file or a verdict that shows the control works. Use a path or a link, never prose alone.
- **Status:** `Planned` (no code yet) → `Implemented` (code merged) → `Verified` (evidence exists and passes). Use `Retired` for a requirement that no longer applies. Never delete a row.

## Register

| ID | Requirement | Control | Enforced in | Evidence | Status |
|---|---|---|---|---|---|
| CR-1 | Contact only approved retailers (spec §8.1) | S-1 | Shared fetch client (C-12) reads the approved list; guard G4 for agent sessions; `registry.Registry` parses the register bundled into the backend (only `Approved YYYY-MM-DD` approves, a parse error approves nothing) and refuses to start when `registry.ConfigCheck` finds an `adapter: enabled` entry that is not Approved (ADR 0009, story 1.5) | `.claude/hooks/test_guard.py` (G4 tests); `backend/src/test/java/ro/pricechecker/registry/SourceRegisterTest.java`, `ConfigCheckTest.java` (`ac_1_5_2_enabledButNotApproved`, `ac_1_5_2_registryRefusesToStartOnViolation`), `BundledConfigTest.java`; fetch-client test TBD (stage 7) | Planned |
| CR-2 | Only a human approves a retailer (spec §4) | S-1 | Guard G5 blocks agent edits that set `Approved`; `.claude/settings.json` asks before guardrail edits; the backend reads approval only from the bundled register at fixed classpath locations, never from a property or environment variable (AC 1.5.4) | `.claude/hooks/test_guard.py` (G5 tests); `backend/src/test/java/ro/pricechecker/registry/ApprovalBypassTest.java`, `RegistryAcceptanceGapsTest.java` (`ac_1_5_4_*`) | Implemented |
| CR-3 | Public pages only, logged out (spec §8.2) | S-2 | Fetch client sends no cookies or credentials | TBD (stage 7) | Planned |
| CR-4 | One lookup per retailer per EAN; no crawling (spec §8.2) | S-3 | Fetch client and adapters; cache (FR-6) | TBD (stage 7) | Planned |
| CR-5 | Respect robots.txt (spec §4) | S-4 | Fetch client checks robots.txt; source register records disallowed paths | TBD (stage 7) | Planned |
| CR-6 | Rate and concurrency limits; honor Retry-After; back off (spec §8.3) | S-5 | Fetch client, using the limits in `docs/source-register.md`; `registry.ConfigCheck` fails the backend tests and startup when `config/retailers.yaml` `minIntervalMs` or `concurrency` is looser than the register's Limits column, or that column cannot be parsed (story 1.5) | `backend/src/test/java/ro/pricechecker/registry/ConfigCheckTest.java` (`ac_1_5_2_looserIntervalOrConcurrency`, `ac_1_5_2_unparseableRegisterLimits`); Retry-After and back-off TBD (stage 7) | Planned |
| CR-7 | Honest user agent with a contact URL; no proxies, CAPTCHA solving or spoofing (spec §8.4) | S-6 | Fetch client config | TBD (stage 7); contact URL still needed from the owner | Planned |
| CR-8 | Store facts only (spec §8.5) | S-7 | Domain model; fixtures stripped (T-2) | TBD (stage 7) | Planned |
| CR-9 | Per-retailer kill switch without a deploy (FR-7) | S-8, I-9 | Reloadable config file | TBD (stage 6–7) | Planned |
| CR-10 | No personal data, no accounts, no IP logging | S-9, C-9 | Backend logging config | TBD (stage 7) | Planned |
| CR-11 | No secrets in repo, images, logs or state | S-10, I-5 | `.gitignore`, CI secret scan (gitleaks over each PR's or push's commit range, ADR 0024), OIDC; settings deny reading `.env`, tfstate, tfvars | `.gitignore` (state, tfvars) and `infra/.gitignore` (plan files); `infra/versions.tf` and `infra/providers.tf` (`use_oidc`, partial backend); `.github/workflows/terraform-plan.yml` (OIDC, `budget_contact_email` sensitive); `.github/workflows/ci.yml` job `secret-scan` (canary step proves a planted key fails, AC 1.9.2) | Planned |
| CR-12 | Validate input; encode the EAN in retailer URLs | S-11 | Backend validation | TBD (stage 7) | Planned |
| CR-13 | Pinned dependencies; no known critical vulnerabilities | S-12 | CI dependency scan: Trivy over lockfiles and the backend boot jar, runtime dependencies, HIGH and CRITICAL fail (ADR 0024); actions pinned to commit SHAs and scanner binaries to version plus SHA-256 | `.github/workflows/ci.yml` job `vulnerability-scan` (canary step proves a lodash 4.17.15 lockfile fails, AC 1.9.3); dev dependencies, jar canary and Gradle wrapper validation not covered | Planned |
| CR-14 | TLS only; ports 80 (redirect) and 443; SSH only from the deploy source | S-13, I-3, ADR 0018 | `infra/main.tf` NSG (allow 80 and 443, deny 22 from Internet, no standing SSH allow); `infra/cloud-init.yaml.tftpl` (key-only sshd, forced-command `deploy` user); reverse proxy (story 3.2); JIT SSH rule (story 3.3) | `infra/tests/plan.tftest.hcl` (`ac_3_1_1_nsg_allows_only_80_and_443_and_no_ssh_from_internet`, `adr_0018_cloud_init_restricts_deploy_key_and_hardens_sshd`); TLS and JIT evidence TBD (stories 3.2, 3.3) | Planned |
| CR-15 | `terraform apply` is done by a human only (plan §6) | Guard G1 | `.claude/hooks/guard.py`, `.claude/settings.json` deny rules | `.claude/hooks/test_guard.py` (G1 tests) | Implemented |
| CR-16 | Changes reach `main` only through a PR with green CI (plan §4.2) | Guard G2; branch protection | `.claude/hooks/guard.py`; GitHub branch protection (step 0.9) | `.claude/hooks/test_guard.py` (G2 tests); branch protection TBD | Implemented |
