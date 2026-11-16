---
title: 'Zamolxe (Orchestrator) core and dry-run'
type: 'feature'
created: '2026-11-16'
status: 'ready-for-dev'
route: 'dispatch'
review_loop_iteration: 0
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
- [ ] `orchestrator/pyproject.toml`, `uv.lock` -- uv project with the `sdlc` console script, the deps and dev deps from the spine, and the ruff and pytest config from its conventions.
- [ ] `orchestrator/src/sdlc/core/models.py` -- frozen pydantic models: `Pipeline`, `Stage`, `Step`, `RunState`, scope nodes, `StepResult`, `Verdict`, `Finding`, `Status` (StrEnum), and events.
- [ ] `orchestrator/src/sdlc/core/pipeline.py` -- load and validate the YAML, including the load-time checks from AD-7, AD-16 and AD-17.
- [ ] `orchestrator/src/sdlc/core/state.py` -- `apply(state, event)` with only the legal AD-5 transitions, round counters, `head_sha`/`base_sha`, budget reservation.
- [ ] `orchestrator/src/sdlc/core/gates.py` -- the AD-19 registry; verdict normalisation (AD-10).
- [ ] `orchestrator/src/sdlc/core/scheduler.py` -- `decide(pipeline, state, inputs) → actions`: stage chain, `on_fail` rewind, story readiness (AD-15), planning integration (AD-20), the learn queue (AD-21), escalation (AD-14), the exit condition (AD-11).
- [ ] `orchestrator/src/sdlc/core/epics_parser.py`, `core/register_parser.py` -- pure parsers over text.
- [ ] `orchestrator/src/sdlc/ports.py`, `adapters/fake/` -- Protocols, and fakes driven by `tests/fixtures/dryrun/` (results and verdicts keyed by scope/step/round).
- [ ] `orchestrator/src/sdlc/store.py` -- atomic `state.json`, `events.jsonl`.
- [ ] `orchestrator/src/sdlc/app/loop.py`, `cli.py` -- the loop, and `run [--from] [--dry-run]`, `status`, `resume`, with the spine's exit codes. Resume aborts a rebase in progress and resets interrupted steps (AD-6).
- [ ] `sdlc/pipeline.yaml`, `sdlc/prompts/*.md` -- the stage table from plan §2 with the AD-7 defaults; one prompt template per agent or judge step, naming inputs and the verdict path.
- [ ] `orchestrator/tests/` -- unit tests per core module, plus app tests for every matrix row (test names carry the row name, T-1). Use the fake clock (T-5).
- [ ] `_bmad/custom/bmad-create-epics-and-stories.toml`, `AGENTS.md` -- the story yaml block with `sources`, and the orchestrator commands.

**Acceptance Criteria:**
- AC-1: Given a fresh clone, when `cd orchestrator && uv run sdlc run --dry-run` runs, then it exits 0 and prints each step it would invoke, including the exact `claude -p` argument list (AD-8).
- AC-2: Given the repo, when `uv run pytest` and `uv run ruff check` run in `orchestrator/`, then both pass.
- AC-3: Given `core/`, when it is scanned for imports, then nothing imports `subprocess`, `asyncio`, `os`, `sdlc.adapters` or `sdlc.app`. A test enforces this.

## Implementation Notes

## Spec Change Log

## Review Triage Log

## Verification

**Commands:**
- `cd orchestrator && uv run pytest -q` -- expected: all pass
- `cd orchestrator && uv run ruff check && uv run ruff format --check` -- expected: clean
- `cd orchestrator && uv run sdlc run --dry-run; echo $?` -- expected: exit 0, all scopes passed
