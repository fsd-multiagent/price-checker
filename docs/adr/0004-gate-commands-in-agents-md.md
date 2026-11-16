# 0004. Declare gate commands in an `sdlc-commands` block in `AGENTS.md`, trusted only as it is on `origin/main`

- **Status:** Accepted
- **Date:** 2026-11-16
- **Deciders:** Marghioala (Developer), Loredan (Architect); accepted by Adrian (owner), 2026-11-16
- **Stage / story:** setup step 0.8 (real adapters)
- **Rules touched:** T-8, D-2; orchestrator spine AD-19, AD-21

## Context

T-8 says that Zamolxe (Orchestrator) runs exactly the test commands listed in the root `CLAUDE.md` (which loads `AGENTS.md`). The `tests` and `terraform_plan` gates need those commands in a form a program can read. The gates run outside the Claude guard hook, and the agents being gated can edit `AGENTS.md` in their own worktree.

## Options considered

| Option | Pros | Cons |
|---|---|---|
| A. A fenced `yaml sdlc-commands` block under "Running and verifying" in `AGENTS.md`; the gate refuses to run if the worktree's block differs from the one on `origin/main` | One source, read by agents and by the orchestrator; agents cannot weaken their own gate | A change to the commands lands only through a merged PR |
| B. A `commands` map in `sdlc/pipeline.yaml` | Easy to parse | Two sources for the same commands; breaks T-8 |
| C. Parse the prose list in `AGENTS.md` | No new format | Fragile; ambiguous |

## Decision

Option A. The block maps a name (`tests`, `terraform_plan`) to a list of commands. Each command is split without a shell; shell operators and the commands the guard denies (`terraform apply`/`destroy`, `git push`, `gh pr merge`, `ssh`, `scp`, `sftp`) are refused. A missing name, or a block that differs from `origin/main`, fails the gate (fail closed).

## Consequences

- The gate commands change only through a reviewed PR, normally a stage 9 learn PR, since `AGENTS.md` is a bookkeeping path (AD-21).
- Stage 4 must add the backend and web test commands to the block (the TODO in `AGENTS.md`).

## Compliance

It strengthens the guardrails in plan §5: the gates cannot run the commands the guard denies to agents.
