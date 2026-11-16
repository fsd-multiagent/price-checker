# Review: rubric and reconcile — Zamolxe (Orchestrator) spine

- **Target:** `ARCHITECTURE-SPINE.md` (draft, 2026-11-16)
- **Inputs checked:** `docs/automation-plan.md` §1, §2, §4, §5, §6, §7, §8; `docs/standards/review-findings.md`; `docs/bmad-reference.md` §4; `docs/standards/testing.md` T-8; `docs/adr/README.md`
- **Reviewer:** independent rubric and reconcile pass (read-only, no project file edited)

## Verdict

**Not ready to build as written.** The paradigm and the core ADs (AD-1 to AD-4, AD-8, AD-12, AD-13) are sound and enforceable. But the spine leaves several real divergence points open between the modules that will be built separately (scheduler, gates, git adapter, prompts, store). The biggest are: how the planning branch reaches `main` before stories start; who owns `sprint-status.yaml` under parallel stories; where stage 9 writes; how verdicts are delivered by read-only reviewers versus writing reviewers; what resume does to the round counter; and running two `sdlc` processes at once. The input docs also contain about a dozen quiet requirements the spine drops, mostly gate semantics (CONCERNS, "no update needed", agent-reported ambiguity) and the per-stage tags.

ADR hygiene (D-2): ADR 0001 and 0002 exist and are indexed. OK.

---

## Part 1 — Rubric

### 1.1 Divergence points fixed (good)

| Point | Where fixed | Assessment |
| --- | --- | --- |
| Pure core vs I/O | AD-1 | Enforceable with an import test (see 1.3) |
| Dry-run path | AD-2 | Good: one loop, fake adapters |
| Concurrency model | AD-3 | Good |
| State writer, atomicity | AD-4 | Good |
| Status vocabulary | AD-5 | Good, but the transitions are incomplete (F-6) |
| Agent invocation | AD-8 | Good, close to plan §4.1 |
| Gate output shape | AD-10 | Good, but delivery is incomplete (F-4) |
| Git and GitHub ownership | AD-12 | Good |
| Idempotent external effects | AD-13 | Good |
| Escalation scope | AD-14 | Good |
| Scope IDs, run dir, timestamps, exit codes | Conventions | Good |

### 1.2 Divergence points missed

| # | Missing decision | Modules that would diverge | Severity |
| --- | --- | --- | --- |
| M-1 | **Planning branch to `main`.** Plan §4.1: the `sdlc/<run-id>` branch is merged through a PR once stage 5 passes. The spine names the branch but not when the PR is opened, whether stage 6/7 waits for the merge, or what base story worktrees branch from (`main` without `epics.md`, or `sdlc/<run-id>`). AD-15 rule 1 ("merged to `main`") only works if planning has merged | scheduler, git adapter, gh adapter | critical |
| M-2 | **Where planning stages run.** AD-9 says agents write only in their own worktree, and the run dir lives in the main checkout. It does not say whether stages 1–5 get one worktree for `sdlc/<run-id>` or use the main checkout. Stage 3 runs UX and Security in parallel in the same tree; AD-6's reset to `base_sha` of one interrupted step would wipe the other parallel step's work | git adapter, app loop, resume | high |
| M-3 | **`sprint-status.yaml` ownership.** `bmad-build-auto` sets its story to `done` in `sprint-status.yaml` and commits (plan §4.1, ref §4). With `max_parallel` 3, every story PR edits the same file, so every rebase after the first conflicts. The spine also does not say whether `state.json` or `sprint-status.yaml` is the truth for "story merged" (AD-15 rule 1) | stories, git adapter, rebase handling | high |
| M-4 | **Stage 9 placement.** Aglaia (Knowledge Keeper) runs "after each story" and edits docs and registers. It cannot push to `main` (AD-12, G2). The spine does not say which branch or worktree it uses, whether it is a PR of its own or a step inside the story PR before merge, or how parallel stage 9 runs avoid colliding on the ADR index and registers. Also the epic retrospective trigger ("after each epic") has no detection rule | scheduler, git adapter, gates | high |
| M-5 | **Concurrent runs.** Triggers (plan §4.5) can start a fix run or a story run while a full run is active. AD-3 is one process per run, but two processes share the main checkout, worktree names (`story/<key>`), `sprint-status.yaml`, `max_parallel` and the spend cap. No lock or queue is decided | cli, app, git adapter | high |
| M-6 | **Story source for story and fix runs.** `sdlc story <issue>` and `sdlc fix <issue>` take an issue, but AD-15 has one parser that reads `epics.md`, and `bmad-build-auto` needs a story key and an `epics.md` path in the prompt. Plan §4.5 says a fix run runs "for one generated story". Nobody is assigned to turn an issue into a story key and an entry the Developer can read | cli, stories, prompts | high |
| M-7 | **Waiting on external state.** Stage 8 waits for CI (`gh pr checks`) and auto-merge; stage 6 deployment waits for the human `terraform apply`; dependent stories wait for a merge. The spine has no polling rule (interval, timeout, which status the scope holds while waiting: `running` or `blocked`), and no rule for how a human `terraform apply` is detected | scheduler, gh adapter, status CLI | medium |
| M-8 | **Stage identity without hard-coding.** AD-7 forbids stage names in Python, but story scheduling (AD-15), `--from <stage>`, and story/fix runs ("stages 7 → 9") need to know which stage is per-story and where a story run starts. Needs a declared stage attribute in YAML (e.g. `per_story: true`, `entry: story`) | pipeline model, scheduler, cli | medium |
| M-9 | **Gate catalogue.** AD-7 says gate kinds are looked up by registry name, but the spine never lists the gate kinds and their pass rules. Each gate in plan §4.2 has its own semantics (F-3); left to the code, the YAML author and the gate implementer will disagree | gates, pipeline.yaml, prompts | high |

### 1.3 Are the AD rules enforceable and do they prevent their stated divergence?

| AD | Enforceable? | Notes |
| --- | --- | --- |
| AD-1 | Partly | Import bans are testable (an `import-linter` contract or a test that walks `core/` imports). "`pathlib` I/O" is not checkable by imports; say "`core/` may import only stdlib `dataclasses`, `typing`, `enum`, `datetime` types, and pydantic", and add the test as part of the rule |
| AD-2 | Yes | Add "the fakes implement the same `ports.py` Protocols, checked by a type check" |
| AD-5 | Partly | The diagram has no `running → blocked` (e.g. `bmad-build-auto` returns `blocked`), no `running → escalated` (agent-reported ambiguity, plan §4.4), and does not show how `on_fail` sends an *earlier* step back to `pending` (stage 4 security fail goes back to Architect). `escalated → pending` conflicts with AD-6 (F-6) |
| AD-6 | Partly | Correct for one step per worktree. Breaks with parallel steps in one worktree (M-2). Says nothing about an interrupted external effect (PR opened, not recorded); AD-13 covers that only if lookups happen on every resume — state it |
| AD-9 | No as written | A read-only reviewer cannot write `<worktree>/.sdlc/verdicts/<gate>.json` (F-4) |
| AD-10 | Partly | "Fails iff a blocker" — does the orchestrator recompute this from `findings`, or trust the `verdict` field? Does it apply the rule "a blocker without a rule or AC ID is downgraded to `major`"? Two gate implementations can differ. "Never runs as the agent that produced the work" is not checkable as worded; say "the judge's `--agent`/prompt differs from the producing step's and the pipeline loader rejects a judge whose role equals the producer's" |
| AD-11 | Yes | But rounds are counted per `(scope, gate)` while `on_fail` can loop across steps; state whether the rebase fix round (AD-15) counts toward `max_rounds` |
| AD-12 | Yes | `refuses any push whose target ref is main` — also refuse `gh pr merge` without `--auto` (G2 parity) |
| AD-15 | Partly | Rule 3 is ambiguous: `serial_paths` is a run-level list, not per story. Intended rule: "a story whose `touches` intersect `serial_paths` does not start while another running story's `touches` intersect the same path". Default `serial_paths` must include `api/openapi.yaml` (plan §4.3) |

### 1.4 Could anything under Deferred let two units diverge?

| Deferred item | Risk |
| --- | --- |
| Notifier on Linux | None; behind a port |
| How Actions starts `sdlc` | Low for the CLI contract, but M-5 (concurrent runs) and M-6 (issue to story) are hidden here and must not wait for step 0.9 |
| `C-O` standards section | Low; conventions table covers it |
| `sources` in `epics.md` | **Medium.** "A story without `sources` is treated as having none" means an adapter story for an unapproved retailer is *not blocked*, and the Developer will try to fetch it; only G4 stops it, as a denial that burns rounds. Safer default: a story whose `touches` include the adapters path and has no `sources` is `blocked` |
| Exact `pipeline.yaml` field names, prompt texts | **Medium.** The field names are the contract between `core/pipeline.py` and the YAML author; the prompt texts carry the verdict-delivery contract (F-4). At minimum, fix the required prompt slots (input paths, verdict path, story key, `epics.md` path, `-H` skill call) |
| Agent SDK | None; behind a port |

### 1.5 Dimensions at this altitude

| Dimension | Status in spine | Gap |
| --- | --- | --- |
| Where it runs | Decided (diagram: self-hosted runner, Mac or small VM) | Runner prerequisites (`gh auth`, `claude` on PATH, token) not listed |
| Triggers | Deferred to 0.9 | M-5, M-6 should be decided now |
| Auth | Decided (`CLAUDE_CODE_OAUTH_TOKEN`) | Plan allows `ANTHROPIC_API_KEY` too, and the spend cap is on the API key; pick one or allow both. GitHub auth for `gh` not stated |
| Cost | Decided (step cap, stage `budget_usd`, `run_budget_usd`) | Plan §5 says "token and cost cap"; tokens dropped (accept, but say so). Cost total must be in `state.json` so resume keeps it |
| Observability | Decided (events.jsonl, step logs, verdicts, `sdlc status`) | Fine. Add: `status` shows cost so far and permission denials |
| Model choice | Not mentioned | Smoke tests used Sonnet; no `--model` in AD-8 means the runner's default decides cost and behaviour. Add a per-stage `model` field or an open question |
| Timeouts | Field exists | Defaults dropped (F-8) |
| Open questions section | Absent | The spine has no Open Questions section at all |

---

## Part 2 — Reconcile (dropped or contradicted requirements)

| # | Source | Requirement | Spine | Status |
| --- | --- | --- | --- | --- |
| R-1 | Plan §4.1, §7 | Each stage leaves a tag; phase tags `phase-3-planning`, `phase-4-skeleton`, … | AD-12/13 mention tags only as an object kind; no naming or creation point | **Dropped** |
| R-2 | Plan §4.1 | Planning branch merged through a PR once stage 5 passes | Branch named only | **Dropped** (M-1) |
| R-3 | Plan §4.1 | Every stage ends in a commit | AD-12 commits per step | OK (stronger) |
| R-4 | Plan §4.2 | Readiness: PASS passes, **CONCERNS counts as a pass but is logged**, FAIL goes back to the PM | Not mentioned; AD-10 "fails iff blocker" does not map PASS/CONCERNS/FAIL | **Dropped** |
| R-5 | Plan §4.2 | Docs updated: Knowledge Keeper's diff touches the expected files, **or it records "no update needed" with a reason** | Not mentioned | **Dropped** |
| R-6 | Plan §4.2 | Brief judge maps each spec section to the brief | Not mentioned | Dropped (gate catalogue, M-9) |
| R-7 | Plan §4.2 | `bmad-prd` validate mode passes **and** a script checks every FR row has AC | Not mentioned | Dropped (M-9) |
| R-8 | Plan §4.2, T-8 | Tests pass = the test commands listed in root `CLAUDE.md`, run in the story worktree | Not mentioned; no rule for where the `script` gate gets its commands | **Dropped** |
| R-9 | Plan §4.2 | `terraform plan` gate = exit code of `init && validate && plan` | Not mentioned | Dropped (M-9) |
| R-10 | Plan §4.2 | CI green read with `gh pr checks`; then auto-merge | AD-12 auto-merge only; no CI-wait rule | Partly (M-7) |
| R-11 | Plan §8 | Gates must read the `open_questions` and `assumptions` in each skill result | Not mentioned | **Dropped** |
| R-12 | Plan §4.4 | Escalate also when **an agent reports a blocking ambiguity** | AD-11/14 escalate only at `max_rounds` | **Dropped** |
| R-13 | Plan §4.1 | `bmad-build-auto` stops with `blocked` and writes `bmad-build-auto-result-*.md` without a story key | No mapping of that outcome to a status | Dropped |
| R-14 | Plan §2, §4.1 | `max_rounds` default 3, `max_parallel` default 3 | Fields named, no defaults | Dropped |
| R-15 | Plan §4.1 | Timeouts per stage: start with 60 min architecture, 30 min story | `timeout_min` field, no defaults | Dropped |
| R-16 | Plan §4.3 | Lock on `api/openapi.yaml` | Generalised to `serial_paths`, no default value | Partly |
| R-17 | Plan §4.5 | Full run on push changing `docs/initial-spec.md` or `workflow_dispatch`; fix run (stages 7→8→9) on `adapter-broken`; story run (7→9) on `story` label | CLI exists, trigger mapping deferred; "stages 7→9" entry point not expressible (M-8); issue-to-story not decided (M-6) | Partly |
| R-18 | Plan §6, O2 | Exactly three human points: retailer approval, `terraform apply`, escalations | Retailer approval → `blocked` (AD-5, AD-15); escalation → AD-14. **`terraform apply` wait not modelled** | Partly |
| R-19 | Plan §2 stage 6 | Stage 6 runs "alone, or alongside the first stories" | Not decided | Dropped |
| R-20 | Plan §2 stage 9 | Knowledge Keeper after each story; retrospective after each epic | Not mentioned | **Dropped** (M-4) |
| R-21 | review-findings.md | Read-only reviewers return the verdict as the **last fenced `json` block** of the final message; writing reviewers (`bmad-code-review`, Knowledge Keeper) write one file to the path in the prompt | AD-9 gives every agent a file path to write; no parse-last-block rule | **Contradicted** for read-only reviewers |
| R-22 | review-findings.md | Orchestrator also sets `SDLC_VERDICT_PATH` | Not mentioned | Dropped (low; the doc can be changed instead) |
| R-23 | review-findings.md | Blocker without a rule or AC ID is downgraded to `major`; `major` goes to the deferred-work list | Not mentioned | **Dropped** |
| R-24 | Plan §4.1 | Prompt starts with the skill call and `-H`, e.g. `/bmad-prd -H …` | AD-8 renders from template, does not require it | Partly |
| R-25 | Plan §5, §6 | Token and cost cap; API key with spend cap; key or OAuth token | Cost only; OAuth token only | Partly |
| R-26 | Plan §4.4 | You fix the input, then `sdlc resume <id>` | AD-5 `escalated → pending`; AD-6 "rounds already counted stay counted" means the first failure after resume re-escalates immediately | **Contradicted** (F-6) |
| R-27 | bmad-reference §4 | Orchestrator starts `bmad-correct-course` when an escalation calls for re-planning | Not mentioned | Dropped (low; may be left to the human) |
| R-28 | Plan §8 | Each worktree gets its own `TMPDIR` | AD-8 | OK |
| R-29 | Plan §5 | Headless runs never get a blanket permission bypass | Not stated in AD-8 | Dropped (low; add "never pass `--dangerously-skip-permissions` or `--permission-mode bypassPermissions`") |

---

## Findings with fixes

| ID | Severity | Finding | Suggested fix |
| --- | --- | --- | --- |
| F-1 | critical | Planning branch never reaches `main`; stories' base and AD-15 rule 1 undefined (M-1, R-2) | Add **AD-16 Planning integration**: stages 1–5 run in one worktree on `sdlc/<run-id>`; after the stage 5 gate the orchestrator opens the PR (AD-13 key = branch), waits for CI and merge, tags; stages 6–7 start only after merge, and story worktrees branch from `origin/main` at that point |
| F-2 | high | `sprint-status.yaml` written by every parallel story; truth for "merged" unclear (M-3) | Add to AD-15: `state.json` (fed by `gh` merge events) is the truth for scheduling; the orchestrator, not the agent, writes `sprint-status.yaml`, on the story branch just before PR, or treat it as an auto-resolved file on rebase (take `main`, re-apply the one story line). Tell `bmad-build-auto` in the prompt not to rely on it |
| F-3 | high | Gate semantics dropped: CONCERNS = pass + log, "no update needed" with reason, FR script, T-8 commands, terraform exit code, `open_questions` reading, uncited-blocker downgrade (M-9, R-4..R-11, R-23) | Add **AD-17 Gate catalogue**: a table of registry gate kinds (`exists`, `judge`, `readiness`, `fr_has_ac`, `script_exit`, `terraform_plan`, `ci_green`, `docs_updated`), each with its input and its mapping to a `Verdict`; the core normalises every verdict (recompute `verdict` from findings, downgrade uncited blockers, `major` → deferred list); `script_exit` reads commands from a declared source (T-8) |
| F-4 | high | Verdict delivery contradicts review-findings.md: read-only reviewers cannot write a file (R-21) | Tighten **AD-9/AD-10**: two delivery modes declared per step in YAML: `final_message` (parse the last fenced `json` block of `result`) and `file` (`<worktree>/.sdlc/verdicts/<gate>.json`); the orchestrator saves both to the run dir; optionally set `SDLC_VERDICT_PATH` or remove it from the standard |
| F-5 | high | Stage 9 and the epic retrospective have no home; parallel KK runs collide (M-4, R-20) | Add **AD-18 Learn stage**: Knowledge Keeper runs inside the story worktree after review passes and before the PR (one PR per story), or as a serialised `docs/<story-key>` PR; retrospective is triggered when every story of an epic is merged; `docs/adr/README.md` and the registers go into `serial_paths` |
| F-6 | high | Resume vs round counting contradicts plan §4.4; AD-5 lacks `running → blocked/escalated` and on_fail rewinds (R-12, R-13, R-26) | Tighten **AD-5/AD-6/AD-11**: `sdlc resume` resets the round counter of the escalated `(scope, gate)` (history kept in `events.jsonl`); add transitions `running → blocked` (agent reports `blocked`), `running → escalated` (agent reports blocking ambiguity, detected from `open_questions` or a declared result field); `on_fail` sets the target step and every later step of the scope back to `pending` |
| F-7 | high | Concurrent runs and issue-driven runs undefined (M-5, M-6, R-17) | Add **AD-19 Run admission**: one active run per checkout via a lock file in `_bmad-output/sdlc/`; story/fix triggers enqueue into the active run (or wait); `sdlc story/fix <issue>` writes the issue as a story entry (key `N-M-slug`, `depends_on: []`) into a run-local stories file that the one parser reads; declare `entry` and `per_story` attributes on stages in YAML so no stage name is hard-coded (M-8) |
| F-8 | medium | Defaults and human waits missing: `max_rounds`=3, `max_parallel`=3, timeouts 60/30, `serial_paths: [api/openapi.yaml]`, CI polling, `terraform apply` wait, stage 6 alongside stories, model pin, no-bypass flag, tags (R-1, R-14..R-19, R-29, M-7) | Add a **Defaults** table to the spine and an **AD-20 External waits**: CI polled every N s up to a timeout, scope stays `running`; deploy of infra stories `blocked` until a human-set marker (tag or label) exists; per-stage tag `sdlc/<run-id>/stage-<n>` plus phase tags at the plan points; add `model` to AD-7 stage fields; AD-8 forbids bypass flags. Also add an Open Questions section, and change the `sources` deferral default to `blocked` for adapter stories |
