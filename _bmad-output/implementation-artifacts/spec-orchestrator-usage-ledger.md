---
title: 'Zamolxe (Orchestrator) usage ledger per agent, with opt-in OpenTelemetry'
type: 'feature'
created: '2026-11-17'
status: 'done'
route: 'dispatch'
review_loop_iteration: 0
baseline_commit: '3fafbf816b6fd5fd10fabd11fbd662ab8dc98bdb'
context:
  - '{project-root}/_bmad-output/planning-artifacts/architecture/architecture-orchestrator-2026-11-16/ARCHITECTURE-SPINE.md'
  - '{project-root}/docs/adr/0019-orchestrator-usage-ledger-per-agent.md'
---

<frozen-after-approval reason="human-owned intent — do not modify unless human renegotiates">

## Intent

**Problem:** The owner wants the token use of every agent measured. Today each step stores one estimated cost, and `sdlc status` shows only the run total. Nothing is attributed to an agent, kept across runs, warned about, or given to the retrospective.

**Approach:** Implement AD-24 (the usage ledger per agent) and AD-25 (opt-in OpenTelemetry) of the orchestrator spine, with ADR 0019, exactly as written. Phase 1 run `20261117-174705-full` is in progress and must stay resumable with the new code. `orchestrator/tests/fixtures/legacy/state-pre-ad24.json` is a snapshot of its state.

## Boundaries & Constraints

**Always:**
- `state.json` is the source and the ledger is a projection: `(run_id, seq)` is the key, reconciled on load, and rebuilt by `sdlc usage --rebuild`.
- Token totals are summed over `modelUsage`.
- `core/usage.py` stays pure and is the only aggregator.
- Budgets keep reading `state.json`.
- Every agent and judge step in `sdlc/pipeline.yaml` gets an `agent_role`, using these roles and allowed runners:
  - Pompiliu (Analyst): `bmad-product-brief`
  - Onofrei (PM): `bmad-prd`, `bmad-create-epics-and-stories`
  - Safta (UX Designer): `bmad-ux`
  - Veronel (Security & Compliance): `security-compliance-reviewer`
  - Loredan (Architect): `bmad-architecture`, `bmad-sprint-planning`
  - Pacala (DevOps): `devops-release`
  - Marghioala (Developer): `bmad-build-auto`
  - Eufrosina (QA): `bmad-testarch-automate`, `bmad-testarch-atdd`
  - Filofteia (Code Reviewer): `bmad-code-review`, `code-reviewer`
  - Aglaia (Knowledge Keeper): `knowledge-keeper`, `bmad-retrospective`
  - Judge (gate check): every `judge` step
- `agent_role` is excluded from the stage-table digest.
- The agent environment follows AD-25: the content-logging variables are always removed; when telemetry is off, the telemetry switches are removed too; when it is on, only `otlp` is allowed.

**Never:**
- No prompt, result or tool text in ledger rows or telemetry.
- No ledger writes outside `store`.
- A dry-run never writes the real ledger.
- No edits to `.claude/settings*.json` or `.claude/hooks/`.
- No paid call and no network call in the default test suite.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Per-agent report | Happy dry-run | `sdlc usage <run> --by agent` lists every role that ran with tokens, cost and attempts; the sum of `cost_usd` equals `budget.spent` | N/A |
| Subagent tokens | Result whose `modelUsage` has two models, and a top-level `usage` smaller than their sum | Token totals equal the `modelUsage` sums; `usage` kept for information only | N/A |
| Failed and superseded attempts | `gate-fails-then-passes` and `usage-limit` scenarios | Every attempt has a record with its status, outcome and subtype; `--by round` shows the retry cost | N/A |
| Timeout or crash | No parsable output, or `error_during_execution` with cost 0 | Record charges the budgeted amount, zero tokens, `usage_complete: false` | N/A |
| Script step | `tests` gate | No record | N/A |
| Crash between state save and ledger append | Process killed after the save | On load, reconcile appends the missing line once | A torn last line is skipped on read |
| Resume twice | Resume an already-reconciled run | No duplicate keys in the ledger | N/A |
| Legacy state | `state-pre-ad24.json` loaded with the new pipeline | Loads; finished steps get backfilled records (cost from the step, `usage_complete: false`); `sdlc resume` is not refused by the digest | N/A |
| Missing or wrong role | A step without `agent_role`, or `Onofrei (PM)` on `security-compliance-reviewer` | Load error naming the stage and step | Nothing runs |
| Warning | spent + reserved crosses 80%, then the budget is raised and crossed again | One `UsageWarning` and one notification per budget value | N/A |
| Retro summary | The epic's retrospective step | `<worktree>/.sdlc/usage-summary.md` exists, and the prompt names it | N/A |
| Rebuild | Delete the ledger, run `sdlc usage --rebuild --all` | The ledger equals the reconciled projection of all runs | N/A |
| Dry-run isolation | `sdlc run --dry-run` | The ledger is in the temporary runs dir; the real ledger is untouched | N/A |
| Telemetry off | Runner env has `CLAUDE_CODE_ENABLE_TELEMETRY=1` and `OTEL_LOG_USER_PROMPTS=1` | The agent env has neither | N/A |
| Telemetry on | `telemetry: {enabled: true, endpoint, protocol}`, inherited `OTEL_RESOURCE_ATTRIBUTES=team=x` | Agent env has `OTEL_METRICS_EXPORTER=otlp`, shutdown timeout 10000, diag to stderr, `team=x,sdlc.run_id=…,sdlc.seq=…,sdlc.agent=marghioala_developer,…` (percent-encoded); content variables absent | `console` or `prometheus` is a load error |

</frozen-after-approval>

## Code Map

- `orchestrator/src/sdlc/core/models.py`:
  - `Settings`: add `agents` (role → allowed runners), `usage_warn_ratio`, `telemetry`.
  - `Step`: add `agent_role`.
  - `StepResult`: add the per-model usage, `usage` (main loop), `duration_ms`, `duration_api_ms`, `session_id`.
  - Step record: add `agent_role` and `usage`.
  - New: `UsageRecord`, and the `UsageWarning` event.
- `orchestrator/src/sdlc/core/pipeline.py` -- role validation.
- `orchestrator/src/sdlc/core/state.py`:
  - copy the role at `StepStarted`
  - build the record at `StepFinished` and at interruption
  - backfill on load
  - the warning
- `orchestrator/src/sdlc/core/usage.py` -- new, pure: aggregate by agent, stage, model, round or run; render the retrospective summary.
- `orchestrator/src/sdlc/adapters/claude.py` -- `parse_result` reads `modelUsage` and the new fields; the agent env follows AD-25.
- `orchestrator/src/sdlc/store.py` -- the ledger: append after save, reconcile on load, skip a torn last line, rebuild.
- `orchestrator/src/sdlc/cli.py`:
  - the `usage` subcommand
  - `stage_table_digest` excludes `agent_role`
  - `status` totals come from `core/usage.py`
- `orchestrator/src/sdlc/app/loop.py`:
  - keep the raw result, minus the `result` text, in the step file
  - write the retrospective summary before the retrospective step
- `orchestrator/src/sdlc/adapters/real.py` -- preflight warns when a settings-file `env` block sets telemetry switches.
- `sdlc/pipeline.yaml` -- `agents`, `agent_role` on every agent and judge step, `usage_warn_ratio: 0.8`, `telemetry: {enabled: false}`, and `sdlc/**` in the paths that learn branches revert.
- `sdlc/prompts/retrospective.md` -- name `.sdlc/usage-summary.md`, and ask for the model/effort/max_turns recommendations as action items.
- `orchestrator/tests/fixtures/legacy/state-pre-ad24.json` -- the legacy state snapshot, already present.

## Tasks & Acceptance

**Execution:**
- [ ] `core/models.py`, `core/pipeline.py` -- the new fields, the role map and its validation.
- [ ] `adapters/claude.py` -- result parsing from `modelUsage`; the AD-25 env.
- [ ] `core/state.py`, `core/usage.py` -- the records, backfill, the warning, the aggregation and the summary.
- [ ] `store.py` -- the ledger projection, reconcile and rebuild.
- [ ] `app/loop.py`, `cli.py`, `adapters/real.py` -- the raw step file, the retro summary, `sdlc usage`, the digest, status totals, the preflight warning.
- [ ] `sdlc/pipeline.yaml`, `sdlc/prompts/retrospective.md` -- roles, settings, the summary in the prompt.
- [ ] `orchestrator/tests/` -- one test per matrix row (`test_usage_<row>`), plus the per-run invariant that the ledger cost sum equals `budget.spent`.
- [ ] `AGENTS.md` -- `sdlc usage` and the telemetry setting, in two lines.

**Acceptance Criteria:**
- AC-1: Given `uv run sdlc run --dry-run`, when `sdlc usage <run> --by agent --runs-dir <tmp>` runs, then every role that ran is listed and the total equals the run's spent.
- AC-2: Given the legacy fixture, when it is loaded with the new code and pipeline, then it loads, gets backfilled records, and its digest check passes.
- AC-3: Given the repo, when `uv run pytest -q` and `uv run ruff check && uv run ruff format --check` run in `orchestrator/`, then all pass, with the live test skipped.

## Implementation Notes

## Spec Change Log

## Review Triage Log

Pass 1 (2026-11-17). Reviewers: BH = Blind Hunter, VG = Verification Gap, EC = Edge Case Hunter.

| # | Finding | Verdict | Evidence | Route |
|---|---|---|---|---|
| 1 | BH1: no ADR or spine change in the patch | false | ADR 0019 and AD-24/AD-25 are committed in `928a607` and `326f46e`, before the baseline; the diff excluded `docs/adr` | reject |
| 2 | BH2: legacy fixture missing | false | `tests/fixtures/legacy/state-pre-ad24.json` is committed at the baseline `3fafbf8`; the diff excluded it | reject |
| 3 | BH3, EC3, VG-o3: `status` and `usage` write the ledger and race a live run; `--rebuild` can drop rows | high | `_load_run` → `RunStore.load` → `reconcile` appends; no lock | patch (reports read state; only the run appends; rebuild refuses while a run is running) |
| 4 | BH4: ledger rows are snapshots at finish time | medium | Rows keep their first values under "first wins" | patch (covered by row 3: reports read state) |
| 5 | BH5, EC11, VG4: `model` is an alias while `--by model` groups by ids; by-model totals differ from spent | medium | `_usage_record` uses `stage.model or default_model` | patch |
| 6 | BH6: `_pipeline_for` docstring is wrong | low | It loads the current file | patch (docstring) |
| 7 | BH7, EC7, EC8, EC9, VG1, VG2: telemetry not metrics-only; per-signal overrides win; dead `exporter` field; warning gaps; wiring untested | medium | `agent_env` keeps inherited otlp logs and traces exporters | patch |
| 8 | BH8, EC14: retro summary parse can raise; epic scope excluded; partial without saying so | medium | `int(subject.rsplit(...))`; `epic_records` filter | patch |
| 9 | BH9: `sdlc/**` revert is silent | medium | No event, no prompt mention for the keeper | patch |
| 10 | BH10: `raw` bloats events and state | medium | `StepResult.raw` travels in `StepFinished` | patch |
| 11 | BH11, VG-o1: dead `is_measured`; `_key` lacks the model branch | low | Direct correction | patch |
| 12 | BH12, EC5: CLI gaps (`--all` with a run id, unknown run checked after rebuild, wording) | low | Direct correction | patch |
| 13 | BH13: docs incomplete | low | AGENTS.md, pipeline header | patch |
| 14 | BH14a: the warning does not re-arm after the budget is lowered and raised back | low | Needs an owner lowering then restoring the same value; the fix adds state | reject |
| 15 | BH14b, VG3, VG5: tests missing (status line, by model, protocol, empty roster, digest, removed step) | medium | Pre-verified by VG | patch |
| 16 | EC1, EC2: a ledger write or read failure crashes the run | high | OSError or UnicodeDecodeError in `reconcile`/`read_ledger` | patch |
| 17 | EC4: one corrupt state breaks `--all` and `--rebuild` | medium | No per-run guard | patch |
| 18 | EC6: `Infinity` raises OverflowError | low | Direct correction | patch |
| 19 | EC10: `error_orchestrator` steps get a record | medium | No model ran | patch |
| 20 | EC12: a judge step with a skill is rejected | low | `Step.runner` returns the skill | patch |
| 21 | EC13: a legacy custom pipeline without roles cannot resume | low | Only for custom `--pipeline` runs; better error message | patch (message) |
| 22 | VG-o2: `test_real_no_required_checks` is timing-sensitive | medium | Pre-existing, not caused by this change | defer |

## Verification

**Commands:**
- `cd orchestrator && uv run pytest -q` -- expected: all pass, live skipped
- `cd orchestrator && uv run ruff check && uv run ruff format --check` -- expected: clean
- `cd orchestrator && uv run sdlc run --dry-run; echo $?` -- expected: 0
