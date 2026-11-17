# 0002. Keep run state in a single-writer `state.json`, and resume by resetting to the scope's last passed head

- **Status:** Accepted
- **Date:** 2026-11-16
- **Deciders:** Loredan (Architect); accepted by Adrian (owner), 2026-11-16
- **Stage / story:** setup step 0.8
- **Rules touched:** D-2; automation-plan §4.1 (state on disk, resume after a crash), §4.4

## Context

Plan §4.1 requires that a run can be resumed after a crash, with state kept under `_bmad-output/sdlc/runs/<run-id>/`. Agent steps change git themselves: `bmad-build-auto` commits its own work. A crash in the middle of a step therefore leaves partial commits behind, and resuming must not stack a second attempt on top of them.

## Options considered

| Option | Pros | Cons |
|---|---|---|
| A. `state.json` as the authority, one writer, atomic replace; on resume or a failed round, reset the worktree to the scope's last passed `head_sha` and rerun the step | Simple; easy to inspect; no half-applied work; never wipes a step that passed | Work done before a crash is discarded (kept as a patch) |
| B. Event sourcing: state is a fold over `events.jsonl` | A full replay history | More code; two sources to keep consistent |
| C. Continue an interrupted step where it stopped | Saves cost | Cannot be done: steps use `--no-session-persistence`, and partial agent work is not trustworthy |

## Decision

Option A, as follows:

- Only the `store` module writes `state.json`, atomically, after every transition. It is the only source of status; `sprint-status.yaml` is rendered from it.
- `events.jsonl` is append-only audit.
- Each scope records `head_sha` after every step that passes. A failed round, a timeout, or a step still `running` on load resets the worktree to that `head_sha`, and the discarded diff is saved as a patch.
- At most one step per worktree writes at a time (AD-16), so the reset never touches another step's work.
- Any rebase in progress is aborted on load.
- `sdlc resume` resets the round counter of the escalated `(scope, gate)`.
- Statuses and transitions come from a closed set.

The binding rules are AD-4 to AD-6, AD-13 and AD-16 in the orchestrator architecture spine.

## Consequences

- A crash costs at most one step's work and budget.
- Every external effect (issue, PR, tag) must be idempotent and found by a key (AD-13), or a resume would duplicate it.
- The run dir is gitignored and lives only in the main checkout.

## Compliance

No compliance impact: this decision covers only local state and git history.
