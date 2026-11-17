# Architecture decision records

- **Written by:** Loredan (Architect) at stage 4, and by any agent whose story makes or changes an architecture decision
- **Index maintained by:** Aglaia (Knowledge Keeper) (`docs/standards/documentation.md` D-2)

## Rules

- One decision per file: `NNNN-<slug>.md`, numbered in order, starting from a copy of [`0000-template.md`](0000-template.md).
- Never delete an ADR or reuse a number. When a decision changes, write a new ADR and set the old one's Status to `Superseded by NNNN`.
- Add every new ADR to the index below in the same change.

## Index

| # | Title | Status | Date |
|---|---|---|---|
| [0001](0001-orchestrator-functional-core.md) | Build Zamolxe (Orchestrator) as a functional-core Python CLI that drives headless `claude -p` | Accepted | 2026-11-16 |
| [0002](0002-orchestrator-run-state-and-resume.md) | Keep run state in a single-writer `state.json`, and resume by resetting to the scope's last passed head | Accepted | 2026-11-16 |
| [0003](0003-orchestrator-git-integration.md) | Integrate only through merged PRs, and let the orchestrator own the shared bookkeeping files | Accepted | 2026-11-16 |
| [0004](0004-gate-commands-in-agents-md.md) | Declare gate commands in an `sdlc-commands` block in `AGENTS.md`, trusted only as it is on `origin/main` | Accepted | 2026-11-16 |
| [0005](0005-orchestrator-subscription-limits-and-budget.md) | Run on the subscription: pause on plan limits, wait on budget reservations, refuse per-token credentials | Accepted | 2026-11-17 |
| [0006](0006-backend-ports-and-adapters.md) | Build the backend as one ports-and-adapters Spring Boot process, packaged by feature | Proposed | 2026-11-17 |
| [0007](0007-lookup-api-contract.md) | Serve one Lookup endpoint whose response is already decided: rows ordered, lowest flagged, money in bani, times in UTC | Proposed | 2026-11-17 |
| [0008](0008-shared-fetch-client.md) | Send every retailer request through one fetch client that enforces S-1 to S-6 before each request | Proposed | 2026-11-17 |
| [0009](0009-retailer-config-and-register.md) | Bundle the source register as the compliance authority, and keep retailer config in three files with one writer each | Proposed | 2026-11-17 |
| [0010](0010-ean-resolution-curated-list.md) | Resolve EANs through a curated EAN-to-URL list for every retailer in the first release | Proposed | 2026-11-17 |
| [0011](0011-lookup-execution-and-cache.md) | Run each retailer on its own virtual thread with a deadline, and cache Found/Not found rows in one single-flight cache | Proposed | 2026-11-17 |
| [0012](0012-adapter-contract.md) | Give every retailer Adapter one interface with a sealed outcome and a facts-only result | Proposed | 2026-11-17 |
| [0013](0013-web-stack.md) | Build the web app with React, TypeScript and Vite, a generated client, and no state or routing library | Proposed | 2026-11-17 |
| [0014](0014-backend-build-and-gate-commands.md) | Build the backend with Gradle, check it with Spotless and ArchUnit, and declare the gate commands without `cd` | Proposed | 2026-11-17 |
| [0015](0015-deployment-topology.md) | Run one `prod` environment: Caddy, web and backend in Docker Compose on one VM, deployed and operated only by GitHub Actions | Proposed | 2026-11-17 |
| [0016](0016-health-check-on-runner.md) | Run the Health check as the backend in `health` mode on a GitHub Actions runner, gated by production's `/api/status` | Proposed | 2026-11-17 |
| [0017](0017-logging-and-privacy.md) | Log only an allowlist of fields as JSON to stdout, and turn off access logs | Proposed | 2026-11-17 |
| [0019](0019-orchestrator-usage-ledger-per-agent.md) | Measure every agent step's usage in a ledger derived from the run state, with optional OpenTelemetry export | Accepted | 2026-11-17 |
