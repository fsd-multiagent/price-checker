<!-- bmad:context -->
<!-- Verified 2026-11-16 against a6764a2. Managed by bmad-project-context; edits inside this block are replaced on refresh. Keep anything you want preserved outside the markers. -->

## price-checker

A learning project for an automated multi-agent SDLC built on BMAD; the app (enter an EAN, compare prices across approved Romanian retailers, React + Java on one Azure VM) is the pretext. When a product question is ambiguous, choose the simplest option that keeps the demo working. The plan is `docs/automation-plan.md`; the spec is `docs/initial-spec.md`; BMAD outputs go to `_bmad-output/`.

## Policy

- Follow the rules in `docs/standards/` and cite their IDs (C-, T-, S-, I-, D-) in findings and commits.
- Never approve a retailer, run `terraform apply`/`destroy`, push or merge to `main`, or open SSH to the VM. The guard hook blocks these; escalate to the owner instead.
- Never contact a retailer domain unless `docs/source-register.md` marks it `Approved`. To propose one, set its Status to `Proposed`.
- Never edit `.claude/settings*.json`, `.claude/hooks/` or `docs/source-register.md` Status cells to get past a block; ask the owner.
- Never read `.env*`, `*.tfstate*`, `*.tfvars`, `~/.ssh` or `~/.azure`.
- Every architecture decision gets an ADR from `docs/adr/0000-template.md`, added to the index in `docs/adr/README.md` (D-2).

## Where things are

- Standards and who verifies each: `docs/standards/README.md`
- Review verdict JSON format: `docs/standards/review-findings.md`
- Custom agents: `.claude/agents/`; BMAD agent overrides: `_bmad/custom/*.toml`
- Guardrails: `.claude/settings.json`, `.claude/hooks/guard.py` (rules G1–G5 in `docs/automation-plan.md` §5)
- Registers: `docs/source-register.md`, `docs/compliance-register.md`

## Running and verifying

- Guard tests: `python3 .claude/hooks/test_guard.py`; run them after any change to `guard.py`.
- After every BMAD install or update, run `python3 scripts/apply-persona-names.py` (`--check` reports only); the update restores the default persona names.
- Zamolxe (Orchestrator), in `orchestrator/` (Python ≥ 3.12, uv):
  - Tests: `cd orchestrator && uv run pytest -q`
  - Lint and format: `cd orchestrator && uv run ruff check && uv run ruff format --check`
  - Dry-run of the whole stage table with fake adapters: `cd orchestrator && uv run sdlc run --dry-run` (exit 0; prints every `claude -p` it would start and a temporary run dir)
  - Real run: `cd orchestrator && uv run sdlc run` runs real `claude -p`, `git` and `gh` (paid). A preflight first checks that `claude`, `git` and `gh` are on PATH, `gh auth status` passes, `origin` exists and the main checkout is clean; it exits 1 naming what is missing. State goes to `_bmad-output/sdlc/runs`; worktrees go to `../price-checker-worktrees/`.
  - Real runs bill the Claude subscription: the preflight also exits 1 when `ANTHROPIC_API_KEY` or another API-key or provider credential is set (environment, or `env`/`apiKeyHelper` in a Claude settings file) unless `allow_api_key: true` (AD-23); keep "extra usage" credits off so a plan limit pauses the run instead of buying credits.
  - Settings in `sdlc/pipeline.yaml` for subscription runs: `default_model`, `default_effort` (per stage: `model`, `effort`, `max_turns`), `usage_limit_wait_min`, `usage_limit_max_wait_h`, `allow_api_key`. A plan usage limit pauses the run; while paused, `sdlc status` prints `plan usage limit: resume_at …` (also after an episode escalated the run).
  - Dry-run limit scenarios: `uv run sdlc run --dry-run --scenario usage-limit` (one pause, then exit 0) and `--scenario usage-limit-forever` (escalates with scope `run`, exit 2).
  - `uv run sdlc status [<run-id>]` and `uv run sdlc resume <run-id>`; pass `--runs-dir <dir>` for a dry-run's temporary dir.
  - `uv run sdlc usage [<run-id> | --all] [--by agent|stage|model|round|run] [--rebuild]` reports token use and cost from each run's `state.json` (read-only). The run itself appends to the ledger `<runs-dir>/usage.jsonl`; `--rebuild` regenerates it and refuses while a run is running (AD-24).
  - Usage settings in `sdlc/pipeline.yaml`: `agents` (role -> skills and agents it may run; every new agent or judge step needs an `agent_role` from it, and `Judge (gate check)` is the pseudo-role of gate judges), `usage_warn_ratio` (one warning at 80% of the run budget), `telemetry` (off; when on, OTLP metrics only to an http(s) endpoint, labelled `sdlc.agent` etc., with content logging always stripped, AD-25), and `learn_revert_paths` (learn branches revert `sdlc/**`, so pipeline changes go through an owner PR). Before an epic retrospective the orchestrator writes `<worktree>/.sdlc/usage-summary.md`.
  - Opt-in live test of one real `claude -p` call (about $0.05): `cd orchestrator && SDLC_LIVE=1 uv run pytest -q -m live`.
  - The stage table is `sdlc/pipeline.yaml`; prompts are `sdlc/prompts/*.md`; dry-run fixtures are `orchestrator/tests/fixtures/dryrun/`.
- Commands the orchestrator's `tests` and `terraform_plan` gates run, from the worktree root (T-8). Each is split like a shell word list and run without a shell, so no `cd`, `&&`, pipes or redirections, and nothing the guard hook denies; a missing name fails the gate. The gate reads this block from `origin/main` at the story's branch point and fails when the story itself changed it (ADR 0023), and before each of its commits on a planning, infra or learn branch the orchestrator puts the block back as it is on `origin/main` (story branches revert all of `AGENTS.md`), so agents cannot change it through their auto-merged PRs (ADR 0004, ADR 0021).

  ```yaml sdlc-commands
  tests:
    - uv run --directory orchestrator pytest -q
    - backend/gradlew -p backend check
    - npm --prefix web ci
    - npm --prefix web run check
  terraform_plan:
    - terraform -chdir=infra fmt -check -recursive
    - terraform -chdir=infra init -backend=false -input=false
    - terraform -chdir=infra validate
    - terraform -chdir=infra test
  ```

  - `backend/gradlew -p backend check` runs the backend's format check (Spotless), compile, tests and architecture rules (ArchUnit) (ADR 0014). It needs a JDK on the runner; Gradle provisions JDK 21 through the toolchain.
  - The `terraform_plan` gate runs offline: formatting, validity, and `infra/tests/*.tftest.hcl` against a mocked provider. No Azure access is needed. The real `terraform plan` runs in `.github/workflows/terraform-plan.yml` once the owner sets the `AZURE_*` and `TF_STATE_*` repository variables.

- `npm --prefix web ci` installs the locked web dependencies and `npm --prefix web run check` runs ESLint, Prettier, the TypeScript check and Vitest (ADR 0013, ADR 0014). They need Node 24 on the runner (`web/package.json` `engines`).

## Conventions that differ from defaults

- Refer to agents as Name (Position), for example "Marghioala (Developer)" (D-6). Names: Pompiliu (Analyst), Onofrei (PM), Safta (UX Designer), Veronel (Security & Compliance), Loredan (Architect), Pacala (DevOps), Marghioala (Developer), Eufrosina (QA), Filofteia (Code Reviewer), Aglaia (Knowledge Keeper), Zamolxe (Orchestrator).

## Known pitfalls

- The guard reads every Bash command as text, heredoc bodies included. Inline code (`python3 -c`, `node -e`) that mentions a retailer domain, or a heredoc whose text contains a blocked command such as `terraform apply`, is denied even though nothing would run. Write the content with the Write tool, or put code in a script file and run that.

<!-- /bmad:context -->
