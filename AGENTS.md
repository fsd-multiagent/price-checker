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
  - `uv run sdlc status [<run-id>]` and `uv run sdlc resume <run-id>`; pass `--runs-dir <dir>` for a dry-run's temporary dir. A non-dry `sdlc run` exits 1 until the real adapters exist (`_bmad-output/implementation-artifacts/deferred-work.md`).
  - The stage table is `sdlc/pipeline.yaml`; prompts are `sdlc/prompts/*.md`; dry-run fixtures are `orchestrator/tests/fixtures/dryrun/`.
- TODO (stage 4): backend (Java) and web (React) build, test and lint commands. Zamolxe (Orchestrator) runs exactly these at the "tests pass" gate (T-8).

## Conventions that differ from defaults

- Refer to agents as Name (Position), for example "Marghioala (Developer)" (D-6). Names: Pompiliu (Analyst), Onofrei (PM), Safta (UX Designer), Veronel (Security & Compliance), Loredan (Architect), Pacala (DevOps), Marghioala (Developer), Eufrosina (QA), Filofteia (Code Reviewer), Aglaia (Knowledge Keeper), Zamolxe (Orchestrator).

## Known pitfalls

- The guard reads every Bash command as text, heredoc bodies included. Inline code (`python3 -c`, `node -e`) that mentions a retailer domain, or a heredoc whose text contains a blocked command such as `terraform apply`, is denied even though nothing would run. Write the content with the Write tool, or put code in a script file and run that.

<!-- /bmad:context -->
