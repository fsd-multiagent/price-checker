# 0021. Restore the gate-command block on every agent branch, so gate commands change only through an owner PR

- **Status:** Accepted
- **Date:** 2026-11-18
- **Deciders:** Loredan (Architect); requested and accepted by Adrian (owner), 2026-11-18
- **Stage / story:** orchestrator fix during phase 1 (found on learn PR #13)
- **Rules touched:** T-8, D-2; extends ADR 0004; orchestrator spine AD-21

## Context

ADR 0004 reads the gate commands from the `sdlc-commands` block in `AGENTS.md` on `origin/main`, and it fails a story whose worktree changes that block. That stops a story from weakening its own gate. It does not stop other agent branches:

- Learn branches (Aglaia (Knowledge Keeper)), the planning branch and the infra branch may edit `AGENTS.md`.
- Their PRs auto-merge with 0 approvals.

So an agent could change the gate commands for every later story without the owner seeing it. Learn PR #13 edited `AGENTS.md` next to the block, though it left the block alone.

## Options considered

| Option | Pros | Cons |
|---|---|---|
| A. Before every agent commit, the orchestrator puts the block back as it is on `origin/main`, keeps the agent's other edits, and records the restore | No escalation; docs edits still flow; the control does not depend on agent behaviour | Only the orchestrator's commits are covered (agents do not push) |
| B. Fail the step when the block changed | Loud | Every such learn step escalates over a docs edit |
| C. CODEOWNERS on `AGENTS.md` with required code-owner review | Enforced by GitHub | Blocks auto-merge for every learn PR that touches `AGENTS.md`; the orchestrator's merge wait then times out and escalates |

## Decision

Option A, with option C prepared but switched off.

- **Option A.** Before each of its commits on a planning, infra or learn branch, including the sprint-status commit, the orchestrator puts the `sdlc-commands` block back as it is on `origin/main`.
  - The agent's other edits to the file are kept.
  - If the agent deleted `AGENTS.md`, or removed the block or its section, the whole file is restored, and the agent's other edits to it are lost.
  - Either way, the run log names what was restored, and `AGENTS.md` is recorded as a reverted path in the step log and in a `paths_reverted` event.
  - Story branches need no restore: they already revert all of `AGENTS.md` (AD-21).
- **Agent prompts.** The knowledge-keeper, architecture and infra prompts tell the agent that the block is off-limits, and that it should recommend a gate change instead.
- **Option C, prepared but off.** At the owner's request, `.github/CODEOWNERS` names the owner for the files that decide what agents may do (`AGENTS.md`, `CLAUDE.md`, `.claude/`, `sdlc/`, `orchestrator/`, the workflows and `CODEOWNERS` itself). It has no effect until the owner turns on "Require review from Code Owners". If turned on, every agent PR touching those paths waits for the owner's approval, and the orchestrator's merge wait for it times out and escalates.

## Consequences

- Agents can no longer change the gate commands through their auto-merged PRs. The commands change through a PR the owner reviews, as with #9 and #12.
- An agent that wants a different gate command records it as a recommendation, and the owner decides.
- **Remaining gap.** Agents can still change *what a gate command does* without touching the block, and those PRs auto-merge too:
  - the orchestrator code under `orchestrator/` (including this guard)
  - the test configuration (`pyproject.toml`, `conftest.py`)
  - the CI workflows under `.github/workflows/`

  Closing it needs the code-owner switch above (with its escalation cost), or path reverts for those files on non-story branches. It is recorded as deferred work.

## Compliance

Strengthens T-8 and the guardrails of `docs/automation-plan.md` §5. No change to S-1 to S-13.
