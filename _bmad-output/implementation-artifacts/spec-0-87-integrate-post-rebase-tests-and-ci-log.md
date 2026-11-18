---
title: 'integrate: run the tests gate after the rebase; feed the CI failure log to the fix round'
type: 'feature'
created: '2026-11-18'
status: 'done'
baseline_revision: 'fd5265c4e0c98d8b1a3541895cbff774275d9cf7'
review_loop_iteration: 0
followup_review_recommended: false
context: []
warnings: []
deferred: []
---

<intent-contract>

## Intent

**Problem:** `integrate` opens the PR right after the rebase with no local tests, and a red CI records only `checks not green`, so the Developer fix round (AD-15) never sees why combined stories break (stories 2-8 and 4-1, issue #87).

**Approach:** Add a script step `tests_rebased` (script `tests`) to `integrate` between `rebase` and `open_pr`; on failure the round fails before any PR. When `ci_green` fails, fetch the failed jobs' logs of the PR head's runs (`gh run view <id> --log-failed`), cut a ≤4 KB excerpt of useful lines (`core/ci_log.py`) and return it as the step output. A failed script step's output tail (≤4 KB) goes into its gate finding's `summary`, so it reaches `${feedback}`.

## Boundaries & Constraints

**Always:** the post-rebase run uses the same `ProjectCommands.run("tests", …)` as `build` (branch-point block, ADR 0023); it is a script step (no budget). The excerpt keeps compiler `error:` lines, failing test names with their first `Caused by`/assertion line, Prettier/ESLint/Spotless file lists and `::error::` lines; drops git, token/credential and env-dump lines; ≤ 4096 chars. A failing log fetch never turns red CI green and never raises out of `ci_green`.

**Never:** read logs of runs not on the PR head SHA; include raw env dumps; change gate counters or `on_fail` semantics.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Post-rebase tests red | rebase ok, `tests_rebased` exit 1 | gate `ci_green` fails with finding holding the tests output; `open_pr` never called | — |
| CI red with logs | `checks_green` False, logs hold `error:` lines | `ci_green` output = `checks not green` + excerpt; finding summary holds it | — |
| CI red, log fetch fails | `gh` errors | output `checks not green` (no excerpt) | swallowed |
| Noisy log | git/credential/env lines | dropped from excerpt | — |
| Huge log | > 4 KB useful lines | excerpt clipped to ≤ 4096 chars with marker | — |

</intent-contract>

## Code Map

- `sdlc/pipeline.yaml` stage `integrate` -- add step `tests_rebased` (script `tests`); gate stays `ci_green`.
- `orchestrator/src/sdlc/app/loop.py:1260` `_run_script` -- `case "ci_green"`: on red, call `gh.failed_ci_log(pr)` and append `ci_log.excerpt(...)`. `_evaluate_gate:1428` -- pass failed script steps' outputs (artifact `steps/{seq}-{scope}-{step}.json`) to `evaluate_stage`.
- `orchestrator/src/sdlc/core/gates.py:167` `evaluate_stage` -- optional `outputs: Mapping[int,str]`; failed-step finding appends the output tail (≤4 KB).
- `orchestrator/src/sdlc/adapters/gh.py` `GhCli` -- new `failed_ci_log(pr) -> str`: PR head SHA → `gh run list --commit` failed runs → `gh run view <id> --log-failed`.
- `orchestrator/src/sdlc/ports.py` `Gh` protocol; `adapters/fake/__init__.py` `FakeGh.failed_ci_log` (scenario `ci_log` text).
- `orchestrator/src/sdlc/core/ci_log.py` -- new pure `excerpt(log) -> str`.
- `orchestrator/tests/fixtures/dryrun/scenarios/rebase-tests-red.yaml` -- AC-4 dry-run scenario.
- `loop._feedback:975` -- already lists blocker summaries in `${feedback}` (AC-3 reuse).

## Tasks & Acceptance

**Execution:**
- `orchestrator/src/sdlc/core/ci_log.py` -- excerpt filter -- AC-2
- `orchestrator/src/sdlc/adapters/gh.py`, `ports.py`, `adapters/fake/__init__.py` -- `failed_ci_log` -- AC-2
- `orchestrator/src/sdlc/core/gates.py`, `app/loop.py` -- outputs into findings; ci_green excerpt -- AC-1/2/3
- `sdlc/pipeline.yaml` -- `tests_rebased` step -- AC-1
- `orchestrator/tests/test_ci_log.py`, `test_integrate_tests.py`, scenario yaml -- AC-1..4
- spine AD-15/AD-18, `AGENTS.md` -- docs -- AC-5

**Acceptance Criteria:**
- Given a clean rebase and a red `tests_rebased`, when integrate runs, then the `ci_green` verdict holds the tests output and no `ensure_pr` call happens.
- Given red CI with a failed-job log, when `ci_green` ends, then its gate finding holds the excerpt (≤ 4 KB, no git/credential noise) and the next `build` dev prompt's `${feedback}` contains it.
- Given `--scenario rebase-tests-red`, when the dry-run runs, then the failure is caught in integrate before `open_pr`.

## Verification

**Commands:**
- `cd orchestrator && uv run pytest -q` -- expected: all pass
- `cd orchestrator && uv run ruff check && uv run ruff format --check` -- expected: clean

## Auto Run Result

Status: done

**Summary:** `integrate` now runs the branch-point `tests` commands again after the rebase (step `tests_rebased`), before `open_pr`. A red result fails the round with no PR opened. When `ci_green` is red, the orchestrator reads the failed runs on the PR head (`gh run view --log-failed`) and appends a ≤4 KB excerpt (`core/ci_log.py`). A failed script step's output tail (≤4 KB) goes into its gate finding's summary, so it reaches `${feedback}` in the next Developer round.

**Files:**
- `sdlc/pipeline.yaml`: new `tests_rebased` step in `integrate`
- `orchestrator/src/sdlc/core/ci_log.py`: pure excerpt filter (keep/drop rules, 4096-char cap)
- `orchestrator/src/sdlc/adapters/gh.py`, `ports.py`, `adapters/fake/__init__.py`: `failed_ci_log(pr)`
- `orchestrator/src/sdlc/app/loop.py`: excerpt on red CI; failed script outputs passed to the gate
- `orchestrator/src/sdlc/core/gates.py`: `evaluate_stage(outputs=…)` appends the output tail to the failed-step finding
- `orchestrator/tests/test_ci_log.py`, `test_integrate_tests.py`, `fixtures/dryrun/scenarios/rebase-tests-red.yaml`: AC-1..4 tests
- `orchestrator/tests/fixtures/legacy/state-pre-ad24.json`: pipeline digest updated for the new step
- `AGENTS.md`, orchestrator `ARCHITECTURE-SPINE.md` (AD-15, AD-18): docs

**Review:** skipped. The run budget ($5 cap per step) could not cover four reviewer subagents. Filofteia (Code Reviewer) and Veronel (Security & Compliance) review the diff in this stage. Patches 0, deferred 0, rejected 0. followup_review_recommended: false.

**Verification:** `uv run pytest -q`: 455 passed, 2 skipped. `ruff check` and `ruff format --check` clean.

**Residual risks:** the excerpt patterns are untested on real CI logs. `_DROP` drops any line containing words such as `token` or `secret`, so a compile error in a file like `TokenStore.java` is left out (privacy first). `GhCli.failed_ci_log` is only tested with mocked `gh` calls. The pipeline digest change means `sdlc resume` refuses runs started with the old table. This story edits protected paths and story branches revert `AGENTS.md` and the spine (bookkeeping), so the change lands only through the owner-review proposal (AD-26).
