# 0003. Integrate only through merged PRs, and let the orchestrator own the shared bookkeeping files

- **Status:** Accepted
- **Date:** 2026-11-16
- **Deciders:** Loredan (Architect); accepted by Adrian (owner), 2026-11-16
- **Stage / story:** setup step 0.8
- **Rules touched:** D-1, D-2, D-5, T-8; automation-plan §4.1 (git is the record), §4.3, §6, §7

## Context

Up to three stories run in parallel worktrees (plan §4.3). `bmad-build-auto` edits `sprint-status.yaml` and the deferred-work file on every story branch. Aglaia (Knowledge Keeper) edits the ADR index, the registers and `CLAUDE.md` after each merge. Left alone, every rebase after the first one conflicts. "Merged to main" also had three possible readings: local `main`, a PR whose auto-merge is enabled, or a PR that is really merged. The guard hook does not see the orchestrator's own git calls, so its code must keep `main` safe itself.

## Options considered

| Option | Pros | Cons |
|---|---|---|
| A. The orchestrator owns the bookkeeping files: it reverts them on story branches and writes them, with Aglaia (Knowledge Keeper), on a `learn/<key>` branch run one at a time after each merge | No bookkeeping conflicts; `state.json` stays the only source of status | Docs land one PR after the code |
| B. Put the bookkeeping files in `serial_paths` | No new mechanism | Every story touches them, so all stories run one at a time |
| C. Resolve conflicts automatically during the rebase | Stories stay parallel | Brittle merge logic for YAML and Markdown |

## Decision

Option A, together with these rules for integration:

- "Merged" means `gh` reports the PR as `MERGED` with a merge SHA.
- Planning stages 1–5 share one branch, `sdlc/<run-id>`, merged through a PR after stage 5. Stages 6 and 7 start only after that merge.
- Every worktree is cut from `origin/main` after a fetch.
- Auto-merge is `--squash --match-head-commit`. It is refused when the repo has no required checks.
- The orchestrator never pushes to `main`.

The binding rules are AD-12, AD-20 and AD-21 in the orchestrator architecture spine.

## Consequences

- Stories stay parallel. Stage 9 adds one small PR per story.
- Owner setup (plan §6) must turn on auto-merge and make CI a required check on `main` before stage 8 can pass.
- `epics.md` stories need an explicit `sources` list. A `bmad-create-epics-and-stories` override is needed before phase 1.

## Compliance

No change to S-1 to S-13. The orchestrator reads `docs/source-register.md` from `origin/main` and never writes it. A register edit on a story branch is reverted as bookkeeping, so only a learn PR can change it, and approval stays with the owner.
