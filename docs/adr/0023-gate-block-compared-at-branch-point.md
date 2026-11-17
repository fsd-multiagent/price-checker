# 0023. Compare the gate block with the story's branch point on main, not with today's main

- **Status:** Accepted
- **Date:** 2026-11-18
- **Deciders:** Marghioala (Developer), Loredan (Architect); hotfix under the owner's standing instruction to fix what hurts the run, 2026-11-18
- **Stage / story:** orchestrator hotfix during phase 1
- **Rules touched:** T-8, D-2; refines ADR 0004

## Context

ADR 0004 failed the `tests` gate when the worktree's `sdlc-commands` block differed from `origin/main`. When the owner changed the block on `main` (PRs #12 and #17), every story that had branched earlier failed its gate with "sdlc-commands differ from origin/main". The stories had not changed anything: story branches keep `AGENTS.md` as it was when they branched (AD-21).

In run `20261117-174705-full`, stories 1-3 and 2-2 lost two rounds each this way, and 1-3 reached its last round.

## Options considered

| Option | Pros | Cons |
|---|---|---|
| A. Compare with the block at the branch point (`git merge-base HEAD origin/main`), and run the commands from there | Fails only when the story itself changed the block. The commands are trusted, because the branch point is on `main`, and they match the code the story was built on | A story that branched before a new gate command is checked with the older commands; the merge rebases it, and CI runs on the PR |
| B. Compare with the branch point, but run today's `main` commands | The newest gate everywhere | Fails on branches that predate the code a new command needs, for example `web/` |
| C. Rebase the story before its gate | Newest code and gate | Changes the story under the agent mid-build; conflicts burn rounds |

## Decision

Option A. If the merge-base cannot be found, the old comparison with `origin/main` applies.

## Consequences

- Changing the gate block on `main` no longer fails stories already in progress.
- A new gate command applies to stories that branch after it lands.

## Compliance

No change to S-1 to S-13. Agents still cannot change their own gate.
