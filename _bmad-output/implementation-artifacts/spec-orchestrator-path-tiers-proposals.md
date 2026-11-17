---
title: 'Zamolxe (Orchestrator) path tiers, pinned controls and owner-review proposals'
type: 'feature'
created: '2026-11-18'
status: 'done'
route: 'dispatch'
review_loop_iteration: 0
baseline_commit: 'f80c80b60b8737bb03fce714d04d25fe2d538b76'
context:
  - '{project-root}/_bmad-output/planning-artifacts/architecture/architecture-orchestrator-2026-11-16/ARCHITECTURE-SPINE.md'
  - '{project-root}/docs/adr/0022-factory-changes-by-proposal-and-issue.md'
---

<frozen-after-approval reason="human-owned intent — do not modify unless human renegotiates">

## Intent

**Problem:** An agent's edit to a factory file is silently reverted. So factory recommendations are lost, or become owner chores, and nothing pins the guard hook and settings that an agent's `claude -p` loads from its worktree.

**Approach:** Implement AD-26 of the orchestrator spine exactly: the three path tiers, pinned `owner_only_paths` before every agent and judge step, proposals captured before the revert and delivered as `owner-review` PRs or issues, and the `gh` adapter's refusal to merge them. AD-27 (issue runs) is a separate, later spec. Run `20261117-174705-full` is in progress and must stay resumable (`orchestrator/tests/fixtures/legacy/state-pre-ad24.json`).

## Boundaries & Constraints

**Always:**
- Tiers come from `settings` (`owner_only_paths`, `protected_paths`, `review_paths`) with the spine defaults, matched by path prefix or exact file. The `sdlc-commands` block of `AGENTS.md` is protected through `restore_commands_block`, and a new `apply_commands_block(base, agent_text)`.
- A proposal is a `Proposal` record in `state.json`, keyed by the patch's SHA-256, and delivered by an orchestrator action between ticks (like `OpenEscalation`), never inside an agent step.
- Patch capture: `git add -A -- <paths>`, then `git diff --cached --binary --full-index HEAD -- <paths>`.
- Delivery:
  - Applying is `git apply --3way` and counts only if the exit code is 0 and no unmerged entries remain.
  - On failure, or when the patch touches `review_paths`, the proposal becomes an issue.
  - The marker `<!-- sdlc-proposal:<sha256> -->` is searched across all PR and issue states.
  - The label `owner-review` is created if it is missing (`gh label create --force`).
- `adapters/gh.py` refuses `merge` and auto-merge on a PR labelled `owner-review`.
- Existing behaviours stay: story bookkeeping reverts, the learn revert of `sdlc/`, and the ADR 0021 block restore. Factory-story exceptions come in the AD-27 spec, not here.

**Never:**
- No agent-written workflow is pushed from a proposal.
- No proposal blocks the run or consumes a round.
- No paid call or GitHub write in the default tests (fakes and stub `gh`).
- No edits to `.claude/settings*.json`, `.claude/hooks/` or `docs/source-register.md` in this change.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Pinned controls | An earlier step left a changed `.claude/hooks/guard.py` in the worktree | Before the next agent step it equals `origin/main` | N/A |
| Owner-only edit | A learn step edits `docs/source-register.md` | Reverted; no proposal | N/A |
| Protected edit becomes a PR | A learn step edits `sdlc/prompts/qa.md` and adds `sdlc/prompts/new.md` | The commit has neither; a `Proposal` holds both, new file included; a PR on `proposal/<sha8>` with the label, the marker and no auto-merge | N/A |
| Gate block proposal | A step changes the `sdlc-commands` block, plus other `AGENTS.md` lines | The proposal's `AGENTS.md` equals `origin/main` with only the agent's block spliced in | N/A |
| Workflow edit | A learn step edits `.github/workflows/ci.yml` | Not pushed; an `owner-review` issue with the patch text | N/A |
| Apply conflict | `origin/main` changed the same lines | An issue, not a PR; the proposal worktree is reset | N/A |
| Dedupe | The same patch in two rounds, or after a resume, or after the owner closed the PR | One PR or issue only, never reopened | N/A |
| Merge refusal | The orchestrator tries to merge or auto-merge an `owner-review` PR | `AdapterError` | The step fails |
| Retry prompt | A failed round that touched protected paths | The discarded patch in the fix prompt excludes protected paths | N/A |
| Notification and status | A new proposal | One notifier call; `sdlc status` lists "owner review: PR #n" | N/A |
| Legacy state | The legacy fixture loads with the new code | It loads; there are no proposals | N/A |

</frozen-after-approval>

## Code Map

- `orchestrator/src/sdlc/app/loop.py`:
  - `_pre_commit_guards` (capture, then revert)
  - the step start (pin `owner_only_paths` via `git.restore_from(ref, paths)`)
  - `_run_agent` discarded patch (exclude protected)
  - the action handling for `DeliverProposal`
- `orchestrator/src/sdlc/adapters/commands.py`:
  - `restore_commands_block` already exists
  - add `apply_commands_block(base, agent_text)`
- `orchestrator/src/sdlc/adapters/git.py` (and the fake): `staged_diff(worktree, paths)`, `restore_from(worktree, ref, paths)`, and a proposal worktree with `apply_3way(worktree, patch) -> bool`.
- `orchestrator/src/sdlc/adapters/gh.py` (and the fake, plus the stub `gh`):
  - `ensure_label`
  - `ensure_proposal_pr` and `ensure_proposal_issue`, both searching by marker across all states
  - the merge and auto-merge refusal
- `orchestrator/src/sdlc/core/models.py`, `core/state.py`, `core/scheduler.py`: the settings tiers, the `Proposal` record and events, and the `DeliverProposal` action.
- `orchestrator/src/sdlc/core/usage.py` / `format_status`: an "owner review" list.
- `sdlc/pipeline.yaml`: the three tiers with the spine defaults.
- `sdlc/prompts/knowledge-keeper.md`, `architecture.md`, `infra.md`, `dev.md`: one paragraph on how to propose, and on what is off limits.

## Tasks & Acceptance

**Execution:**
- [x] The models, settings and pipeline tiers.
- [x] git and gh adapters, fakes and the stub, with their new operations.
- [x] Capture in `_pre_commit_guards`, pinning at the step start, the fix-prompt exclusion.
- [x] The `Proposal` state, the `DeliverProposal` action and its handling, the notifier call and the status list.
- [x] The prompts.
- [x] Tests: one per matrix row (`test_proposals_<row>`).

**Acceptance Criteria:**
- AC-1: Given the repo, when `uv run pytest -q` and `uv run ruff check && uv run ruff format --check` run in `orchestrator/`, then all pass, with the live test skipped.
- AC-2: Given `uv run sdlc run --dry-run`, when it runs, then it exits 0.
- AC-3: Given the legacy state fixture, when it is loaded and resumed with fakes, then it completes as before.

## Implementation Notes

- `review_paths` are captured (as issue proposals) and reverted on learn branches only, as the matrix row says. On story, planning and infra branches they stay allowed, as in the AD-26 tier table; the owner-review wait for those PRs is AD-27.
- No pinning (spine AD-26 as amended): owner-only paths are only reverted before commits, and a reviewer that changes any file outside `.sdlc/` fails. The `AGENTS.md` block is compared with the scope's branch point (ADR 0023); a deleted or added block proposes the whole file. The proposal key is the normalised change (path and new blob or deletion). Every writing prompt renders the tiers from the settings as `${factory_rules}`.
- The `AGENTS.md` part of a proposal is a text diff from `origin/main`'s file to `apply_commands_block(base, agent_text)`, appended to the git patch; `git apply --3way` applies both.
- A failed delivery is recorded as `failed` (the run goes on) and set back to `pending` by `sdlc resume`. The notifier fires only when a PR or issue is created, not when one is found by its marker.
- `GhCli.merge` now reads `labels` in its first `pr view`, which must succeed (retried), and refuses an `owner-review` PR before any `pr merge` call.

## Spec Change Log

- 2026-11-18, after review pass 1. **Trigger:** triage row 2: pinning `owner_only_paths` to `origin/main` before every step dirtied worktrees, swapped controls under running agents, and hid reviewer edits.
  - **Amended:** the orchestrator spine AD-26 and ADR 0022 (not yet on `main`) drop pinning. The frozen Intent, the matrix row "Pinned controls" and the Code Map entry for pinning are superseded by that amendment; only the owner may rewrite the frozen text.
  - **Avoided state:** dirty worktrees, failed rebases, and silent owner-only edits by reviewers.
  - **KEEP:** owner-only paths are reverted before every commit; reviewer edits to them fail.

## Review Triage Log

## Verification

**Commands:**
- `cd orchestrator && uv run pytest -q` -- expected: all pass, live skipped
- `cd orchestrator && uv run ruff check && uv run ruff format --check` -- expected: clean
- `cd orchestrator && uv run sdlc run --dry-run; echo $?` -- expected: 0

## Review Triage Log

Pass 1 (2026-11-18). Reviewers: BH = Blind Hunter, VG = Verification Gap, EC = Edge Case Hunter.

| # | Finding | Verdict | Evidence | Route |
|---|---|---|---|---|
| 1 | BH1, VG-o1, EC1: the block proposal compares with today's `main`, so it undoes owner changes made mid-run | high | `_capture` uses `git.show(BASE_REF, …)`; the same drift as ADR 0023 | patch (branch point) |
| 2 | BH2, BH3, EC2, EC8, EC10, VG2: pinning to `origin/main` swaps controls under running agents, dirties script steps, causes false owner-only reverts, and hides reviewer edits | high | `_pin` restores per tick per scope | spine amended: pinning dropped (AD-26); patch |
| 3 | BH4: reviewer edits to owner-only paths pass silently | high | Owner-only paths excluded from `_check_reviewer_edits` | patch (covered by row 2) |
| 4 | BH5, EC9: the fix prompt replays the block and `review_paths` | medium | `_fix_diff` excludes two tiers only | patch |
| 5 | BH6: the SHA-256 dedupe depends on base blob hashes | medium | `--full-index` patch text | patch (normalised key) |
| 6 | BH7, EC5: `find_proposal` runs twice, is capped at 1000, matches any body | medium | Two calls per delivery | patch |
| 7 | BH8: a retried push is not fast-forward; worktrees pile up | medium | A reset branch is pushed again | patch |
| 8 | BH9, EC6: the issue patch is clipped mid-hunk, the path list is unbounded, and the body refers to a local path | medium | `issue_text` clip at 60,000 | patch |
| 9 | BH10: ADR 0022 missing; AD-26 undefined | false | ADR 0022 and AD-26 are committed in `95bc32e`; the review diff excluded `docs/adr` | reject |
| 10 | BH10b: AGENTS.md not updated | low | Direct addition | patch |
| 11 | BH11: the prompt paragraph is copied four times and missing from six prompts | medium | QA may edit `orchestrator/` unknowingly | patch (rendered from settings) |
| 12 | BH12: a failed delivery is not notified | low | Echo only | patch |
| 13 | EC3: the patch is saved but `ProposalCaptured` is lost if a revert raises | medium | The order in `_pre_commit_guards` | patch |
| 14 | EC4: a deleted or added block is dropped silently | medium | `apply_commands_block` returns None | patch |
| 15 | EC7: the `owner-review` label is checked only on the first view | medium | `merge` polls without re-checking | patch |
| 16 | VG1, VG3, VG4, VG5, BH13: test gaps (pin failure, resume retry, no-op patch, clipping, fences, two heads) | medium | Pre-verified | patch (pin tests moot after row 2) |
| 17 | VG-o2: protected story edits are dropped | low | Intended for normal stories; factory stories come in AD-27 (part 2) | reject (by design) |
