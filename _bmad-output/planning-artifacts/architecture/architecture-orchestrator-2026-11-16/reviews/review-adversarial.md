# Adversarial review: Architecture Spine for Zamolxe (Orchestrator)

- **Target:** `../ARCHITECTURE-SPINE.md` (status: draft, 2026-11-16)
- **Lens:** adversarial. Each hole is a pair of units, one level below the spine, that both obey every AD as written but still build incompatibly.
- **Context read:** `docs/automation-plan.md` §2, §4, §5; `docs/standards/review-findings.md`; `.claude/skills/bmad-build-auto/` (step-01, step-03, step-04, workflow.md); `.gitignore`.

## Verdict

**Not buildable as is.** The functional-core layout (AD-1, AD-2, AD-4) holds up. The weak spots are where the spine meets git and the agents that commit their own work. The spine treats a worktree as owned by one step, an agent as something that only writes files, and "merged to main" as a single obvious fact. None of the three is true. Two holes are critical: either one loses committed work or stalls every parallel story. Both can be closed by tightening rules, without redesigning anything.

| # | Severity | Pair | Short name |
|---|---|---|---|
| H1 | critical | `app/` resume (AD-6) vs parallel steps sharing one worktree (AD-7 `parallel`) | Reset of one step destroys a sibling's commits |
| H2 | critical | story scheduler (AD-15) vs `bmad-build-auto` and Aglaia (Knowledge Keeper) bookkeeping writes | Shared bookkeeping files conflict on every story branch |
| H3 | high | `core/stories.py` readiness (AD-15.1) vs `git` and `gh` adapters (AD-12) | "Merged to main" has three meanings |
| H4 | high | `core/state.py` status (AD-5) vs `bmad-build-auto` HALT status and `sprint-status.yaml` | Several owners of story status; the agent's `blocked` is lost |
| H5 | high | `core/gates.py` (AD-10) vs `adapters/claude.py` and prompts (AD-8, AD-9) | Verdict delivery and verdict path collide |
| H6 | high | AD-11 timeout handling vs AD-6 crash handling vs AD-12 rebase and commit | The same partial work is committed in one case and destroyed in another; git ops have no recovery |
| H7 | medium | scheduler `on_fail` (AD-11) vs the closed transition set (AD-5) | Routing back to an earlier stage is not a legal transition |
| H8 | medium | two `sdlc` processes (AD-3) vs branch and PR keys (AD-12, AD-13) | Concurrent runs share `story/<key>` and reuse each other's PRs |
| H9 | medium | run budget (AD-11) vs `parallel` and `max_parallel` | Run budget is checked per start, not reserved |
| H10 | medium | escalation (AD-14) vs readiness (AD-15) vs CLI exit codes | Dependents of an escalated story wait forever; the run never terminates |
| H11 | low | register parser (AD-15.4) vs the checkout it reads | Approval visibility depends on which checkout is read |
| H12 | low | AD-9 `.sdlc/` gitignored vs the repo's `.gitignore` and build-auto's clean-tree check | Ignore rules are assumed, not established |

---

## H1 (critical): a reset of one step destroys a sibling's commits

**Pair:** `app/loop.py` resume logic (AD-6) and parallel steps that share a worktree (AD-7 `parallel: true`, plan §2 stage 3, plan §4.3 step 3).

**How both obey the ADs.** Stage 3 runs Safta (UX Designer) and Veronel (Security & Compliance) in parallel on the planning branch `sdlc/<run-id>`. That branch has one worktree, and AD-12 does not give each step its own. Per AD-6, each step records `base_sha` as "the HEAD of its worktree" before it starts, so both record the same SHA `S0`. Safta finishes. Per AD-12, the orchestrator commits her leftovers as `S1`, and her step goes to `passed`. Then the process is killed while Veronel is still `running`. On load, AD-6 resets "its worktree" to `base_sha = S0`. Safta's committed `DESIGN.md` and `EXPERIENCE.md` are gone, but her state still says `passed`, so stage 3's gate checks files that no longer exist, or worse, the next stage reads stale input.

**Same defect in other places:**
- Stage 7 step 3: Filofteia (Code Reviewer) and Veronel (Security & Compliance) run in parallel in the story worktree. They are read-only, but `bmad-code-review` has write tools and writes a verdict file (review-findings.md). A reset with `git clean` removes the sibling's `.sdlc/verdicts/` file if the clean includes ignored files (`-x`), and AD-6 does not say which clean is used.
- The `--from <stage>` restart and `sdlc resume` after an escalation (AD-5 `escalated → pending`) do not say whether the worktree is reset, or to which SHA.
- `base_sha` is recorded at step start, but an agent that commits on its own (`bmad-build-auto` commits during step-04) moves HEAD. That is fine for a single writer. With two writers in one worktree, "HEAD at start" is not a property of either step.

**Proposed rule (tighten AD-6, new AD-16):**
- **AD-16, one writer per worktree.** At any moment, at most one step with write tools runs in a given worktree. Steps marked `parallel` in the same scope must either be read-only (Read, Grep, Glob, with the verdict taken from `result`) or each get their own worktree, which the orchestrator merges at the end of the stage. `pipeline.yaml` validation rejects two writing steps that are parallel in one scope (a load error, AD-7).
- **AD-6, reset target is the scope, not the step.** The state records `scope.head_sha` after every step passes. On resume, an interrupted step's worktree is reset to the last `head_sha` of its scope, never to an SHA from before a sibling's passed step. Reset is `git reset --hard <sha>` plus `git clean -fd` (no `-x`), so `.sdlc/` survives. Verdicts already moved to the run dir are never lost.

## H2 (critical): shared bookkeeping files conflict on every story branch

**Pair:** the story scheduler (AD-15.3, `touches` vs `serial_paths`) and the agents that update project-wide bookkeeping as a side effect.

**How both obey the ADs.** Plan §4.1 records that `bmad-build-auto` "commits its own work and sets the story to `done` in `sprint-status.yaml`". It also writes `spec-<slug>.md` and may add to deferred-work under `_bmad-output/implementation-artifacts/`. None of these files appears in a story's `touches`, because `touches` comes from `epics.md` and describes product code. So AD-15.3 lets three stories run at once (`max_parallel: 3`). Each branch edits the same lines of `sprint-status.yaml`. The first PR merges. Each later PR now needs a rebase (AD-15) that conflicts, which costs a Developer fix round to resolve a YAML line the Developer never meant to own, and escalates on a second failure. With three parallel stories, two out of three conflict every time.

The same thing happens with Aglaia (Knowledge Keeper) at stage 9 "after each story". She edits `docs/adr/README.md`, the `CLAUDE.md`/`AGENTS.md` files and the registers. The spine does not say which branch she works on. She cannot commit to `main` (AD-12). If she commits on the story branch, those files join the conflict set. If she gets her own branch and PR, every stage 9 merge moves `main` again and triggers more rebases.

**Proposed rule (new AD-17, extend AD-15):**
- **AD-17, bookkeeping files have one owner.** `pipeline.yaml` declares `bookkeeping_paths` (at least `_bmad-output/implementation-artifacts/sprint-status.yaml` and the deferred-work file). The orchestrator owns them. Story branches must not change them: after each step, the `git` adapter restores them to the base version before committing, and logs the agent's attempted change as an event. The orchestrator derives `sprint-status.yaml` from `state.json` and writes it once, in the stage 9 change for that story.
- Stage 9 is serial across the run (`parallel: false`, a global `serial_paths` entry `docs/**`). It runs on its own branch `learn/<story-key>`, which starts from the merge commit of the story's PR.
- AD-15.3 is computed against `touches ∪ bookkeeping_paths ∪ the files the story's diff actually changed`, not only the declared `touches`. When the actual diff leaves the declared `touches`, that is logged as a `major` finding.

## H3 (high): "merged to main" has three meanings

**Pair:** `core/stories.py` readiness rule 1 ("every story in `depends_on` is merged to `main`") and the `git`/`gh` adapters.

**How both obey the ADs.** AD-12 sends integration only through a PR with auto-merge, which GitHub carries out asynchronously after CI. One reasonable implementation checks local `main` with `git merge-base --is-ancestor`. But nothing ever updates local `main`: the orchestrator never pushes to it, and the spine never fetches. Under that reading, a dependency never counts as merged and the dependent story never starts. A second implementation asks `gh pr view --json state` and sees `MERGED`, then creates the dependent worktree from local `main` (AD-12 says the orchestrator creates worktrees but not where they start). The dependent story is then built without its dependency's code, and it passes or fails for the wrong reason. A third reading counts `auto-merge enabled` as merged. That is also when the story's stage 8 goes to `passed` in some implementations.

Related: the planning branch `sdlc/<run-id>` "is merged through a PR once stage 5 passes" (plan §4.1). The spine does not say that stage 6 and stage 7 must wait for that PR to be **merged**. If they do not wait, story worktrees start from a `main` without `epics.md`, and `bmad-build-auto` HALTs with `no epic spec found` or `story id not found`.

**Proposed rule (tighten AD-15.1, AD-12):**
- "Merged" means exactly this: `gh` reports the PR keyed by the branch (AD-13) as `MERGED`, with `mergeCommit.oid = M`. The app turns this into an event `Merged(scope, M)`, polled on every tick. A story's stage 8 is `passed` only on that event, not when auto-merge is enabled.
- Every worktree starts from `origin/main` after a `git fetch`, and the start SHA is recorded. A dependent story starts from a commit that contains every `M` of its `depends_on`. Local `main` is never used for a decision.
- Stages 6 and 7 of a full run depend on `Merged(stage:5-…)` for the planning PR.

## H4 (high): several owners of story status; the agent's `blocked` is lost

**Pair:** `core/state.py` with the closed status set (AD-5), and `bmad-build-auto`'s own status machine.

**How both obey the ADs.** `bmad-build-auto` keeps its own story status in three places: the spec frontmatter (`draft | ready-for-dev | in-progress | in-review | done | blocked`), `sprint-status.yaml`, and on an early HALT, a `bmad-build-auto-result-*.md` file. When it HALTs with `blocked` (for example `intent gap`, `no stories.yaml found`, or `finalization left repository dirty`), the `claude -p` process still ends normally, so the JSON has `subtype: success` and `is_error: false`. AD-8's `StepResult` has no field for the skill's own status. AD-11 counts only a timeout or `is_error` as a failed round. So the core marks the Developer step `passed`, QA runs against an empty diff, and the reviewers pass it.

Second conflict: an agent's "blocking ambiguity" is a reason to escalate (plan §4.4). But AD-5 defines `blocked` as "waiting on a human input from plan §6" that consumes no round and is rechecked on every tick. If an implementer maps the skill's `blocked` onto AD-5's `blocked`, the story waits forever, because no tick check can clear an intent gap.

Third conflict: on a fix round or a resume, `bmad-build-auto` finds its existing spec by id and routes on that spec's `status` (step-01). After AD-6 resets to `base_sha` the spec file goes back to its earlier status. But if the spec says `done` (from a previous round on the same branch), the skill runs a "follow-up pass" instead of fixing the reviewers' blockers. Neither the spine nor the prompt contract says how reviewer findings reach the Developer in a fix round.

**Proposed rule (new AD-18, tighten AD-5 and AD-8):**
- **AD-18, every agent step has a declared result contract.** Each step kind in `pipeline.yaml` names an `outcome_source`: `json_result` (default), `spec_frontmatter:<path>` or `halt_file:<glob>`. The `claude` adapter reads it and puts `outcome ∈ {done, blocked, error}` and `reason` into the `StepResult`. A step passes only on `done`.
- `outcome: blocked` is a failed round with the reason recorded. If the reason is in a declared list of human-input reasons (intent gap, ambiguity), the scope goes straight to `escalated` (AD-14), never to AD-5 `blocked`. AD-5 `blocked` is reserved for conditions the core can check on each tick (register approval, a dependency being merged).
- `state.json` is the only owner of story status. Spec frontmatter and `sprint-status.yaml` are agent-internal or derived (H2). A fix round's prompt names the verdict files of the failed round and gives the spec path explicitly, so the skill routes to `in-progress` and not to a follow-up pass.

## H5 (high): verdict delivery and verdict path collide

**Pair:** `core/gates.py` and judge steps (AD-10), and the prompt and path rules (AD-9, AD-8).

**How both obey the ADs.**
- AD-9 says the verdict path given to an agent is `<worktree>/.sdlc/verdicts/<gate>.json`, and the orchestrator moves the file into the run dir. AD-10 says a judge has only Read, Grep and Glob. A judge cannot write that file. Review-findings.md says read-only reviewers return the verdict as the last fenced `json` block in `result`. An implementer who follows AD-9 waits for a file that never appears, and every judge gate fails (AD-10: missing verdict means fail). An implementer who follows review-findings.md parses `result` and never looks at the file. In that case `bmad-code-review` and Aglaia (Knowledge Keeper), who write files, are never read.
- The path is keyed by `<gate>`. Stage 7's "no blocker findings" gate is fed by **two** parallel reviewers, Filofteia (Code Reviewer) and Veronel (Security & Compliance), plus `bmad-code-review`. All of them get the same `.sdlc/verdicts/<gate>.json`, and the last one to write wins. The run-dir path `verdicts/<scope>/r<round>/<gate>.json` has the same collision.
- Review-findings.md says the orchestrator "also sets `SDLC_VERDICT_PATH`". AD-8 does not include that variable in the one invocation builder. The two docs disagree, and an implementer can follow either.

**Proposed rule (tighten AD-9, AD-10):**
- A gate consumes a **list** of verdicts, one per contributing step. The path is `verdicts/<scope>/r<round>/<gate>/<step>.json` in the run dir and `.sdlc/verdicts/<step>.json` in the worktree. The gate fails if any verdict is missing or invalid, or has a blocker.
- Each step declares `verdict_source: result_block | file`. Read-only steps must use `result_block`, and pipeline validation rejects `file` for a step without write tools. The `claude` adapter extracts the block, and the `fs` adapter moves the file. `core/gates.py` sees only parsed `Verdict` objects.
- Settle `SDLC_VERDICT_PATH` either way: add it to AD-8 or delete it from review-findings.md. The spine should pick one, and an ADR or a change to review-findings.md should record it.

## H6 (high): partial work is committed in one case and destroyed in another; git operations have no recovery

**Pair:** AD-11 (a timeout is a failed round), AD-6 (an interrupted step is reset), and AD-12 (commit leftovers after each step; the orchestrator rebases).

**How both obey the ADs.**
- **Timeout vs crash.** A step that times out is killed (AD-3) and counted as failed (AD-11). AD-12 says "after each step the orchestrator commits anything left uncommitted", so the half-written work is committed and the fix round starts on top of it. If the same step is killed because the orchestrator crashed, AD-6 resets it to `base_sha` and the half-written work is thrown away. The same partial state is kept in one case and discarded in the other, so a fix round behaves differently depending on why the process died.
- **Commit vs persist.** The orchestrator's "commit leftovers" happens after the step and before `store` persists `passed`. A crash between the two leaves the step `running` with a new commit. Resume resets that commit away and reruns the whole step, so a 22-minute architecture step is paid for twice. That is safe, but the spine does not say it is intended.
- **Rebase.** A rebase (AD-15) rewrites the story branch, and it is not a step, so AD-6 records no `base_sha` for it. If the process crashes during a rebase, the worktree is left in `rebase-merge` state. On the next load, the next step's reset to an earlier `base_sha` either fails because a rebase is in progress, or "succeeds" onto a pre-rebase SHA and quietly undoes the rebase. After a successful rebase, every `base_sha` recorded earlier is an orphan. AD-12 only forbids pushes to `main`. It does not say the rebased branch is pushed with `--force-with-lease`, and a plain push is rejected.

**Proposed rule (tighten AD-6, AD-11, AD-12):**
- On a failed round (timeout, `is_error`, `outcome != done`), the worktree is reset to the step's `base_sha`. The discarded diff is saved as `steps/<seq>-…-discarded.patch` in the run dir, and the next fix prompt names that file. Leftovers are committed only for a step that ends `done`.
- Orchestrator git operations (commit leftovers, rebase, push) are modelled as `script` steps, with the same rules as other steps: a `base_sha` before, a `head_sha` after, and the state persisted. On load, the git adapter first runs `rebase --abort` or `merge --abort` if needed, then resets to the last `head_sha` of the scope (H1).
- Story branches are pushed with `--force-with-lease=<branch>:<expected sha>`. The "never `main`" check stays.

## H7 (medium): routing back to an earlier stage is not a legal transition

**Pair:** scheduler `on_fail` routing (AD-11) and the transition set (AD-5).

**How both obey the ADs.** Plan §2 sends some failures back to an earlier stage, for example readiness at stage 5 back to the PM. The stage table also allows `on_fail` to name any stage. AD-5's diagram has no `passed → pending` edge. To send stage 5 back to stage 4 (or a story back to its Developer step after QA `passed`), either the scheduler invents a transition, which AD-5 forbids, or it creates new step nodes for every round, which AD-6 and AD-11 do not describe. Rounds are counted per `(scope, gate)`. When stage 5 sends work back to the Architect, the spine does not say whether that is a round of stage 4's gate or of stage 5's.

**Proposed rule (tighten AD-5, AD-11):** Add one edge: `passed → pending`, with the reason `invalidated_by(<scope>, <gate>, <round>)`. The scheduler applies it to the `on_fail` target and to every node after it in the same scope chain. Step nodes are per round (`<scope>/<step>/r<n>`), so history is never overwritten. The round is charged to the gate that **failed**, never to the target. `on_fail` may only name a step or stage earlier in the same scope chain. A target outside it is a load error.

## H8 (medium): concurrent runs share `story/<key>` and reuse each other's PRs

**Pair:** AD-3 (one process, one event loop) and AD-12/AD-13 keys.

**How both obey the ADs.** AD-3 is about a single process. Plan §4.5 starts a single-story run from a labelled issue, and that run can start while a full run is going. Each is its own process, so each follows AD-3 on its own. Both pick branch `story/<story-key>` (no run id in it). AD-13 keys PRs by branch name, so the second run "reuses" the first run's PR and enables auto-merge on someone else's work. `git worktree add` for an existing branch fails, or both processes reset the same worktree.

**Proposed rule (extend AD-3, AD-12):** A repo-wide lock file `_bmad-output/sdlc/lock` (pid, run id, and the story keys held) is taken with `O_EXCL` at start and released at exit. A stale lock is detected by pid. A second run may start only if its story keys are disjoint from the ones held, and otherwise it exits 2 with a `blocked` reason. The worktree path includes the story key, and AD-13 lookups also check the issue marker's run id before reusing anything.

## H9 (medium): the run budget is checked per start, not reserved

**Pair:** run budget (AD-11) and `parallel` / `max_parallel`.

**How both obey the ADs.** The check "before any step starts" happens in one tick, and costs are known only when a step finishes. With three stories each starting a Developer step with `--max-budget-usd 5` and $2 of budget left, all three pass the check, and the run can overspend by up to $13. "A step that is already running is allowed to finish" makes that legal.

**Proposed rule:** A step may start only if `spent + Σ(caps of running steps) + its own cap ≤ run_budget_usd`. When it does not fit, the step waits (no escalation) until running steps finish. Escalate only when nothing is running and the next step still does not fit.

## H10 (medium): dependents of an escalated story wait forever

**Pair:** AD-14 ("stories that do not depend on the escalated scope keep running") and AD-15 readiness, plus the CLI exit codes.

**How both obey the ADs.** A dependent of an escalated story is never ready (rule 1). It is not `blocked`, which AD-5 reserves for human §6 input, so it stays `pending`. The loop cannot tell "nothing can ever run" from "wait for the next tick", and `sdlc run` never exits with 2.

**Proposed rule:** Define the run's stopping condition in the core. When no node is `running` and no `pending` node can become ready without a human action, the run stops with exit 2. Dependents of an escalated scope become `blocked` with the reason `upstream_escalated(<scope>)`. That widens AD-5 `blocked` to cover anything that waits on a human, which `sdlc resume` clears.

## H11 (low): approval visibility depends on which checkout is read

**Pair:** register parser (AD-15.4) and the `fs` adapter.

The owner approves a retailer by changing `docs/source-register.md` on `main`. The spine does not say whether the parser reads the main checkout (local, maybe stale), the story worktree (fixed at the branch's start), or `origin/main`. Two reasonable implementations disagree about when a `blocked` story becomes ready. The guard hook reads the register in the agent's `cwd`, which is the worktree, so a story can be "ready" for the orchestrator while every fetch is still denied inside the worktree.

**Proposed rule:** The parser reads `origin/main:docs/source-register.md` (`git show`) after the tick's fetch. A story whose `sources` became Approved after its worktree was created is rebased onto `origin/main` before its Developer step starts.

## H12 (low): ignore rules are assumed, not established

**Pair:** AD-9 (`.sdlc/` gitignored, `TMPDIR=<worktree>/.sdlc/tmp`) and the run dir convention ("Gitignored"), against the actual `.gitignore` and `bmad-build-auto`'s clean-tree check (step-01 item 3: "HALT on a dirty tree").

The current `.gitignore` has no entry for `.sdlc/` or `_bmad-output/sdlc/`. If the orchestrator writes `.sdlc/` before the ignore rule is on the worktree's base commit, `bmad-build-auto` sees untracked files and HALTs with `blocked` (which H4 then silently passes). The post-step "commit anything left uncommitted" would commit the temp files and verdicts.

**Proposed rule:** At `sdlc run` start, the app checks that `.sdlc/` and `_bmad-output/sdlc/` are ignored on the base commit (`git check-ignore`) and stops with exit 1 if not. Commit-leftovers uses an explicit pathspec that excludes `.sdlc/`.

---

## What holds

- AD-1, AD-2 and AD-4 together give a clean, testable core. The dry-run-as-real-loop rule (AD-2) is the right call. It is the right place to add fixtures for H1, H4 and H6, such as a canned `bmad-build-auto` result that HALTs `blocked` and a killed parallel step.
- AD-13's idempotency keys are sound once run ids are part of them (H8).
- AD-14 scoping is right. It only needs the stopping condition (H10).

## Suggested ADR follow-up (D-2)

H1 and H6 together change AD-6, which ADR 0002 adopted. They need an amendment to `docs/adr/0002-orchestrator-run-state-and-resume.md`, not only an edit to the spine. H2 (who owns the bookkeeping files) and H3 (what "merged" means) are new architecture decisions and each needs its own ADR from `docs/adr/0000-template.md`, listed in `docs/adr/README.md`.
