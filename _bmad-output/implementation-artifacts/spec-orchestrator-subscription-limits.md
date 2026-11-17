---
title: 'Zamolxe (Orchestrator) subscription limits, budget wait and credential preflight'
type: 'feature'
created: '2026-11-17'
status: 'draft'
route: 'dispatch'
review_loop_iteration: 0
context:
  - '{project-root}/_bmad-output/planning-artifacts/architecture/architecture-orchestrator-2026-11-16/ARCHITECTURE-SPINE.md'
  - '{project-root}/docs/adr/0005-orchestrator-subscription-limits-and-budget.md'
---

<frozen-after-approval reason="human-owned intent — do not modify unless human renegotiates">

## Intent

**Problem:** The owner runs on a Claude subscription. Several current behaviours waste rounds or bill unexpectedly:

- Parallel stories escalate as "budget exhausted" long before $50 of estimated usage, because reservations count as spending.
- A plan usage limit counts as a failed round.
- An `ANTHROPIC_API_KEY` in the environment would silently switch `claude -p` to per-token billing.
- Model and effort cannot be tuned per stage.

**Approach:** Implement the 2026-11-17 amendments of the orchestrator spine exactly: AD-1, AD-5, AD-6, AD-7, AD-8, AD-11, AD-18 and the new AD-23, with ADR 0005. Keep the core pure and the existing tests green, except where an amended rule changes the expected behaviour.

## Boundaries & Constraints

**Always:**
- The spine's amended rules are binding: the "waiting" condition, `Wait(until)`, `Clock.sleep_until`, `superseded`, the `UsageLimited` event, the limit episode, FIFO budget wait, env stripping, and the AD-23 credential checks.
- `limited` detection is one case-insensitive pattern list in `adapters/claude.py`, applied only when `subtype != "success"` or `is_error` is true.
- The dry-run fake clock's `sleep_until` returns at once and moves the clock to `until`.
- `sdlc resume` accepts changes to `model`, `effort` and `max_turns`.

**Never:**
- No in-process wait inside `claude` (no `CLAUDE_CODE_RETRY_WATCHDOG*` set by the orchestrator).
- No `ultracode` effort value.
- No paid call and no GitHub call in the default test suite.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Budget wait | Limit 20; 3 parallel steps with caps 5, 5, 15; spent 0 | First two start; the 15 waits; starts after one finishes; no escalation | N/A |
| Budget FIFO | A 15-cap step waiting, a 5-cap step queued after it | The 5-cap step does not start before the 15-cap one | N/A |
| Budget exhausted | spent 18, limit 20, next cap 5, nothing running | Run escalates, scope `run`, exit 2 | One escalation issue |
| Limited | A dev step's result is an error whose text says "You've hit your session limit" | The attempt is `superseded`; worktree reset; patch saved; no round counted; `resume_at` set; after the fake clock passes it, a new attempt runs in the same round and passes | N/A |
| Limited in parallel | Two steps limited in the same tick | One `resume_at` (the max); the episode is counted once | N/A |
| Limit text on success | `subtype: success`, and the result quotes "hit your weekly limit" | Outcome by normal rules (`done`), not `limited` | N/A |
| Limit episode too long | Limited results until wall-clock passes `usage_limit_max_wait_h` | Run escalates, scope `run`, exit 2 | N/A |
| Crash while waiting | Process killed during the wait; `sdlc resume` | Resume clears `resume_at` and the episode; the step runs | N/A |
| Waiting is not finished | Only `resume_at` pending, nothing running | No `Finish`; a `Wait(until)` action | N/A |
| API key present | `ANTHROPIC_API_KEY=x`, or an `apiKeyHelper` in `.claude/settings.local.json`, `allow_api_key` false | Preflight exits 1 naming the source; no run dir | Empty value counts as unset |
| API key allowed | Same, `allow_api_key: true` | Preflight passes | N/A |
| Env stripping | Parent env has `CLAUDE_CODE_RETRY_WATCHDOG=1`, `CLAUDE_CODE_EFFORT_LEVEL=high` | The agent process sees neither | N/A |
| Model, effort, turns | Stage `model: sonnet`, `effort: low`, `max_turns: 20`; setting `default_effort: medium` | argv has `--model sonnet --effort low --max-turns 20`; a stage without them gets `--effort medium` | `ultracode` is a load error |
| Unparseable output | Stub exits 1 with text | That step ends `error` (failed round); the run continues | N/A |
| Resume after an effort change | `effort` changed between run and resume | Resume proceeds | N/A |

</frozen-after-approval>

## Code Map

- `orchestrator/src/sdlc/core/models.py`:
  - `Settings` (line 152): add `default_model`, `default_effort`, `usage_limit_wait_min`, `usage_limit_max_wait_h`, `allow_api_key`.
  - `Stage`: add `effort`, `max_turns`.
  - `Outcome` (line 40): add `LIMITED`.
  - `Status`: add `SUPERSEDED`.
  - `TickInputs` (line 618): add `now`.
  - New: the `UsageLimited` event and the `Wait` action. State gains `resume_at` and `limit_episode_start`.
- `orchestrator/src/sdlc/core/scheduler.py`:
  - `_Tick.fits` (line 73) holds the budget rule; replace it with wait, FIFO and escalate (AD-11).
  - The exit at line 118 must not `Finish` while waiting.
- `orchestrator/src/sdlc/core/state.py` -- `apply`: the reserved invariant, superseded records, the limit episode, resume clearing.
- `orchestrator/src/sdlc/core/outcome.py` -- `derive_outcome` (line 20): `limited` first, guarded by subtype and error.
- `orchestrator/src/sdlc/adapters/claude.py`:
  - `build_argv`: `--effort` and `--max-turns`.
  - The usage-limit pattern list.
  - `ClaudeCli.run`: strip env vars. `run_proc` merges `os.environ` (proc.py line 61), so remove them from the merged map.
- `orchestrator/src/sdlc/app/loop.py`:
  - The invocation env at line 444.
  - Handle `Wait`.
  - The "no progress" `LoopError` at line 273 must not fire while waiting.
  - Unparseable output ends the step as `error`.
- `orchestrator/src/sdlc/ports.py` `Clock` (line 89), `adapters/clock.py`, `adapters/fake/__init__.py` `FakeClock` (line 35) -- `sleep_until`.
- `orchestrator/src/sdlc/adapters/real.py` `preflight` (line 35) -- the AD-23 checks.
- `orchestrator/src/sdlc/cli.py` `stage_table_digest` (line 81) -- exclude `effort` and `max_turns`.
- `sdlc/pipeline.yaml` -- add the new settings with their spine defaults. Leave per-stage model and effort unset.
- `orchestrator/tests/fixtures/dryrun/` -- add a `usage-limit` scenario.

## Tasks & Acceptance

**Execution:**
- [ ] `core/models.py`, `core/pipeline.py` -- the new fields, enums, event and action; load validation for `effort` and `max_turns` (> 0).
- [ ] `core/outcome.py`, `adapters/claude.py` -- `limited` detection; `--effort` and `--max-turns`; env stripping.
- [ ] `core/state.py`, `core/scheduler.py` -- budget wait and FIFO, superseded records, `resume_at` and the episode, the waiting exit rule.
- [ ] `ports.py`, `adapters/clock.py`, `adapters/fake/` -- `sleep_until`; the fake advances at once.
- [ ] `app/loop.py` -- `Wait` handling, unparseable output as a step `error`, now in tick inputs.
- [ ] `adapters/real.py`, `cli.py` -- the AD-23 preflight; resume digest; resume clears `resume_at`.
- [ ] `sdlc/pipeline.yaml`, `tests/fixtures/dryrun/` -- the settings and the `usage-limit` scenario.
- [ ] `orchestrator/tests/` -- one test per matrix row (`test_limits_<row>`); update tests whose expectation the amendments change.
- [ ] `AGENTS.md` -- one line on the credential preflight and keeping extra-usage credits off.

**Acceptance Criteria:**
- AC-1: Given `uv run sdlc run --dry-run --scenario usage-limit`, when it runs, then it pauses once, resumes, and exits 0 with no escalation.
- AC-2: Given the repo, when `uv run pytest -q` and `uv run ruff check && uv run ruff format --check` run in `orchestrator/`, then all pass, with the live test skipped.
- AC-3: Given `uv run sdlc run --dry-run`, when it runs, then it exits 0, unchanged.

## Implementation Notes

## Spec Change Log

## Review Triage Log

## Verification

**Commands:**
- `cd orchestrator && uv run pytest -q` -- expected: all pass, live skipped
- `cd orchestrator && uv run ruff check && uv run ruff format --check` -- expected: clean
- `cd orchestrator && uv run sdlc run --dry-run; echo $?` -- expected: 0
- `cd orchestrator && uv run sdlc run --dry-run --scenario usage-limit; echo $?` -- expected: 0
