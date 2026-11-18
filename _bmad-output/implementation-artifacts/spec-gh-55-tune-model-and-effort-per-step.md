---
title: 'Tune model and effort per step (issue #55)'
type: 'feature'
created: '2026-11-18'
status: 'done'
baseline_revision: '8a534f9eb866c3a789360f8262a8dc799a41429e'
review_loop_iteration: 0
followup_review_recommended: false
context: []
warnings: []
deferred:
  - summary: >-
      Usage ledger resolves model_requested/effort from the current pipeline, not the values the attempt was launched with.
    evidence: |-
      state.py _usage_record calls limits_for on the loaded pipeline; after a retune and resume, or usage --rebuild, older records show new values. Same as the stage-level behaviour before this story.
    location: >-
      orchestrator/src/sdlc/core/state.py:428
    severity: low
  - summary: >-
      No ADR records the per-step limits extension of AD-7 (D-2).
    evidence: |-
      Earlier AD-7 amendments (effort, max_turns) were recorded in the spine only; Loredan (Architect) to decide if an ADR 0005 amendment is needed.
    severity: low
  - summary: >-
      AGENTS.md and ARCHITECTURE-SPINE.md are bookkeeping paths, so the orchestrator may revert these doc edits on the story branch.
    evidence: |-
      Settings.bookkeeping_paths lists both; Aglaia (Knowledge Keeper) updates docs after merge.
    location: >-
      orchestrator/src/sdlc/core/models.py:220
    severity: low
---

<intent-contract>

## Intent

**Problem:** `model`, `effort` and `max_turns` exist only per stage (AD-7), so the build stage's read-only reviewers cannot run on a cheaper model or lower effort without also changing Marghioala (Developer).

**Approach:** Add the same three optional fields to `Step`; resolve step value, then stage value, then `default_model`/`default_effort` (max_turns: step, then stage). Keep the step fields out of the stage-table digest, use the resolved values in the `claude -p` argv and in the usage ledger, and tune `sdlc/pipeline.yaml`.

## Boundaries & Constraints

**Always:** Same validation as stage fields (`Effort` enum without `ultracode`; `max_turns > 0`), with the error naming stage and step. Digest of an unchanged pipeline stays identical (step fields excluded, so existing runs still resume).

**Never:** No change to `dev` or `qa` steps; no per-step `timeout_min` or `budget_usd`.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Step override | stage effort high, step effort medium + model sonnet | argv `--model sonnet --effort medium` | none |
| No step override | step without fields | stage values, else settings defaults | none |
| Bad effort | step `effort: ultracode` | load error `stage 'x' step 'y': ...effort...` | PipelineError |
| Bad turns | step `max_turns: 0` | load error naming stage and step | PipelineError |
| Tuning change | step model/effort/max_turns changed | same digest; resume accepted | none |

</intent-contract>

## Code Map

- `orchestrator/src/sdlc/core/models.py:88` -- `Step`: add `model`, `effort`, `max_turns`; add a `Stage` helper resolving the three values for a step (reused by loop and ledger).
- `orchestrator/src/sdlc/core/pipeline.py:92-125` -- per-step validation loop (`step_where`); stage `max_turns > 0` check at :122.
- `orchestrator/src/sdlc/app/loop.py:758-767` -- `build_argv(model=stage.model ...)`: switch to resolved step values.
- `orchestrator/src/sdlc/core/state.py:429-430` -- ledger `model_requested`/`effort`: use resolved step values (AD-24).
- `orchestrator/src/sdlc/cli.py:479-497` -- `LIMITS`, `STEP_ATTRIBUTION`, `stage_table_digest`: exclude step limit fields.
- `orchestrator/tests/test_limits.py:470,538` -- templates for argv and resume tests.
- `sdlc/pipeline.yaml` -- steps coverage:104, security_prd:152, security_arch:177, code_review:273, code_lens:282, security:290.
- `AGENTS.md`, `_bmad-output/planning-artifacts/architecture/architecture-orchestrator-2026-11-16/ARCHITECTURE-SPINE.md:140` -- document step fields.

## Tasks & Acceptance

**Execution:**
- `orchestrator/tests/test_pipeline.py` -- tests AC-1..AC-5 first (named `test_ac1_...` etc.).
- `orchestrator/src/sdlc/core/models.py` -- fields + `Stage.limits_for(step)` helper.
- `orchestrator/src/sdlc/core/pipeline.py` -- `max_turns > 0` check per step.
- `orchestrator/src/sdlc/app/loop.py`, `orchestrator/src/sdlc/core/state.py` -- use helper.
- `orchestrator/src/sdlc/cli.py` -- exclude step limits from digest.
- `sdlc/pipeline.yaml` -- AC-4 values.
- `AGENTS.md`, spine AD-7 -- describe step fields.

**Acceptance Criteria:**
- Given a step with model/effort/max_turns, when its invocation is built, then argv carries the step values; given a sibling without them, then argv carries the stage values.
- Given step `effort: ultracode` or `max_turns: 0`, when the pipeline loads, then a PipelineError names the stage and step.
- Given a step tuning change, when the digest is computed, then it equals the original.
- Given the real `sdlc/pipeline.yaml`, when loaded, then code_lens, security_prd, security_arch, security, coverage resolve to sonnet/medium, code_review to effort medium, dev and qa have no step overrides.
- Given `sdlc run --dry-run`, when it prints invocations, then those steps show `--model sonnet --effort medium` and dev shows no `--model`.

## Verification

- `cd orchestrator && uv run pytest -q`
- `cd orchestrator && uv run ruff check && uv run ruff format --check`
- `cd orchestrator && uv run sdlc run --dry-run` (exit 0)

## Spec Change Log

## Review Triage Log

### 2026-11-18 — Review pass
- verdicts: 16 findings — high 0, medium 0, low 11, false 2, maybe-false 0 (3 descriptive intent notes counted as false/low below)
- findings:
  - `low` `patch` Script steps accept model/effort/max_turns silently — added a load error naming stage and step, plus a test.
  - `low` `patch` usage.py retrospective text says "per stage" only — now "per stage or step".
  - `low` `reject` str.replace in tests not checked for effect — current asserts would fail on a no-op except the digest test; adding guards is extra complexity for an unlikely fixture rename.
  - `low` `reject` max_turns > 0 checked in two places — consistent with the existing stage check style; no harm named.
  - `low` `defer` Ledger uses current pipeline limits — pre-existing for stage limits.
  - `low` `reject` argv-order-dependent asserts — same pattern as existing test_limits tests; build_argv order is stable.
  - `low` `patch` unused tmp_path fixture in test_ac1 — removed. (test_ac4 not asserting code_review model: reject, AC-4 asks only effort.)
  - `low` `reject` empty-string model falls back — same as stage-level; unlikely config.
  - `low` `defer` no ADR for AD-7 extension — architect decision.
  - `false` `reject` tests couple to pipeline.yaml values — AC-4/AC-5 require exactly that.
  - `low` `patch` (edge) script-step overrides ignored — duplicate of the first row; same fix.
  - `low` `defer` (edge) ledger values from current pipeline — duplicate of the ledger row.
  - `low` `patch` (verification-gap) usage.py text — duplicate; same fix.
  - `low` `defer` (intent) doc edits are bookkeeping paths and may be reverted — orchestrator policy, not this diff.
  - `false` `reject` (intent) AC-3 tested via digest, not resume — the resume refusal is the digest compare (cli.py), and test_limits_resume_after_an_effort_change covers the command path.
  - `false` `reject` (intent) AC-5 tested via argv, not printed output — the dry-run prints these same argv; test_app_matrix checks printed code_lens argv.

## Design Notes

`settings.default_model`/`default_effort` are null in the real pipeline, so dev has no `--model`/`--effort` in the dry-run output.

## Auto Run Result

Status: done

- **Change:** `Step` gains optional `model`, `effort`, `max_turns`; `Stage.limits_for` resolves step, then stage, then settings defaults; used by the `claude -p` argv (loop.py) and the usage ledger (state.py). Step limits are left out of the stage-table digest (cli.py `STEP_EXCLUDED`). Load errors for `max_turns <= 0` and for limits on script steps name stage and step.
- **Files:** models.py (fields, `StepLimits`, `limits_for`); pipeline.py (step checks); loop.py, state.py (use resolver); cli.py (digest exclusion); usage.py (retro wording); sdlc/pipeline.yaml (AC-4 tuning, header); AGENTS.md and spine AD-7 (docs); tests test_pipeline.py (AC-1..AC-5), test_usage.py (ledger), test_app_matrix.py (expected code_lens argv).
- **Review:** 3 patches applied (all low), 3 deferred, rest rejected with reasons in the triage log.
- **Follow-up review recommended:** false (no high or medium patched).
- **Verification:** `uv run pytest -q` 420 passed, 2 skipped; ruff check and format clean; `uv run sdlc run --dry-run` exit 0, reviewer steps show `--model sonnet --effort medium`, dev shows neither flag.
- **Residual risks:** factory paths (`sdlc/`, `orchestrator/`) become an owner-review proposal; doc edits may be reverted as bookkeeping.
