# 0022. Agents propose factory changes and build them from owner issues; the owner only reviews and merges

- **Status:** Accepted
- **Date:** 2026-11-18
- **Deciders:** Loredan (Architect); accepted by Adrian (owner), 2026-11-18
- **Stage / story:** orchestrator update during phase 1
- **Rules touched:** D-2, T-8; orchestrator spine AD-12, AD-22, AD-26, AD-27; extends ADR 0004 and ADR 0021

## Context

The owner wants the agent factory to run on its own. Building the app is automated. Changing the factory itself (gate commands, prompts, pipeline tuning, orchestrator code) has so far been done by hand in an interactive session:

- An agent's edit to a protected file was silently reverted.
- There was no way to file factory work as a story.

The repo is public, so a GitHub issue or label from a stranger must never steer agents. The orchestrator's `gh` acts as the owner, so GitHub does not notify the owner of the orchestrator's PRs. Sources: the reviews in `reviews/review-update-factory-*.md`.

## Options considered

| Option | Pros | Cons |
|---|---|---|
| A. Three path tiers (owner-only, protected, review). Protected edits become owner-review proposals. Owner-authorised issues start runs, and `factory` stories may change protected paths behind owner review | Agents do the factory work; the owner only merges; controls stay owner-approved | More orchestrator code; the owner must review PRs |
| B. Keep reverting protected edits; the owner turns recommendations into PRs by hand | Simple | Owner chores; the factory does not run on its own |
| C. Let agents change the factory freely, guarded only by CI | Fully automatic | Agents could weaken their own gates and guardrails |

## Decision

Option A, as AD-26 and AD-27 of the orchestrator spine describe:

- `owner_only_paths` (`.claude/`, `CODEOWNERS`, the source register) are never changed by agents: `Edit` of them is `ask`, which is denied headless; the guard blocks Bash; they are reverted before every commit; and a reviewer that changes one fails. *Amended 2026-11-18, before merge: an earlier draft also copied them from `origin/main` before every agent step. The build review showed that this dirties worktrees under running agents and scripts, and hides reviewer edits, so it was dropped (spine AD-26).*
- `protected_paths` edits become `owner-review` proposals (PRs, or issues when they touch workflows).
- `review_paths` (workflows) send a story's PR to owner review.
- Issue runs require owner authorship, owner edits and an owner-applied `factory` label, all re-checked on resume. Their text enters prompts only as untrusted data.
- Owner-review waits end the run cleanly, and a later command resumes it.
- One run at a time, with `fcntl.flock`, and a queue drained by `exec` so that new code runs.

## Consequences

- Factory work reaches the owner as PRs. The owner's job becomes: review and merge `owner-review` PRs, approve retailers, apply infrastructure, answer escalations.
- Stories touching workflows (planned CI and deploy stories) wait for the owner's merge.
- Owner checklist, before setting up Azure: the OIDC federated credential's subject must be an `environment` with required reviewers, never `pull_request`. Otherwise agent-written workflow changes on a PR branch could reach Azure before review.
- GitHub notifications do not cover the orchestrator's own PRs. The owner watches `sdlc status`, the desktop notifier and the saved search `is:open label:owner-review`. A bot identity is deferred.

## Compliance

Strengthens S-1 (the register stays owner-only), S-10 and S-13 (no CI credentials before review), and the guardrails of plan §5.
