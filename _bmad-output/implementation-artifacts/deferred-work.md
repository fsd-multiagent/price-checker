# Deferred work

- source_spec: none
  summary: Real adapters for Zamolxe (Orchestrator) — claude (claude -p invocation and JSON parsing, AD-8), git (worktrees, reset, rebase, push refusal to main, AD-6/AD-12) and gh (PRs, auto-merge, checks, escalation issues, AD-13/AD-20).
  evidence: Split from the orchestrator build intent on 2026-11-16; step 0.8 acceptance (`sdlc run --dry-run`) needs only fake adapters (AD-2), and the real adapters are independently shippable behind the ports.
- source_spec: none
  summary: Run admission for Zamolxe (Orchestrator) — repo lock file, trigger queue, and issue-started `sdlc story|fix <issue>` runs (AD-22).
  evidence: Split from the orchestrator build intent on 2026-11-16; only needed once GitHub Actions triggers exist (step 0.9).
- source_spec: none
  summary: Desktop notifier adapter for escalations (macOS osascript, no-op elsewhere; AD-14).
  evidence: Split from the orchestrator build intent on 2026-11-16; the dry-run uses the fake notifier.
- source_spec: _bmad-output/implementation-artifacts/spec-orchestrator-dry-run.md
  summary: Before opening a story PR, check the files the diff actually touches against `serial_paths` (AD-15, last bullet). Needs a real `git diff --name-only`.
  evidence: Found while building the dry-run; the fake git has no real diff, so only the declared `touches` are checked today.
- source_spec: _bmad-output/implementation-artifacts/spec-orchestrator-dry-run.md
  summary: Record the orchestrator's own git operations (leftover commit, rebase, push) with their before and after SHAs as `script` steps or events (AD-12). Today the leftover commit runs inline after a writing step ends `done`.
  evidence: Found while building the dry-run; only matters once the real git adapter exists.
- source_spec: _bmad-output/implementation-artifacts/spec-orchestrator-dry-run.md
  summary: A scope that escalates again after `sdlc resume` reuses its escalation issue (marker match, AD-13) but posts nothing new. Add a comment on reuse.
  evidence: Found while building the dry-run.
- source_spec: _bmad-output/implementation-artifacts/spec-orchestrator-dry-run.md
  summary: An `on_fail` that points to an earlier run-level stage resets only the failing scope's worktree; commits made by the stages in between stay on the planning branch.
  evidence: Found while building the dry-run; the current pipeline only rewinds within a stage or from stage 8 to stage 7 on the same story branch.
- source_spec: `_bmad-output/implementation-artifacts/spec-orchestrator-dry-run.md`
  summary: A stage that passes again after an on_fail rewind re-creates its tag `sdlc/<run-id>/<stage>`; the real git adapter must move the tag or use a round-qualified name (AD-13).
  evidence: Edge Case Hunter, triage row 38; only matters for the real git adapter, the fake ignores duplicates.
- source_spec: `_bmad-output/implementation-artifacts/spec-orchestrator-dry-run.md`
  summary: Dry-run resume may lose files the fake agents wrote into the in-memory FakeFs (for example epics.md), so a resumed gate could fail spuriously. Unverified, medium if true.
  evidence: Edge Case Hunter, triage row 41. To settle: crash a dry-run after the breakdown stage, then resume it and check whether the build gate finds the stories file.
- source_spec: `_bmad-output/implementation-artifacts/spec-orchestrator-dry-run.md`
  summary: Cache the `origin/main` register read by blob SHA and throttle the fetch, instead of one `git show` per loop tick.
  evidence: Blind Hunter, triage row 5; a performance concern for the real git adapter only.
- source_spec: none
  summary: Add `sdlc run --until <stage-id>` so a run can stop after a chosen stage (for example planning only, stages 1–5), mirroring `--from`; the stopped run is resumable and can be continued with `--from`.
  evidence: Requested by the owner on 2026-11-16 while reviewing docs/automation-plan.md §4.6; today a run can only start partway, not stop partway.
- source_spec: `_bmad-output/implementation-artifacts/spec-orchestrator-real-adapters.md`
  summary: Preflight should check the repo's auto-merge setting and required checks on `main` through the GitHub API, and whether `claude` is logged in, before a paid step runs.
  evidence: Blind Hunter, triage row 15; today these fail only after the first paid `claude -p` call.
- source_spec: `_bmad-output/implementation-artifacts/spec-orchestrator-real-adapters.md`
  summary: Cap captured stdout/stderr in `run_proc` by bytes while reading, instead of clipping after capture.
  evidence: Edge Case Hunter, triage row 23; agent output is small today.
- source_spec: `_bmad-output/implementation-artifacts/spec-orchestrator-real-adapters.md`
  summary: Resolved by this spec, to be closed by Aglaia (Knowledge Keeper): real adapters (entry 1), the desktop notifier (entry 3), the comment on a reused escalation issue (adapter side only; the core still skips a second escalation), and moving a re-passed tag (triage row 38 of the dry-run spec).
  evidence: Delivered on `feat/orchestrator-real-adapters`; existing entries are not edited by Build.
- source_spec: none
  summary: Usage visibility in Zamolxe (Orchestrator) — `sdlc status` shows the estimated cost per stage and per step (and per model from the result's `modelUsage`), and warns once (status line plus notification) when spent + reserved passes 80% of `run_budget_usd`.
  evidence: Owner decision 2026-11-17. Phase 1 run 20261117-174705-full had used $10.22 of $50 by stage 4 (requirements took 3 rounds); nothing showed where the usage went or warned before the ceiling. Today only the run total is shown.
- source_spec: none
  summary: Usage review in the epic retrospective — the orchestrator passes the run's per-stage usage (cost, rounds, model, effort) to Aglaia (Knowledge Keeper)'s `bmad-retrospective` step, which recommends `model`/`effort`/`max_turns` changes in `sdlc/pipeline.yaml` as action items.
  evidence: Owner decision 2026-11-17. No agent owns token consumption; the retrospective prompt (`sdlc/prompts/retrospective.md`) gets no cost data. Depends on the usage-visibility entry above.
- source_spec: `_bmad-output/implementation-artifacts/spec-orchestrator-usage-ledger.md`
  summary: Make `tests/test_real_adapters.py::test_real_no_required_checks` deterministic; it polls with a 0.01 s deadline and asserts more than one poll, so it fails now and then under load.
  evidence: Verification Gap review of the usage-ledger build, triage row 22; failed once in a full run, passed on reruns; pre-existing.
- source_spec: `_bmad-output/implementation-artifacts/spec-gate-commands-owner-only.md`
  summary: Non-story agent branches (planning, infra, learn) can still change what a gate command does without touching the `sdlc-commands` block, by editing `orchestrator/` (including the ADR 0021 guard), the test configuration or `.github/workflows/`, and their PRs auto-merge. Revert those paths on non-story branches, or accept the code-owner switch and its escalation cost.
  evidence: Blind Hunter review of the gate guard; ADR 0021, Consequences, "Remaining gap".
