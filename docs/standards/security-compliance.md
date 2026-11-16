# Security and compliance standard

- **Followed by:** Marghioala (Developer), Pacala (DevOps), Loredan (Architect)
- **Verified by:** Veronel (Security & Compliance)
- **Sources:** `docs/initial-spec.md` §7–§8, and `docs/source-register.md`. This file turns those sources into controls that can be checked.

## Retailer access

- **S-1** Code may contact a retailer only if the retailer is marked **Approved** in `docs/source-register.md`. Only a human changes that status. Agents may draft a register row, but never approve one.
- **S-2** Fetch only public pages, while logged out. Never create an account, log in, or accept terms in order to fetch.
- **S-3** Make one lookup per retailer per EAN. Never crawl, follow pagination, or prefetch other products.
- **S-4** Respect robots.txt. Never request a path that robots.txt disallows for our user agent (for example, evoMAG search pages).
- **S-5** Each retailer has a rate limit and a concurrency limit. Honor `Retry-After`, back off exponentially on errors, and set timeouts.
- **S-6** Use an honest user agent that includes a contact URL. It must not contain `bot`, `robot`, `crawl` or `spider`, because cel.ro's robots.txt blocks those. Never use proxies, CAPTCHA solving, fingerprint spoofing or header impersonation. If a retailer blocks us, stop and flag it; never work around the block.
- **S-7** Store only facts: price, currency, availability, timestamp and URL. Never store copied descriptions or images.
- **S-8** Each retailer has a kill switch that works without a deploy, through a config file the app reloads.

## Application

- **S-9** Store no personal data. Have no accounts. Never log IP addresses (see `coding.md` C-9).
- **S-10** Never put secrets in the repo, images, logs or Terraform state committed to git. CI uses OIDC to reach Azure and repo secrets for everything else.
- **S-11** Validate every input on the server. The EAN must have the right format and a valid check digit. No input is ever placed into a retailer URL without being encoded.
- **S-12** Pin dependency versions. CI runs a dependency vulnerability scan, and a known critical vulnerability is a blocker.
- **S-13** Serve over TLS only. The VM exposes only ports 80 (redirects to HTTPS) and 443 publicly. SSH access is restricted to the deploy key.

## Review duties of Veronel (Security & Compliance)

- **Stage 3 (PRD):** check that every item in spec §7–§8 is a requirement with acceptance criteria.
- **Stage 4 (architecture):** check that S-1 to S-8 are enforced in code through the shared fetch client (`coding.md` C-12), and are not left to convention.
- **Stage 7 (diff):** check every change against S-1 to S-13. Draft rows for `docs/compliance-register.md` linking requirement, control and evidence.
