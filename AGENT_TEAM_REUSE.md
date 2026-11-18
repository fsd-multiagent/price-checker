# Reusing the agent team on another project

This guide explains how to take the agent team built for Price Checker, together with Zamolxe (Orchestrator), and run it on a different project.

- **Written by:** Loredan (Architect), 2026-11-17
- **Applies to:** the state of `main` at tag `phase-2-setup`, plus PR #3

Two routes exist:

| Route | Effort | When to use it |
|---|---|---|
| **A. Copy into a new repo** (this guide) | About an hour of setup, plus writing the new spec and rules | Works today. Later improvements here do not reach the copy |
| **B. A reusable BMAD module** | More effort up front, with `bmad-module-builder` | Planned in `docs/automation-plan.md` §10, after phase 1 has run end to end. One update reaches every project |

## 1. What the team is

| Part | What it does | Where |
|---|---|---|
| The BMAD agents | Pompiliu (Analyst), Onofrei (PM), Safta (UX Designer), Loredan (Architect), Marghioala (Developer), Eufrosina (QA) | BMAD install, plus `_bmad/custom/*.toml` |
| The custom agents | Veronel (Security & Compliance), Pacala (DevOps), Filofteia (Code Reviewer), Aglaia (Knowledge Keeper) | `.claude/agents/*.md` |
| Zamolxe (Orchestrator) | Runs the stages, checks the gates, opens PRs, escalates | `orchestrator/`, `sdlc/pipeline.yaml`, `sdlc/prompts/` |
| Guardrails | Block risky actions by agents (G1–G5) | `.claude/settings.json`, `.claude/hooks/guard.py` |
| Standards | The rules the agents follow and check, with IDs | `docs/standards/` |
| Decisions | Why the factory works the way it does | `docs/adr/0001`–`0005`, the orchestrator spine |
| CI | The required `ci` check on `main` | `.github/workflows/ci.yml` |

## 2. What to copy, and what to change

### 2.1 Copy unchanged

| Path | Notes |
|---|---|
| `orchestrator/` | Code, tests and `uv.lock`. Do not copy `.venv/` or the caches |
| `sdlc/prompts/` except `dev.md` and `infra.md` | Generic |
| `_bmad/custom/*.toml` except `config.user.toml` | `config.user.toml` holds personal settings; each user creates their own |
| `.claude/agents/devops-release.md`, `.claude/agents/knowledge-keeper.md` | Light project wording; check them, but they work as they are |
| `.claude/settings.json` | Deny rules and hook wiring |
| `.claude/hooks/test_guard.py` | Update it together with `guard.py` (section 2.2) |
| `.github/workflows/ci.yml` | Add the new product's build and test jobs later, in stage 6 |
| `scripts/apply-persona-names.py` | Restores the agent names after a BMAD install or update |
| `docs/standards/README.md`, `review-findings.md`, `documentation.md` | Generic |
| `docs/adr/0000-template.md`, `docs/adr/0001`–`0005` | The orchestrator's decisions. Start the new project's own ADRs at `0006`, or renumber and keep an index |
| `_bmad-output/planning-artifacts/architecture/architecture-orchestrator-2026-11-16/` | The orchestrator spine: the design that the code follows |
| `.gitignore` | Keep the lines for `.sdlc/`, `_bmad-output/sdlc/`, `.env*`, `*.tfstate*` and `*.tfvars` |

### 2.2 Copy, then rewrite for the new project

| Path | What to change |
|---|---|
| `docs/initial-spec.md` | **The main input.** Write the new product's spec. Stage 1 starts from it |
| `AGENTS.md` | Project description, the paths under "Where things are", and the `sdlc-commands` block. Keep `tests` for the orchestrator until stage 4 adds the product's commands |
| `docs/standards/coding.md` | Keep C-1–C-4, C-10 and C-11. Replace C-5–C-9, C-12 and the Java/React sections with the new product's rules, or mark them TBD for Loredan (Architect) at stage 4 |
| `docs/standards/testing.md` | Keep T-1, T-4–T-6 and T-8. Replace T-2, T-3 and T-7 |
| `docs/standards/security-compliance.md` | Keep S-9–S-13. Replace S-1–S-8 (retailer fetching) with the new product's rules for external systems, or remove them |
| `docs/standards/infrastructure.md` | It assumes one Azure VM. Rewrite it for the new target |
| `docs/source-register.md` | The list of external systems the product may contact, each with a `Status`. Only a human sets `Approved`. Start it empty if the product contacts nothing external |
| `docs/compliance-register.md` | Start empty |
| `.claude/hooks/guard.py` | G1 (terraform), G2 (`main`) and G5 (the register and guard files) are generic. G3 names this project's VM, and G4 lists the retailer domains. Change both, then run `python3 .claude/hooks/test_guard.py` |
| `.claude/agents/security-compliance-reviewer.md` | Its checks name the retailer rules. Point them at the new S- rules |
| `.claude/agents/code-reviewer.md` | Same: point it at the new C- and T- rules |
| `sdlc/prompts/dev.md`, `sdlc/prompts/infra.md` | One retailer or Azure mention each |
| `sdlc/pipeline.yaml` | `run_budget_usd`, `max_parallel`, and per-stage `model` and `effort` (section 5) |
| `docs/automation-plan.md` | Optional. Copy it as the method description and replace the product decisions D1–D5 |

### 2.3 Do not copy

- `_bmad-output/` except the orchestrator spine. The brief, the specs and the run state belong to Price Checker.
- `docs/archive/`.
- `.idea/`, `.DS_Store`, `__pycache__/`, `.venv/`.
- Any `.env*` or Terraform state file.

## 3. Step by step

1. **Create the repo** on GitHub. Make it public, or use a paid plan: auto-merge and branch rules are not available for private repos on GitHub Free.
2. **Install BMAD** with the same modules as here (core, bmm, tea, cis, bmb).
3. **Copy** the files in sections 2.1 and 2.2.
4. **Restore the agent names:** `python3 scripts/apply-persona-names.py`. The `--check` option reports only.
5. **Rewrite** the files in section 2.2, and write the new spec.
6. **Check the guardrails:** `python3 .claude/hooks/test_guard.py`.
7. **Check the orchestrator:**
   - `cd orchestrator && uv run pytest -q`
   - `uv run sdlc run --dry-run` (exit 0)
8. **Push** the first commit yourself; the guard blocks agents from pushing to `main`.
9. **Set up the repo:**
   - Settings → General: turn on **Allow auto-merge**.
   - Settings → Branches → **Add branch ruleset**, targeting the default branch:
     - **Restrict deletions** and **Block force pushes** on
     - **Require a pull request**, with **0** required approvals
     - **Require status checks to pass**, with the `ci` check
     - **"Require branches to be up to date" off**
10. **Check the Claude login:**
    - Keep "extra usage" credits off on the account, so a plan limit pauses the run instead of drawing paid credits.
    - Remove any `ANTHROPIC_API_KEY` from the environment, or set `allow_api_key: true` on purpose (ADR 0005).
11. **Optional live check** (about $0.05 of usage): `cd orchestrator && SDLC_LIVE=1 uv run pytest -q -m live`.
12. **Plan for the gate commands before any story merges.** The `tests` and `terraform_plan` gates run only what the `sdlc-commands` block in `AGENTS.md` lists, and only an owner PR can change that block (ADR 0004, ADR 0021). The product's commands do not exist yet when the run starts, because the stack is chosen in stage 4. So stop after the planning PR (stage 5), add the product's build, test and lint commands to the block through an owner PR, and only then let stories run.
    - Add a command only once the code it runs is on `main`. For example, the web commands came after the web scaffold merged.
    - Price Checker learned this the hard way: its first stories merged while the gate ran only the orchestrator's tests (fixed by PRs #12 and #17).
13. **Start the run** in a separate terminal: `cd orchestrator && uv run sdlc run`. Watch it with `uv run sdlc status`.

## 4. Your role during a run

You act only at three points (`docs/automation-plan.md` §6):

| Point | What you do |
|---|---|
| Approving an external system | Set its `Status` to `Approved` in `docs/source-register.md`. Stories that use it wait until then |
| Infrastructure | Run `terraform apply` yourself after stage 6. Agents only run `plan` |
| Escalations | Read the GitHub issue labelled `escalation`, fix the input (spec, story, config or register), then run `uv run sdlc resume <run-id>` |

## 5. Keeping usage down

On a Claude subscription, the dollar amounts the orchestrator shows are estimates, not charges. The real limits are the plan's 5-hour and weekly windows (ADR 0005).

| Lever | Where |
|---|---|
| A cheaper model for simple stages: Opus for architecture, Sonnet for most stages, Haiku for read-only reviewers if their quality holds | `model` per stage, or `default_model` in `sdlc/pipeline.yaml` |
| Lower effort on routine steps | `effort` per stage, or `default_effort` |
| A cap on turns per step | `max_turns` per stage |
| Fewer parallel stories, so one run does not use up a whole 5-hour window | `max_parallel` |
| A ceiling on one run | `run_budget_usd` |
| Short project instructions (`AGENTS.md` under about 200 lines), and prompts that name only the files a step needs | `AGENTS.md`, `sdlc/prompts/` |

## 6. Known limits

- **Not built yet:** stopping a run partway (`--until`), the run lock and trigger queue, and runs started from GitHub issues. See `_bmad-output/implementation-artifacts/deferred-work.md`.
- **Still to tune:** the usage-limit message patterns are best guesses until a real limit has been seen.
- **One stack assumed:** the infrastructure stage and standards assume one VM with Docker Compose and Terraform. Other targets need the files in section 2.2 rewritten.
