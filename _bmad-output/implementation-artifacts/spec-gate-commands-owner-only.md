---
title: 'Gate commands change only through an owner PR'
type: 'bugfix'
created: '2026-11-18'
status: 'done'
route: 'oneshot'
review_loop_iteration: 0
context: []
---

<frozen-after-approval reason="human-owned intent — do not modify unless human renegotiates">

## Intent

**Problem:** Agent branches other than stories (learn, planning, infra) may edit `AGENTS.md`, and their PRs auto-merge with no review. So an agent could change the `sdlc-commands` gate block that gates every later story (found on learn PR #13). ADR 0004 only stops a story from changing its own gate.

**Approach:** The owner asked for both fixes:

1. Before every agent commit, the orchestrator puts the block back as it is on `origin/main`, keeps the agent's other edits, and records the restore. If the block or its section is gone, the whole file is restored.
2. A `.github/CODEOWNERS` entry names the owner for `AGENTS.md`. It has no effect until the owner turns on code-owner review.

Record the decision as ADR 0021 and in spine AD-21.

</frozen-after-approval>

## Implementation Notes

- `orchestrator/src/sdlc/adapters/commands.py`:
  - `_block_span` and `restore_commands_block(current, base)` return the restored text and what was restored (`"sdlc-commands block"` or `"whole file"`).
  - They splice by offset inside the "Running and verifying" section, so an earlier copy of the same text is never touched.
  - A deleted file, or a missing section or block, restores all of `base`.
- `orchestrator/src/sdlc/app/loop.py`:
  - `_pre_commit_guards(scope, worktree)` is the one place every orchestrator commit on an agent branch goes through, including the `render_sprint_status` commit:
    - story scopes revert the bookkeeping paths
    - learn scopes revert `learn_revert_paths`
    - every non-story scope restores the gate block from `origin/main`
  - A restore records the real path `AGENTS.md` in the reverted list (step log and `paths_reverted` event), and the run log says whether the block or the whole file came back.
- `core/models.py`, `core/state.py`: updated `PathsReverted` docstrings.
- `sdlc/prompts/knowledge-keeper.md`, `infra.md`, `architecture.md`: one paragraph saying the block is off-limits and how to recommend a gate change.
- `orchestrator/tests/test_gate_guard.py`, with unit cases for:
  - an unchanged block, a changed block, an earlier quoted copy
  - a removed block, a deleted file, a base without a block

  A dry run checks that every non-story worktree that commits gets the block back, that story scopes are skipped, and that nothing happens when `origin/main` has no `AGENTS.md`. A prompt test is included too. `test_usage.py` was adjusted to the new log wording.
- Docs:
  - ADR 0021 and its index entry
  - the AD-21 bullet in the orchestrator spine
  - the sentence in `AGENTS.md` next to the block
  - `.github/CODEOWNERS`, covering the control files (off until the owner turns on code-owner review)
  - a deferred-work entry for the remaining gap

## Review Triage Log

Blind Hunter, 2026-11-18. All fixed in this change unless noted.

| # | Finding | Verdict | Evidence | Route |
|---|---|---|---|---|
| 1 | A deleted `AGENTS.md` is not restored | high | `current is None` returned False; a deletion would auto-merge and fail every later gate closed | patch |
| 2 | The sprint-status commit skips the guard and the learn revert | medium | `render_sprint_status` called `commit_all` directly | patch (shared `_pre_commit_guards`) |
| 3 | The splice can replace an earlier identical copy | medium | `str.replace(..., 1)` picks the first occurrence | patch (splice by offset) |
| 4 | "No-op for story branches" is false when `origin/main` moved | medium | The bookkeeping revert targets HEAD, the guard targets `origin/main` | patch (story scopes skipped) |
| 5 | A whole-file restore is logged as a block restore | low | The label did not distinguish the two | patch (run log names "whole file") |
| 6 | `PathsReverted.paths` held a label; docstrings were stale | low | `"AGENTS.md (sdlc-commands block)"` is not a path | patch (real path; docstrings) |
| 7 | No prompt tells agents the block is off-limits | medium | Only `sdlc/**` was mentioned | patch (keeper, architecture and infra prompts, plus a test) |
| 8 | A wider bypass through orchestrator code, test configuration and workflows | medium | Learn, planning and infra PRs can edit them and auto-merge | ADR consequence plus deferred work |
| 9 | CODEOWNERS inert and incomplete | low | Owner-requested; now covers the control files including itself; the ADR explains it is off | patch |
| 10 | The dry-run test is weaker than its name | medium | Did not tie restores to commits, skip stories, or cover a missing base | patch |
| 11 | The wording overclaims "only through an owner PR" | low | See row 8 | patch (AGENTS.md, ADR, spine) |
