---
title: 'Zamolxe (Orchestrator) subscription limits, budget wait and credential preflight'
type: 'feature'
created: '2026-11-17'
status: 'done'
route: 'dispatch'
review_loop_iteration: 0
baseline_commit: 'eebae1f38fc0a970828f99792d2e6db6a49e8db0'
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
- [x] `core/models.py`, `core/pipeline.py` -- the new fields, enums, event and action; load validation for `effort` and `max_turns` (> 0).
- [x] `core/outcome.py`, `adapters/claude.py` -- `limited` detection; `--effort` and `--max-turns`; env stripping.
- [x] `core/state.py`, `core/scheduler.py` -- budget wait and FIFO, superseded records, `resume_at` and the episode, the waiting exit rule.
- [x] `ports.py`, `adapters/clock.py`, `adapters/fake/` -- `sleep_until`; the fake advances at once.
- [x] `app/loop.py` -- `Wait` handling, unparseable output as a step `error`, now in tick inputs.
- [x] `adapters/real.py`, `cli.py` -- the AD-23 preflight; resume digest; resume clears `resume_at`.
- [x] `sdlc/pipeline.yaml`, `tests/fixtures/dryrun/` -- the settings and the `usage-limit` scenario.
- [x] `orchestrator/tests/` -- one test per matrix row (`test_limits_<row>`); update tests whose expectation the amendments change.
- [x] `AGENTS.md` -- one line on the credential preflight and keeping extra-usage credits off.

**Acceptance Criteria:**
- AC-1: Given `uv run sdlc run --dry-run --scenario usage-limit`, when it runs, then it pauses once, resumes, and exits 0 with no escalation.
- AC-2: Given the repo, when `uv run pytest -q` and `uv run ruff check && uv run ruff format --check` run in `orchestrator/`, then all pass, with the live test skipped.
- AC-3: Given `uv run sdlc run --dry-run`, when it runs, then it exits 0, unchanged.

## Implementation Notes

- Detection: `adapters/claude.py` `flag_usage_limit` sets `StepResult.usage_limited` (and `resets_at`, parsed from "resets 3pm (Zone)") only on an error result; `core/outcome.py` returns `limited` first when that flag is set on an error result. The loop flags every agent result, including `error_adapter` ones, with the clock's `now`.
- The app emits `UsageLimited(seq, resets_at)` after the `StepFinished` of a `limited` step. `StepFinished` closes the record as `superseded` and marks the scope for reset; `UsageLimited` sets `resume_at` and the episode.
- Episode check: the run escalates (scope `run`, `RunState.limit_exceeded`) when `resume_at - limit_episode_start > usage_limit_max_wait_h`, because the episode lasts at least until `resume_at`.
- Budget: `apply` recomputes `reserved` as the sum of running caps after every event. A step that does not fit only because of reservations waits; FIFO holds within the tick (units order), as AD-11 states.
- Scenario attempts: fixture keys accept `/r<n>/a<k>` (k-th attempt in the round) for the `usage-limit` scenario.
- `test_matrix_budget_cap` now uses a $4 ceiling (architecture's $5 cap cannot fit), because $9 no longer escalates under the amended AD-11.

## Spec Change Log

## Review Triage Log

Pass 1 (2026-11-17). Reviewers: BH = Blind Hunter, VG = Verification Gap, EC = Edge Case Hunter.

| # | Finding | Verdict | Evidence | Route |
|---|---|---|---|---|
| 1 | BH1, EC1: limit flagged in the adapters with the wall clock; the loop's flag is a no-op | medium | The early return in `flag_usage_limit` skips the loop call (T-5) | patch |
| 2 | BH2, EC3: dated (weekly) resets misread; a bare hour is read as a time | high | `_RESETS` reads only hour and minute; "resets Nov 20, 3pm" becomes tomorrow | patch |
| 3 | BH3, EC5: other limit wordings and the `\|<epoch>` form not matched | medium | Only "hit your <model> limit"; misses become failed rounds | patch |
| 4 | EC4, VG-o2: an unknown or missing zone uses the machine's zone | medium | The VM is UTC; the message is in the user's zone | patch (return None, use the default wait) |
| 5 | BH4: `sdlc resume` drops a wait that is still in force | low | Matches the agreed rule (spec matrix "Crash while waiting", AD-18, ADR 0005): resume is a deliberate owner action | reject (flagged to the owner) |
| 6 | EC2: the fake attempt counter resets on resume | low | Dry-run only; seeding from state is a direct fix | patch |
| 7 | BH5: any non-limited outcome should end the episode | false | AD-18 says the episode ends at the next `done` | reject |
| 8 | EC6: a done step that started before the limit closes the episode | medium | Pushes back the max-wait escalation | patch |
| 9 | BH6: no notification on pause; status line stays forever | medium | `notifier` is used only on escalation | patch |
| 10 | BH7, EC10, EC11, EC12: preflight misses `CLAUDE_CONFIG_DIR` and managed settings; `USE_*=0` refused; env strip misses `inv.env`; credentials not stripped from agents | medium | `settings_files` paths; the strip applies to the parent env only | patch |
| 11 | BH8: SUPERSEDED reachable by units and the run; a run escalation reason is overwritten | medium | A shared `TRANSITIONS` table; `_escalate_record` replaces the reason | patch |
| 12 | BH9: a crash between StepFinished and UsageLimited loses the wait | medium | Two separate events | patch |
| 13 | BH10: `_outcome` duplicates the outcome derivation for every step | low | Drift risk between two paths | patch |
| 14 | EC8: a sibling's commit captures the limited attempt's files | false | AD-16 allows one writer per worktree in a parallel group | reject |
| 15 | EC9: the limited attempt's patch is fed back as feedback | medium | `_scope_reset` appends every patch | patch |
| 16 | EC7: a second run escalation opens no issue | medium | `_escalate_record` keeps the old issue | patch |
| 17 | VG1: limit text on the unparseable-output path untested | high | Pre-verified | patch |
| 18 | VG2: `SystemClock.sleep_until` untested | medium | Pre-verified | patch |
| 19 | VG3: CLI-level `allow_api_key: true` untested | medium | Pre-verified | patch |
| 20 | VG4, BH11: `parse_reset` branches untested; HOME not isolated in one test | medium | Pre-verified | patch |
| 21 | VG5: status line during a pause untested | low | A cheap assertion | patch |
| 22 | BH12: docs miss the new scenarios and settings | low | `AGENTS.md`, `pipeline.yaml` header | patch |
| 23 | BH12b: the budget test change lacks an ADR in the diff | false | ADR 0005 and the spine amendment are committed in `5d654ee` and `2e8f09f` | reject |

## Verification

**Commands:**
- `cd orchestrator && uv run pytest -q` -- expected: all pass, live skipped
- `cd orchestrator && uv run ruff check && uv run ruff format --check` -- expected: clean
- `cd orchestrator && uv run sdlc run --dry-run; echo $?` -- expected: 0
- `cd orchestrator && uv run sdlc run --dry-run --scenario usage-limit; echo $?` -- expected: 0
