---
title: 'Zamolxe (Orchestrator) core and dry-run'
type: 'feature'
created: '2026-11-16'
status: 'done'
route: 'dispatch'
review_loop_iteration: 0
baseline_commit: '190225404f8f63cb9ee54ed42a8e756b24f5f126'
context:
  - '{project-root}/_bmad-output/planning-artifacts/architecture/architecture-orchestrator-2026-11-16/ARCHITECTURE-SPINE.md'
  - '{project-root}/docs/standards/review-findings.md'
---

<frozen-after-approval reason="human-owned intent — do not modify unless human renegotiates">

## Intent

**Problem:** Step 0.8 of `docs/automation-plan.md` needs Zamolxe (Orchestrator) to exist, and its acceptance check is that `sdlc run --dry-run` works. Nothing exists in `orchestrator/` yet.

**Approach:** Build the pure core, the store, the ports, the fake adapters, the CLI and `sdlc/pipeline.yaml` exactly as the accepted spine says (AD-1 to AD-22). The dry-run then drives the full stage table, parallel stories included, with no real agents, git or GitHub. Real adapters, run admission (lock, queue, issue-started runs) and the notifier are split out to `deferred-work.md`.

## Boundaries & Constraints

**Always:**
- The spine is binding. Cite AD IDs in code docstrings only where a rule is non-obvious.
- `core/` stays pure (AD-1). It is tested without temporary dirs or subprocesses.
- Every status, transition, gate kind and verdict shape follows AD-5, AD-10, AD-17, AD-18 and AD-19.
- Story entries, in `epics.md` and in issue bodies later, are one fenced `yaml` block per story with `key`, `title`, `depends_on`, `touches` and `sources`. A missing `sources` is a parse error (AD-15).
- Python ≥ 3.12, pinned versions from the spine Stack table, `uv.lock` committed.

**Never:**
- No real `claude -p`, `git` mutation or `gh` call in this change. A non-dry `sdlc run` exits 1 with "real adapters not implemented yet (see deferred-work.md)".
- No lock, queue, or `sdlc story` / `sdlc fix` commands (deferred).
- No edits to `.claude/settings*.json`, `.claude/hooks/` or `docs/source-register.md`.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Happy dry-run | `sdlc run --dry-run` with default fixtures | Every stage passes. Three stories, two parallel and one dependent, go through stages 7→9. Exit 0. `status` shows all `passed` | N/A |
| Gate fails then passes | A fixture gives a blocker verdict in round 1 | Round 2 runs with the failed verdict named in the prompt. The scope passes | N/A |
| Gate fails `max_rounds` times | A blocker in every round | That scope is `escalated`; independent stories finish; dependents are `blocked`; exit 2 | A fake escalation issue is recorded once (AD-13) |
| Unapproved source | Story `sources: [cel.ro]`, register row not Approved | The story is `blocked`, consumes no round; the others run; exit 2 | N/A |
| Agent reports `blocked` / `ambiguous` | A fixture result says so | `blocked` → failed round; `ambiguous` → escalates at once (AD-18) | N/A |
| Crash and resume | The process is killed while a step is `running`; then `sdlc resume <id>` | The worktree is reset to the scope `head_sha` (fake git records it); the step reruns in the same round; the end state equals the happy run | N/A |
| Budget cap | `run_budget_usd` below the sum of the step caps | No step starts past the cap; the run escalates (AD-11) | N/A |
| Bad pipeline | Two writers in one parallel group, `verdict_source: file` on a read-only step, or an unknown gate kind | Load error naming the stage and step; exit 1 | Nothing runs |
| Bad story block | `sources` missing, or an unknown `depends_on` key | Parse error naming the story key | The stage 5 gate fails |

</frozen-after-approval>

## Code Map

- `orchestrator/` -- new; nothing to reuse. Layout: spine "Structural Seed".
- `docs/standards/review-findings.md` -- Verdict schema; the model must match it field for field.
- `docs/source-register.md` -- table with `Domain` and `Status` columns; approved means the Status cell starts with `Approved`. This is the same contract as `.claude/hooks/guard.py` G4.
- `docs/bmad-reference.md` §4 -- the real step results (`subtype`, `is_error`, `total_cost_usd`, `num_turns`, `permission_denials`, `result`), and the error subtypes carrying `errors`. Fixtures copy this shape.
- `_bmad/custom/bmad-create-epics-and-stories.toml` -- persistent facts for the PM. Extend it so each story emits the yaml block above.
- `AGENTS.md` "Running and verifying" -- add the orchestrator commands (T-8).

## Tasks & Acceptance

**Execution:**
- [x] `orchestrator/pyproject.toml`, `uv.lock` -- uv project with the `sdlc` console script, the deps and dev deps from the spine, and the ruff and pytest config from its conventions.
- [x] `orchestrator/src/sdlc/core/models.py` -- frozen pydantic models: `Pipeline`, `Stage`, `Step`, `RunState`, scope nodes, `StepResult`, `Verdict`, `Finding`, `Status` (StrEnum), and events.
- [x] `orchestrator/src/sdlc/core/pipeline.py` -- load and validate the YAML, including the load-time checks from AD-7, AD-16 and AD-17.
- [x] `orchestrator/src/sdlc/core/state.py` -- `apply(state, event)` with only the legal AD-5 transitions, round counters, `head_sha`/`base_sha`, budget reservation.
- [x] `orchestrator/src/sdlc/core/gates.py` -- the AD-19 registry; verdict normalisation (AD-10).
- [x] `orchestrator/src/sdlc/core/scheduler.py` -- `decide(pipeline, state, inputs) → actions`: stage chain, `on_fail` rewind, story readiness (AD-15), planning integration (AD-20), the learn queue (AD-21), escalation (AD-14), the exit condition (AD-11).
- [x] `orchestrator/src/sdlc/core/epics_parser.py`, `core/register_parser.py` -- pure parsers over text.
- [x] `orchestrator/src/sdlc/ports.py`, `adapters/fake/` -- Protocols, and fakes driven by `tests/fixtures/dryrun/` (results and verdicts keyed by scope/step/round).
- [x] `orchestrator/src/sdlc/store.py` -- atomic `state.json`, `events.jsonl`.
- [x] `orchestrator/src/sdlc/app/loop.py`, `cli.py` -- the loop, and `run [--from] [--dry-run]`, `status`, `resume`, with the spine's exit codes. Resume aborts a rebase in progress and resets interrupted steps (AD-6).
- [x] `sdlc/pipeline.yaml`, `sdlc/prompts/*.md` -- the stage table from plan §2 with the AD-7 defaults; one prompt template per agent or judge step, naming inputs and the verdict path.
- [x] `orchestrator/tests/` -- unit tests per core module, plus app tests for every matrix row (test names carry the row name, T-1). Use the fake clock (T-5).
- [x] `_bmad/custom/bmad-create-epics-and-stories.toml`, `AGENTS.md` -- the story yaml block with `sources`, and the orchestrator commands.

**Acceptance Criteria:**
- AC-1: Given a fresh clone, when `cd orchestrator && uv run sdlc run --dry-run` runs, then it exits 0 and prints each step it would invoke, including the exact `claude -p` argument list (AD-8).
- AC-2: Given the repo, when `uv run pytest` and `uv run ruff check` run in `orchestrator/`, then both pass.
- AC-3: Given `core/`, when it is scanned for imports, then nothing imports `subprocess`, `asyncio`, `os`, `sdlc.adapters` or `sdlc.app`. A test enforces this.

## Implementation Notes

Choices the spine leaves to the code ("Exact field names beyond AD-7 ... the code owns them"):

- **Extra stage fields:** `needs` (default: the previous stage; it defines the chain for `on_fail` and lets stages 6 and 7 both wait on the planning PR), `branch` (template; default by scope kind), `learn_queue: story | epic` (AD-21 queue; `epic` is the retrospective), `stories_file` (stage 5 parses it in its gate, AD-15), and `gate` as a list of `{kind, steps}` so stage 7 has both `tests` and `review_verdicts`. Steps add `script` (a registered name) and `inputs`.
- **Extra stage:** `planning-pr` between stages 5 and 6 (AD-20). Stage 8 `integrate` has `on_fail: build`, `max_rounds: 2`, which gives the "one Developer fix round, then escalate" of AD-15.
- **Extra port:** `Commands` runs project commands for script steps (`tests`, `terraform_plan`, `fr_ac_check`), next to the six spine ports.
- **Units:** status lives on (scope, stage) units, steps and the run. Story status is derived. Rounds per unit count starts; failure counters are per (scope, gate) as AD-11 says.
- **Rule IDs:** a blocker counts as cited when its `rule` is a standards ID (`C-`, `T-`, `S-`, `I-`, `D-`, including `C-B2`) or contains an `AC-n` ID. Findings the gates themselves produce cite `AD-10`, `AD-15`, `AD-18` or `AD-19` and are not re-normalised.
- **Feedback paths:** failed verdicts and the discarded patch are copied into `<worktree>/.sdlc/feedback/r<n>/` and named in the prompt, so no prompt names `_bmad-output/sdlc/` (AD-9).
- **`run_budget_usd: 100`** in `sdlc/pipeline.yaml` is a placeholder for the owner to confirm (plan §6).
- **Dry-run fixtures:** `orchestrator/tests/fixtures/dryrun/scenario.yaml` plus overlays in `scenarios/`. A hidden `--scenario` flag runs an overlay from the CLI.

## Spec Change Log

## Review Triage Log

Pass 1 (2026-11-16). Reviewers: BH = Blind Hunter, VG = Verification Gap, EC = Edge Case Hunter. Rows marked "checked" were confirmed by reading the source.

| # | Finding | Verdict | Evidence | Route |
|---|---|---|---|---|
| 1 | BH1, EC1: orchestrator blockers cite AD-n; `cites_rule` downgrades them in `_feedback` | high | Checked: `_STANDARD_RULE` accepts only `[CTSID]-`, so fix-round prompts drop gate blockers | patch |
| 2 | BH2, EC6: `FAIL`/`CONCERNS` count as `done` for every result_status step | high | Checked: `outcome.py` maps `_READINESS` to DONE regardless of step | patch |
| 3 | BH3: interrupted steps never charged to `spent` | medium | `_step_interrupted` only releases the reservation; crashes bypass `run_budget_usd` | patch |
| 4 | BH4, EC21: story `sources` normalised differently from the register | medium | Checked: `epics_parser` uses `strip().lower()`, register uses `norm_domain` | patch |
| 5 | BH5: register read from `origin/main` with no fetch on resume | medium | `_inputs` uses `git.show("origin/main")` with no fetch before the first `decide` after resume | patch (fetch at resume); caching deferred |
| 6 | BH6: story-scope file-verdict steps can commit untested edits | medium | `code_review` has `writes: true` and no `--tools` limit; `_commit_if_done` commits after tests | patch |
| 7 | BH7, EC16: resume ignores the original `--pipeline` and `--scenario` | medium | `_resume` always loads `repo/sdlc/pipeline.yaml`; RunState stores no path or digest | patch |
| 8 | BH8: `merge_pr` merges without a head SHA | high | Checked: `gh.merge(scope.pr, scope.head_sha or "")` at loop.py:484 | patch |
| 9 | BH9: result-block prompts promise a save to `${verdict_path}` | medium | The app never writes it for result_block; only the fake does, so dry-run and real runs differ | patch |
| 10 | BH10, VG-o1, EC23: `--from` past breakdown fails in dry-run, leaves a dangling run, can exit 0 doing nothing | high | `_load_stories_for_from` reads the real repo after `RunStarted` is saved | patch |
| 11 | BH11: stage 6 output never integrated | medium | `infra` has no PR, CI or merge steps; work is only tagged | patch (pipeline.yaml steps) |
| 12 | BH12: story prompts lack sources and touches | medium | `PROMPT_FIELDS` has only key and title; Veronel cannot check declared sources | patch |
| 13 | BH13: `serial_paths` uses exact string match | medium | Checked: `set(story.touches) & set(serial_paths)` | patch (prefix match) |
| 14 | BH14: fatal error leaves tasks running and the run `running` | medium | `_loop` has no try/finally | patch |
| 15 | BH15, VG-o2, EC15: run IDs collide within one second | medium | `make_run_id` has 1 s resolution; `create(exist_ok=False)` | patch |
| 16 | VG1: `--from` path untested | high | Regression gap, pre-verified | patch |
| 17 | VG2: integrate failure arms unreachable in tests | high | Fakes always succeed; AD-15 fix round only tested in the core | patch |
| 18 | VG3: bookkeeping restore and commit-only-when-done unasserted | medium | No test reads `restore_paths` or commit calls | patch |
| 19 | VG4: `sprint_status.py` untested | medium | No test imports it | patch |
| 20 | VG5: resume of a BLOCKED run untested | medium | Only ESCALATED and RUNNING resumes are tested | patch |
| 21 | EC2: failed rebase leaves the worktree mid-rebase | medium | `ok=False` path does not abort | patch |
| 22 | EC3: stale file verdict from an earlier round is reused | high | `.sdlc/verdicts/<gate>/<step>.json` not deleted before the step | patch |
| 23 | EC4: TMPDIR directory never created | medium | `.sdlc/tmp` not created before the invocation | patch |
| 24 | EC5: `budget_usd` 0 or negative accepted | medium | Every agent step would start with a $0.00 cap | patch |
| 25 | EC7: `result_status` with `result_block` read the same block | low | Load-time rejection is a direct check | patch |
| 26 | EC8: `gate: []` loads, then IndexError | medium | Reviewer verified | patch |
| 27 | EC9: empty parallel group gives a raw IndexError | low | Reviewer verified; a one-line check | patch |
| 28 | EC10: unknown branch-template placeholder raises mid-run | medium | Reviewer verified | patch |
| 29 | EC11: `max_rounds`/`timeout_min` 0 or negative accepted | medium | Field constraint | patch |
| 30 | EC12: `+main` refspec passes `refuse_main` | high | Checked: `+main` is not in PROTECTED; force push to main allowed | patch |
| 31 | EC13: `--flag=value` form slips past the forbidden-flag tripwire | medium | Checked: exact-match intersection | patch |
| 32 | EC14: empty tools gives empty flag values | low | Require tools at load for agent and judge steps | patch |
| 33 | EC17: resume after budget exhaustion re-exhausts silently | medium | `fits` fails at once; no message | patch |
| 34 | EC18: crash before RunStarted leaves PENDING, then "no progress" on resume | low | Direct transition fix | patch |
| 35 | EC19: CoreError on resume of a passed run reported as internal error | low | One more caught exception | patch |
| 36 | EC20: re-run of epics after invalidation keeps stale stories | medium | StoriesLoaded applied only once | patch |
| 37 | EC22: `sources: null` treated as empty | high | Bypasses the source check (S-1); missing must mean error | patch |
| 38 | EC24: re-passed stage creates the same tag twice | low | Only matters for the real git adapter | defer |
| 39 | EC25: stepless gate double-counts findings | medium | Reviewer traced `gate_steps` | patch |
| 40 | EC26: one task's exception drops sibling events | medium | Finished steps rerun and are paid twice | patch |
| 42 | Self (fix-round report): resume refuses any `pipeline.yaml` change, so a raised budget cannot be resumed | medium | Contradicts plan §4.4 ("fix the input, then resume"); the digest should cover only the stage table | patch |
| 41 | EC27: dry-run resume loses FakeFs writes | maybe-false | Would need a dry-run crash after the breakdown stage; `test_matrix_crash_and_resume` passes | defer (unverified, medium) |

## Verification

**Commands:**
- `cd orchestrator && uv run pytest -q` -- expected: all pass
- `cd orchestrator && uv run ruff check && uv run ruff format --check` -- expected: clean
- `cd orchestrator && uv run sdlc run --dry-run; echo $?` -- expected: exit 0, all scopes passed
