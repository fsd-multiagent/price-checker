# Automated SDLC plan: price-checker

- **Owner:** Adrian
- **Drafted by:** Pompiliu (Analyst), 2026-11-12
- **Status:** draft (v3: custom orchestrator, no extra human gates)

**Purpose:** learn and demonstrate an end-to-end SDLC in which specialized agents run each stage. A **custom orchestrator** starts and coordinates the agents and checks every gate. Price Checker is the pretext: a web page that compares prices for an EAN, running on one Azure VM (see `docs/initial-spec.md` v2). It is kept deliberately small, so the effort goes into orchestration. When a product question is ambiguous, choose the simplest option that keeps the demo working.

---

## 1. Decisions

| # | Decision |
|---|---|
| O1 | **A custom orchestrator**, not bmad-loop. bmad-loop runs one story at a time, has a fixed review step and opens no PRs, which doesn't fit the stage table. BMAD skills are used as the agents' workflows. |
| O2 | **No human approval gates between stages.** The orchestrator checks every gate. A human is involved only at the three points in §6. |
| D1 | Product scope is spec v2: enter an EAN and compare prices across at least 2 approved retailers. It runs on one Azure VM with Docker Compose, and has no database, accounts or alerts. |
| D2 | eMAG is dropped because its terms (clause 4.2(j)) ban automated extraction. The candidates are evoMAG, cel.ro, Altex and PC Garage. Each must pass the compliance gate and be searchable by EAN. |
| D3 | If the live demo fails on stage, fall back to a pre-recorded video. |
| D4 | Self-healing: a scheduled health check fails, opens an issue, and the issue starts a fix run (§5.3). |
| D5 | How to look up an EAN at each retailer is left to the Architect, to test how the pipeline handles a real open question. |

## 2. The stages

| Stage | Agents | Runs | Gate before the next stage (checked by the orchestrator) |
|---|---|---|---|
| 1. Analysis | Analyst | Alone | The product brief exists and covers the spec |
| 2. Requirements | PM | Alone | The PRD exists, and every requirement has acceptance criteria |
| 3. Design | UX Designer, and Security & Compliance (reviews the PRD) | **In parallel** | Both documents exist and have no blocker findings |
| 4. Architecture | Architect, then Security reviews it | In sequence | No blockers. Otherwise back to the Architect |
| 5. Breakdown | PM (epics and stories), then Architect (readiness check) | In sequence | Readiness passes. Otherwise back to the PM |
| 6. Infrastructure | DevOps (Terraform, CI/CD) | Alone, or alongside the first stories | `terraform plan` succeeds |
| 7. Build, per story | Developer → QA tests → Code Reviewer and Security (in parallel) → fixes | Independent stories **in parallel**, each in its own git worktree | Tests pass and there are no blocker findings, within N fix rounds. Otherwise escalate |
| 8. Integrate | CI, then merge | Per story | CI is green |
| 9. Learn | Knowledge Keeper after each story; retrospective after each epic | Alone | Docs and registers are updated |

**What sends work back.** A failed gate sends the work back to the stage named in the table. After `max_rounds` attempts (3 by default), the orchestrator escalates. Each loop is bounded, so the pipeline never retries forever.

## 3. The agents

| # | Name | Position | Implemented by | Source | Stages |
|---|---|---|---|---|---|
| 1 | 📊 **Pompiliu** | Analyst | `bmad-product-brief -H` | BMAD | 1 |
| 2 | 📋 **Onofrei** | PM | `bmad-prd -H`; the gate uses `bmad-prd` in validate mode. Epics and stories via `bmad-create-epics-and-stories` (see §8) | BMAD | 2, 5 |
| 3 | 🎨 **Safta** | UX Designer | `bmad-ux` → `DESIGN.md`, `EXPERIENCE.md` | BMAD | 3 |
| 4 | 🛡️ **Veronel** | Security & Compliance | `security-compliance-reviewer`. Read-only: checks fetching limits, the source register and privacy. Drafts rows for the register but never approves them | **Custom** | 3, 4, 7 |
| 5 | 🏗️ **Loredan** | Architect | `bmad-architecture` → architecture and ADRs; readiness check via `bmad-sprint-planning` | BMAD | 4, 5 |
| 6 | ⚙️ **Pacala** | DevOps | `devops-release`. Terraform for the VM, Docker Compose, GitHub Actions and the health-check job. Runs `plan` only, never `apply` | **Custom** | 6 |
| 7 | 💻 **Marghioala** | Developer | `bmad-build-auto`, one story per worktree. Also does the self-healing fixes | BMAD | 7 |
| 8 | 🧪 **Eufrosina** | QA | `bmad-testarch-atdd` / `bmad-testarch-automate`: tests from the acceptance criteria | BMAD TEA | 7 |
| 9 | 🔍 **Filofteia** | Code Reviewer | `bmad-code-review`, plus a custom `code-reviewer` lens for project conventions | BMAD + **custom** | 7 |
| 10 | 📚 **Aglaia** | Knowledge Keeper | `knowledge-keeper`. Maintains docs, the ADR index, the registers and `bmad-reference.md`. Runs `bmad-retrospective -H` for each epic | **Custom** + BMAD | 9 |
| — | 🎼 **Zamolxe** | Orchestrator (not an LLM agent) | `orchestrator/` (§4). Runs the stages, checks gates and escalates | **Custom** code | all |

Names chosen by the owner, 2026-11-12. BMAD personas are renamed through `_bmad/custom/<skill>.toml` (`agent.name`) in step 0.3.

**Not agents:** CI (GitHub Actions).

**Not used:** the CIS creative agents, BMad Builder and bmad-loop. Caravaggio may be used later to build the talk's slides.

## 4. The orchestrator

### 4.1 Shape

- A Python program in `orchestrator/`, run from the command line, for example `sdlc run`, `sdlc status`, `sdlc resume <id>`.
- Each agent step is a **fresh, headless `claude -p` process**. It gets the role's skill or subagent, a scoped tool allowlist, and a prompt that names its input and output files. The Claude Agent SDK is an upgrade path if more control is needed.
- **How each step is run (from step 0.7).**
  - Command: `claude -p --output-format json --max-budget-usd <cap> --no-session-persistence --allowedTools <role list>`, with the prompt on stdin. Add `--agent <name>` for a custom agent. The prompt starts with the skill call and `-H`, for example `/bmad-prd -H …`.
  - Read the JSON result: `subtype`, `is_error`, `total_cost_usd`, `num_turns`, `permission_denials` and `result`. Log the denials; they show a wrong allowlist.
  - Put every input path and the verdict path **in the prompt**. Agents cannot read environment variables without Bash, and asking for one was denied in step 0.7.
  - Read-only reviewers return the verdict as the last fenced `json` block of `result`. The orchestrator parses it and saves it to the verdict path. If there is no valid verdict, the gate fails.
  - `bmad-build-auto` needs the story key and the path of `epics.md` in the prompt. Without them it stops with `blocked` and writes `bmad-build-auto-result-*.md`. It commits its own work and sets the story to `done` in `sprint-status.yaml`.
  - Timeouts per stage, not one for the whole run. Most skills took under 90 s on the toy spec, but `bmad-architecture` took 22 minutes and `bmad-build-auto` took 5 minutes per story. Start with 60 minutes for architecture and 30 for a story.
  - Auth: a browser sign-in expires. Unattended runs use a long-lived token from `claude setup-token`, stored on the runner.
- **The pipeline is data.** `sdlc/pipeline.yaml` encodes the stage table: agents, whether they run in parallel or in sequence, the gate, where a failure goes back to, and `max_rounds`. Changing the process is a YAML edit, not a code change.
- **State on disk.** Everything lives in `_bmad-output/sdlc/runs/<run-id>/`:
  - `state.json`
  - one log per agent step
  - the verdicts
  - escalations

  A run can be resumed after a crash.
- **Git is the record.** Every stage ends in a commit. Planning stages commit to a `sdlc/<run-id>` branch, which is merged through a PR once stage 5 passes. Each story has its own branch and PR. Each stage leaves a tag (§7).

### 4.2 Gate checks

Gates are checked deterministically wherever possible. Otherwise a separate **judge call** returns a JSON verdict (`pass | fail`, plus findings with a severity). The agent that produced the work never judges its own work.

| Gate | Check |
|---|---|
| Brief covers the spec | The file exists. A judge maps each spec section to the brief and reports any section with no matching content |
| Every requirement has acceptance criteria | `bmad-prd` in validate mode passes. A script finds every FR row and confirms it has acceptance criteria |
| No blocker findings | Reviewer output is JSON with a severity on each finding. Any finding marked `blocker` fails the gate |
| Readiness passes | `bmad-sprint-planning` returns PASS. CONCERNS counts as a pass but is logged; FAIL sends the work back to the PM |
| `terraform plan` succeeds | The exit code of `terraform init && validate && plan` |
| Tests pass | The project's test commands exit 0 inside the story's worktree |
| CI green | The PR's checks pass, read with `gh pr checks`. The PR then auto-merges |
| Docs updated | Knowledge Keeper's diff touches the expected files, or it records "no update needed" with a reason |

### 4.3 Parallel stories (stage 7)

- **Dependencies.** The stage 5 breakdown must give each story a `depends_on` list. The orchestrator builds a dependency graph from those lists and starts every story whose dependencies are merged.
- **Concurrency.** Up to `max_parallel` stories at once (default 3). Each runs in its own `git worktree` on the branch `story/<key>`.
- **Steps within a story:**
  1. Developer
  2. QA
  3. Code Reviewer and Security, in parallel
  4. On a blocker, a Developer fix round
  5. Repeat steps 2–4, up to N rounds
  6. Open a PR; CI runs; auto-merge
- **Merge conflicts.** The orchestrator rebases the story branch. If the rebase fails, the Developer gets one fix round; if that fails too, the story is escalated.
- **Lock on shared files.** Stories that edit the shared contract `api/openapi.yaml` are run one at a time.

### 4.4 Escalation

When a gate fails `max_rounds` times, or an agent reports a blocking ambiguity:

1. The orchestrator stops **only that branch of work**. Other parallel stories keep running.
2. It opens a GitHub issue labelled `escalation`, with the context, the gate verdicts and the log paths.
3. It sends you a desktop notification.

You fix the input (a spec, story or config), then run `sdlc resume <id>`.

### 4.5 Where it runs and what starts it

The orchestrator runs on a **self-hosted runner**: your Mac or a small separate VM. GitHub-hosted runners stop after 6 hours.

| Trigger | Starts |
|---|---|
| A push that changes `docs/initial-spec.md`, or a manual `workflow_dispatch` | A full run from stage 1 |
| An issue labelled `adapter-broken`, opened by the health check | A **fix run**: stage 7 → 8 → 9 for one generated story |
| An issue labelled `story`, written by hand or by an agent | A single-story run: stages 7 → 9 |
| A merge to `main` | CI deploys to the VM (only after the human `terraform apply` for infrastructure changes) |

Before Actions and the runner exist, `sdlc run` works the same way from a local terminal.

## 5. Guardrails

- **Per-role tool allowlists** in the agent definitions and `.claude/settings.json`:
  - Reviewers (Security & Compliance, Code Reviewer, judge calls) are read-only: Read, Grep and Glob, no Bash. They return their verdict in the final message (`docs/standards/review-findings.md`).
  - Roles that write (the BMAD planning skills, Developer, QA, DevOps) get plain `Bash`, not prefix rules such as `Bash(uv *)`. Agents chain commands (`cd … && …`, `W=… && mkdir … && uv run …`), and a prefix rule never matches those. In step 0.7 that caused 1 to 10 denials per run. The boundary for Bash is the settings deny rules and the guard hook. Knowledge Keeper edits docs and has no Bash.
  - Headless runs never get a blanket permission bypass. Step 0.7 confirmed that `--dangerously-skip-permissions` is ignored here: an unapproved write is still denied.
- **`PreToolUse` hooks** block:
  - `terraform apply`
  - pushes to `main`
  - SSH from agent sessions to the app VM
  - network fetches to any retailer not marked Approved in `docs/source-register.md`
- **Budgets.** Each run has a token and cost cap in `pipeline.yaml`, enforced by the orchestrator. The API key also has a spend cap.
- **Bounded loops.** `max_rounds` per gate and `max_parallel` for stories.
- **Audit.** Every agent action ends in a commit, a PR, a verdict file or an issue.

**How the guardrails are implemented (step 0.4).**
- `.claude/settings.json` turns off bypass mode, has deny rules (terraform apply/destroy, pushes to main, ssh/scp/sftp, and reading `.env`, tfstate, tfvars, `~/.ssh` and `~/.azure`), and makes edits to the guardrail files prompt. A headless run cannot answer a prompt, so those edits are denied.
- `.claude/hooks/guard.py` runs before every Bash, WebFetch, Edit/Write and MCP call. If the guard itself fails, the call is blocked. Its rules are G1 (terraform), G2 (main, `gh pr merge`), G3 (SSH to the VM), G4 (retailers not Approved; all known retailers are blocked until the register approves them) and G5 (agents cannot set a retailer to Approved, and Bash cannot modify the register or the guardrail files). Tests: `python3 .claude/hooks/test_guard.py`.
- **Register contract for step 0.5:** `docs/source-register.md` holds a Markdown table with `Domain` and `Status` columns. A retailer is approved only when its Status cell starts with `Approved`. Agents write `Proposed`.
- **Limits:** the guard parses commands; it does not sandbox them. A script file that fetches by itself is not seen; the shared fetch client (C-12, S-1) is the control in the code. The orchestrator is plain code, not a Claude session, so its own git operations are not hooked.
- **Initial push (step 0.9):** the guard also blocks Claude in an interactive session from pushing to `main`. The first push is done by the owner (`! git push -u origin main` in the prompt, which bypasses tool hooks).

## 6. Your human input

**Only three points during a run.** Everything else is gated by the orchestrator.

| Point | Why it stays with you | How the run waits |
|---|---|---|
| Approving a retailer in `docs/source-register.md` | It is a legal judgement | Adapter stories for retailers that are not approved stay blocked. Other stories continue |
| `terraform apply` | It changes real cloud resources and costs money | Stage 6 ends at a successful `plan`. Deploying waits until you apply |
| Escalations | A gate failed N times, or an agent hit a genuine ambiguity | That branch of work pauses until you run `sdlc resume` |

**Once, during setup:**
- Set the spend ceiling, `max_rounds` and `max_parallel`.
- Provide the accounts and secrets:

| Item | Needed by |
|---|---|
| A GitHub repo with Actions, branch protection on `main` (CI required), auto-merge enabled, and the Claude GitHub App | Phase 0 |
| An `ANTHROPIC_API_KEY` or Claude Code OAuth token, stored on the runner and as a repo secret | Phase 0 |
| A self-hosted runner machine | Phase 0 |
| An Azure subscription with an OIDC federated credential | Stage 6 |
| An SSH deploy key for the VM, as a repo secret | Stage 6 |
| Optionally, a DNS name for TLS | Stage 6 |
| A contact URL for the user agent | Adapter stories |

## 7. Plan of action

Phase 0 builds the team, so the team cannot run it. Claude (the main session) does it with BMAD's utility skills. The one exception is step 0.8, where the Architect and the Developer are run by hand to build the Orchestrator.

| # | Step | Done by | Tool / skill | You | Output |
|---|---|---|---|---|---|
| **0** | **Foundation** | | | | |
| 0.1 ✅ | Fix `docs/bmad-reference.md` to match this install and this design | Claude (later owned by the Knowledge Keeper) | Editing | Review | An accurate map |
| 0.2 ✅ | Create the 4 custom agents: Security & Compliance, DevOps, Code Reviewer, Knowledge Keeper | Claude | `bmad-agent-builder` or `.claude/agents/*.md` | Skim | `/agents` lists them |
| 0.3 ✅ | Customize the BMAD agents in `_bmad/custom/*.toml`: owner-chosen names, project rules, reviewer findings as JSON with severities, `depends_on` per story | Claude | `bmad-customize` | Review | Machine-checkable outputs |
| 0.4 ✅ | Guardrails: settings and hooks (§5) | Claude (later owned by DevOps) | `update-config` | Approve | Safe headless runs |
| 0.5 ✅ | Registers (source register, compliance register) and an ADR template | Claude (rows later reviewed by Security & Compliance) | Editing | Approve the retailer rows | The compliance gate |
| 0.6 ✅ | Agent instructions | Claude | `bmad-project-context` | Skim | `AGENTS.md` / `CLAUDE.md` |
| 0.7 ✅ | Smoke-test every skill headless, one at a time (§8) | Claude | `claude -p` | — | A list of what works without a chat |
| 0.8 | **Build the Orchestrator** (§4): `pipeline.yaml`, runner, gate checks, worktrees, escalation, resume | **Architect, then Developer**, run by hand (the Orchestrator cannot build itself) | `bmad-architecture` (a one-page design), then `bmad-build` | Watch, review | `sdlc run --dry-run` works |
| 0.9 | Push the repo; add the Actions workflows (CI, deploy, health check, trigger); register the runner | Claude | Editing, `gh` | Repo, secrets, runner | Tag `phase-2-setup` |
| **1** | **Stages 1–5, orchestrated** | Agents, started by the Orchestrator | `pipeline.yaml` | Escalations only | Tag `phase-3-planning` |
| **2** | **Stages 6–9, orchestrated.** Expected epics: E1 skeleton, E2 EAN input, E3 adapters, E4 operations, E5 hardening | Agents, started by the Orchestrator | `pipeline.yaml` | Retailer approvals, `terraform apply`, escalations | Tags `phase-4-skeleton`, `phase-5-features`, `phase-6-hardening` |
| **3** | **Self-healing rehearsal**: simulate a layout change; the health check opens an issue, which starts a fix run | Developer → QA → Code Reviewer and Security & Compliance, started by the Orchestrator | Fix-run trigger | Only if it escalates | Demo criterion 3 |

## 8. Risks and checks

- **Headless skills (checked in step 0.7).** Every pipeline skill ran headless on a toy spec without asking a question. The results are in `docs/bmad-reference.md` §4.
  - `bmad-create-epics-and-stories` has no `-H`, but it ran without a chat and wrote `depends_on` and `touches` on every story. No override is needed. Watch for a menu halt on a larger spec.
  - The `bmad-spec` fallback is out: its story breakdown never runs headless, by design.
  - Skills report their own gaps instead of asking. For example, the brief skipped its review subagents and the architecture could not verify versions without web search. The gates have to read the `open_questions` and `assumptions` in each result.
- **`bmad-build-auto` does not need bmad-loop's environment** (checked in step 0.7). It first failed to render because the GDS module duplicated two config keys. GDS was uninstalled and it now works.
- **Agents write outside the repo.** In step 0.7, `bmad-build-auto` wrote helper scripts to `/private/tmp`. That is harmless, but it is not in the run log. Each worktree should get its own temporary directory through `TMPDIR`.
- **Parallel stories collide** on shared files. Mitigations: the dependency graph, the lock on `openapi.yaml`, and rebase plus a fix round.
- **A judge model is lenient or flaky.** Use deterministic checks first. Keep judge prompts small and specific, and log every verdict for review.
- **EAN lookup may be impossible at some retailers.** The spec requires at least 2. If too few pass, the Architect must choose an external EAN catalog or a curated demo list.
- **Runaway cost.** Mitigations: budgets, `max_rounds`, `max_parallel`, and the spend cap on the API key.

## 9. Corrections to `docs/bmad-reference.md` (applied in step 0.1)

| Reference says | This install / design |
|---|---|
| `bmad-create-prd` | `bmad-prd` (create / update / validate) |
| `bmad-create-architecture` | `bmad-architecture` |
| `bmad-check-implementation-readiness` | Part of `bmad-sprint-planning` (PASS / CONCERNS / FAIL) |
| Scrum Master agent | Not present; the PM and the Architect cover breakdown and readiness |
| `bmad-create-story` + `bmad-dev-story` | `bmad-build-auto` (or `bmad-build`) |
| Test agent (verify) | `bmad-tea` (Eufrosina) |
| `ux-design.md` | `DESIGN.md` + `EXPERIENCE.md` |
| `.customize.yaml` | `_bmad/custom/<skill>.toml` |
| "The human decides what runs next" | The orchestrator decides. The human handles only the three points in §6 |
| Custom agent list | security-compliance-reviewer, devops-release, code-reviewer, knowledge-keeper (data-integration-engineer and qa-engineer dropped) |

## 10. Open items

- [ ] Verify Altex and PC Garage. Both refused or stalled plain non-browser requests during research.
- [ ] Check whether cel.ro search allows lookup by EAN under its robots.txt.
- [ ] Choose how to look up an EAN at each retailer (Architect, stage 4).
