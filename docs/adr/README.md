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
