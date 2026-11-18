---
title: 'sdlc pause / stop / resume (issue #72)'
type: 'feature'
created: '2026-11-18'
status: 'done'
review_loop_iteration: 0
followup_review_recommended: false
context: []
warnings: ['oversized']
deferred:
  - summary: 'SIGTERM before the handler is installed (between on_run and _loop, or after _loop returns) kills the process without RunPaused; the run stays running and a resume treats its steps as interrupted'
    location: 'orchestrator/src/sdlc/app/loop.py _loop'
    severity: medium
  - summary: 'A pause request written after the run left its ticks stays on disk until sdlc resume of that run; queue-history records a paused entry as 0'
    location: 'orchestrator/src/sdlc/cli.py _control, _locked'
    severity: low
---

<intent-contract>

## Intent

**Problem:** The only way to pause a run is to kill its process: running agent steps lose their work, their caps are charged, and the queue keeps no record of the owner's intent.

**Approach:** Add `sdlc pause [<run-id>]` (a pause request file beside the lock, read by the run on every tick and while it waits) and `sdlc stop [<run-id>]` (SIGTERM to the pid in the lock file). A new run-only status `paused` ends the run with exit 0, releases the lock and never drains the queue; `sdlc resume` continues it.

## Boundaries & Constraints

**Always:** pause starts no new step and lets agent steps finish and be recorded (their commits and head kept); script steps (waits) are cancelled at no cost (cap 0) and their scopes reset once nothing runs; stop cancels every running step, resets its worktree (AD-6) and charges its cap (AD-11/AD-24) through `StepInterrupted`; a request names a run id and is ignored by any other run; resume deletes a leftover request for its own run; `paused` consumes no round.

**Never:** no queue drain after a pause or stop; no change to the meaning of the existing `RunStopped` for crashes (still audit only); no pid search outside the lock file; no edits outside the story's touch list except the spine and the spec.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Pause, step running | request for this run while 1 agent step runs | `PauseRequested`, step finishes and is recorded, `RunPaused(pause)`, status `paused`, exit 0, request removed | none |
| Pause, idle tick | request present at tick | `RunPaused(pause)` with no new step | none |
| Pause for other run | request names another run id | ignored; run finishes normally | none |
| Stop | SIGTERM / `request_stop()` while a step runs | step `interrupted`, cap charged, worktree reset, `RunStopped(owner stop)`, `RunPaused(stop)`, exit 0 | none |
| Stop while pausing | stop arrives during a pause drain | remaining steps interrupted as for stop; kind `stop` | none |
| Resume paused | `sdlc resume <id>` | status `running`, leftover request removed, pending work continues; finished steps not rerun | none |
| CLI pause/stop no run | lock not held | message, exit 1 | stderr |

</intent-contract>

## Code Map

- `orchestrator/src/sdlc/core/models.py` -- `Status` (add `PAUSED`), `RunState` (add `pause_kind`, `pause_requested_at`, `paused_at`), events (`PauseRequested`, `RunPaused`; `RunStopped` stays audit).
- `orchestrator/src/sdlc/core/state.py` -- `TRANSITIONS` (`running -> paused`, `paused -> pending`), `_move` run-only guard, `_run_resumed` accepts `paused` and clears pause fields, new handlers.
- `orchestrator/src/sdlc/app/loop.py` -- `Orchestrator._ticks` (control check per tick), `_wait_tasks` (poll with `poll_s`), `Wait` sleep made interruptible, new `_pause`, `request_stop`, `pause_file`; `resume` removes leftover request; `format_status` pausing/paused lines; SIGTERM handler installed in `_loop`.
- `orchestrator/src/sdlc/cli.py` -- `pause` and `stop` subcommands; `RepoLock` dir holds `pause`; run commands set `args.no_drain` when the run ended `paused`; `_locked` releases without `_drain` then.
- `orchestrator/src/sdlc/core/scheduler.py` -- unchanged: `decide` is not called while pausing.
- `orchestrator/tests/test_pause.py` -- new tests.
- `AGENTS.md`, spine `ARCHITECTURE-SPINE.md` AD-5/AD-22/AD-27 -- docs.

## Tasks & Acceptance

**Execution:**
- `orchestrator/src/sdlc/core/models.py`, `core/state.py` -- status, fields, events, transitions -- AD-5.
- `orchestrator/src/sdlc/app/loop.py` -- pause/stop control and status lines.
- `orchestrator/src/sdlc/cli.py` -- subcommands, no drain after pause.
- `orchestrator/tests/test_pause.py` -- matrix rows and ACs.
- `AGENTS.md`, spine -- document.

**Acceptance Criteria:**
- Given a running run with an agent step in flight, when `sdlc pause` writes a request for it, then the run starts no new step, records the step's result, ends `paused` with exit 0, and the lock is released while the queue keeps its entries.
- Given a running run, when `sdlc stop` signals it, then running steps are interrupted (worktree reset, cap charged), `RunStopped` reason `owner stop` is recorded, status `paused`, the queue is not drained.
- Given a paused run, when `sdlc resume` runs, then it continues from where it paused, keeps steps finished during the pause and removes a leftover request.
- Given a pausing run, `sdlc status` prints `pausing (n steps finishing)`; given a paused run, `paused at <time> (pause|stop)`.
- Given a request for run A, run B ignores it.

## Spec Change Log

## Review Triage Log

### 2026-11-18 — Review pass
- layers: blind-hunter, edge-case-hunter ran; verification-gap and intent-alignment skipped (run budget $5).
- verdicts: 13 findings — high 1, medium 4, low 5, false 2, maybe-false 1
- findings:
  - `[high]` `[patch]` tasks already finished when a stop or pause arrives were cancelled and charged — `_cancel` now records done tasks first.
  - `[medium]` `[patch]` actions after an early-ended `Wait` could still start steps — `_ticks` checks `_control()` before each action and skips the wait.
  - `[medium]` `[patch]` `sdlc stop` could signal a reused pid from a stale lock record — `_running` probes the flock; test added.
  - `[medium]` `[defer]` SIGTERM outside `_loop` kills the process uncleanly — recorded in `deferred`.
  - `[medium]` `[patch]` `os.kill` errors raised a traceback — now exit 1 with a message.
  - `[low]` `[patch]` `pausing` shown for a crashed run — shown only while `running`.
  - `[low]` `[patch]` AGENTS.md exit codes stale — updated.
  - `[low]` `[defer]` lingering request after a run finished; queue-history shows 0 for a pause — `deferred`.
  - `[low]` `[reject]` shared `pause.tmp` between concurrent `sdlc pause` — unlikely, replace is atomic; not worth a branch.
  - `[low]` `[reject]` previous SIGTERM handler not restored — the CLI installs none.
  - `[false]` `[reject]` no ADR (D-2) — the spine (AD-5, AD-22) is updated; it is the orchestrator's decision record.
  - `[false]` `[reject]` `_cancel` duplicates `_stop` — `_stop` leaves steps running for resume by design (crash path); different semantics.
  - `[maybe-false]` `[reject]` paused issue run blocks a new run for the issue — intended: the run is resumable, not finished.

## Verification

**Commands:**
- `cd orchestrator && uv run pytest -q` -- expected: all pass
- `cd orchestrator && uv run ruff check && uv run ruff format --check` -- expected: clean

## Auto Run Result

Status: done. Implemented directly by Marghioala (Developer) in the main session rather than an implementation subagent (budget); two review layers ran. `orchestrator/tests/test_state.py` (outside the touch list) got the two new AD-5 edges.
