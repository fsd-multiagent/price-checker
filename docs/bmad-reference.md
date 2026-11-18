# BMAD reference: price-checker

This is the one page agents and humans use to find the right agent, workflow and output in this repo.

> **Status:** checked against this install on 2026-11-12.
> - The process itself is described in `docs/automation-plan.md`. This page is the map of names and paths.
> - Items once marked **(verify 0.7)** were confirmed by the headless smoke test (plan step 0.7); results in §4.
> - The Knowledge Keeper owns this file once the team exists.

---

## 1. Installation

| Item | Value |
|---|---|
| BMAD version | 6.12.4 (installed 2026-11-11) |
| Modules | core, BMM (BMad Method), TEA v1.27.2 (Test Architecture), BMB v2.2.2 (Builder), CIS v0.3.2, bmad-loop skills v0.13.2. GDS (game development) was uninstalled on 2026-11-16: its duplicate `planning_artifacts` and `implementation_artifacts` keys stopped `bmad-build` from rendering |
| Modules used by the pipeline | BMM, TEA, plus BMB for building the custom agents |
| Installed, not used | CIS (creative agents), bmad-loop (replaced by the custom Orchestrator) |
| Tool | Claude Code |
| Output folder | `_bmad-output/` |
| Language | English (for communication and documents) |
| Planning track | BMad Method: brief, PRD, UX, architecture, epics and stories |

**Customization:**
- To change agent or workflow behaviour, add overrides in `_bmad/custom/<skill>.toml` (team-wide) or `<skill>.user.toml` (personal). The `bmad-customize` skill writes these.
- Never edit `.claude/skills/*` or `_bmad/*` directly.

**Where the specs live:**
- Product intent is in `docs/initial-spec.md`, v2, owned by the human.
- Planning artifacts are in `_bmad-output/planning-artifacts/`, written by the agents.
- This file does not describe the app.

---

## 2. Where things live

```
docs/
├── initial-spec.md             # product intent (v2, simplified POC); input to stage 1
├── automation-plan.md          # the automated SDLC: stages, agents, orchestrator, guardrails
├── bmad-reference.md           # this file
├── source-register.md          # retailers and their approval status (human approves)    [0.5]
├── compliance-register.md      # requirement -> control -> evidence                       [0.5]
├── adr/                        # architecture decision records (template, index)         [0.5]
└── archive/                    # superseded specs (v1 mobile app)
_bmad/                          # framework, config, scripts (do not edit)
└── custom/                     # overrides: <skill>.toml                                  [0.3]
_bmad-output/
├── planning-artifacts/
│   ├── briefs/brief-price-checker-<date>/brief.md (+ addendum.md, .memlog.md)   # Analyst
│   ├── prds/prd-price-checker-<date>/                                           # PM
│   ├── ux-designs/ux-price-checker-<date>/  (DESIGN.md, EXPERIENCE.md)          # UX Designer
│   ├── architecture/architecture-price-checker-<date>/                          # Architect
│   ├── epics.md                                                                 # PM (breakdown)
│   └── implementation-readiness.md                                              # Architect (readiness)
├── implementation-artifacts/
│   ├── sprint-status.yaml                  # status of every epic and story
│   └── epic-<n>-retro-<date>.md            # retrospectives (Knowledge Keeper)
├── specs/spec-<slug>/                      # bmad-spec output (story breakdown alternative)
└── sdlc/runs/<run-id>/                     # Orchestrator state, logs, gate verdicts, escalations [0.8]
.claude/
├── skills/                     # installed BMAD skills (do not edit)
└── agents/                     # custom subagents                                         [to create, 0.2]
orchestrator/                   # the custom Orchestrator (Python)                         [0.8]
sdlc/pipeline.yaml              # the stage table as data                                  [0.8]
web/                            # React + TypeScript + Vite app; commands in web/CLAUDE.md  [story 1.2]
└── src/styles/tokens.css       # DESIGN.md tokens as CSS custom properties (only place)
```

Every workflow writes its run folder with a `.memlog.md` that records its decisions. Planning workflows resume an unfinished run if they find one.

---

## 3. The agents

Refer to agents by **Position**. Each persona name is set in that agent's configuration (plan §3).

| Position | Name | Implemented by | Source | Stages |
|---|---|---|---|---|
| Analyst | Pompiliu | `bmad-product-brief` (persona: `bmad-agent-analyst`) | BMM | 1 |
| PM | Onofrei | `bmad-prd`, `bmad-create-epics-and-stories`, `bmad-correct-course` (persona: `bmad-agent-pm`) | BMM | 2, 5 |
| UX Designer | Safta | `bmad-ux` (persona: `bmad-agent-ux-designer`) | BMM | 3 |
| Security & Compliance | Veronel | `.claude/agents/security-compliance-reviewer.md` | Custom [0.2] | 3, 4, 7 |
| Architect | Loredan | `bmad-architecture`, readiness check in `bmad-sprint-planning` (persona: `bmad-agent-architect`) | BMM | 4, 5 |
| DevOps | Pacala | `.claude/agents/devops-release.md` | Custom [0.2] | 6 |
| Developer | Marghioala | `bmad-build-auto` (unattended) or `bmad-build` (persona: `bmad-agent-dev`) | BMM | 7 |
| QA | Eufrosina | `bmad-testarch-atdd`, `bmad-testarch-automate`, `bmad-testarch-framework`, `bmad-testarch-ci` (persona: `bmad-tea`) | TEA | 7 |
| Code Reviewer | Filofteia | `bmad-code-review`, plus `.claude/agents/code-reviewer.md` for project conventions | BMM + custom [0.2] | 7 |
| Knowledge Keeper | Aglaia | `.claude/agents/knowledge-keeper.md`; `bmad-retrospective` for each epic | Custom [0.2] + BMM | 9 |
| Orchestrator | Zamolxe | `orchestrator/` (Python code, not an LLM agent) | Custom [0.8] | all |

There is **no Scrum Master** in this install. The PM handles the breakdown, and the Architect handles the readiness check.

Persona names are set in `_bmad/custom/` (`agent.name`, plus a persistent fact, because each `SKILL.md` hard-codes its default name, which BMAD does not let you configure). Rules for each agent live in `docs/standards/` (see its README).

---

## 4. Workflows by stage

The Orchestrator runs every step as a fresh headless session (`claude -p`). For a human doing a step by hand, run it in a fresh chat.

| Stage | Position | Workflow | Reads | Writes | Gate (checked by the Orchestrator) |
|---|---|---|---|---|---|
| 1 Analysis | Analyst | `bmad-product-brief -H` | `docs/initial-spec.md` | `briefs/…/brief.md` | The brief exists and covers every spec section |
| 2 Requirements | PM | `bmad-prd -H`, then `bmad-prd` in validate mode | The brief | `prds/…/` | Validation passes, and every FR has acceptance criteria |
| 3 Design | UX Designer, and Security & Compliance in parallel | `bmad-ux`; security-compliance-reviewer on the PRD | The PRD | `ux-designs/…/`, findings as JSON | Both outputs exist, with no blocker findings |
| 4 Architecture | Architect, then Security & Compliance | `bmad-architecture -H`; security-compliance-reviewer | PRD, UX | `architecture/…/`, `docs/adr/` | No blockers. Otherwise back to the Architect |
| 5 Breakdown | PM, then Architect | `bmad-create-epics-and-stories` (no `-H`, but it runs without a chat; see the results below); then `bmad-sprint-planning -H` | PRD, UX, architecture | `epics.md`, `implementation-readiness.md`, `sprint-status.yaml` | Readiness is PASS. Otherwise back to the PM |
| 6 Infrastructure | DevOps | devops-release | The architecture | `infra/` (Terraform), `.github/workflows/` | `terraform plan` succeeds |
| 7 Build, per story | Developer → QA → Code Reviewer and Security & Compliance in parallel → fixes | `bmad-build-auto`, given the story key and `epics.md` in the prompt; `bmad-testarch-automate`; `bmad-code-review` and code-reviewer; security-compliance-reviewer | The story, the architecture | Code, tests, findings as JSON | Tests pass, no blockers, at most N fix rounds; otherwise escalate |
| 8 Integrate | CI | GitHub Actions | The PR | Merge into `main` | CI is green |
| 9 Learn | Knowledge Keeper | knowledge-keeper after each story; `bmad-retrospective -H` after each epic | The merged diff | Docs, ADR index, registers, retrospective | Docs and registers are updated |

**Changing scope mid-way:** `bmad-correct-course`. The Orchestrator starts it when an escalation calls for re-planning.

**Other workflows**

| Workflow | Use |
|---|---|
| `bmad-review` | Review a document or diff through a lens: adversarial, edge cases, verification gaps, structure, or prose |
| `bmad-walkthrough` | A guided human review of a change |
| `bmad-party-mode` | A discussion between several agents. A conclusion only counts once it is written to a file |
| `bmad-help` | "What should I do next?" |

### Story statuses in `sprint-status.yaml`

`backlog` → `ready-for-dev` → `in-progress` → `review` → `done`. Confirmed in `bmad-sprint-planning/scripts/sprint_plan.py`:
- Epics: `backlog` / `in-progress` / `done`. Retrospectives: `optional` / `done`.
- Story keys must match `N-M-slug`.
- The old values `drafted` and `contexted` are read as `ready-for-dev` and `in-progress`.

### Headless smoke test results (step 0.7, 2026-11-16)

These ran in a sandbox copy with a toy spec (a click counter), with Sonnet, one `claude -p` process per step. Each skill was called as `/<skill> -H <intent>`. Logs: the session scratchpad, `smoke/out/`.

| Stage | Skill or agent | Result | Time, cost | Notes |
|---|---|---|---|---|
| 1 | `bmad-product-brief -H` | ✅ | 25 s, $0.17 | Returns a JSON status with `open_questions`. Skipped its review subagents |
| 2 | `bmad-prd -H`, then validate | ✅ ✅ | 45 s + 87 s, $0.66 | |
| 3 | `bmad-ux -H` | ✅ | 61 s, $0.29 | |
| 3 | security-compliance-reviewer | ✅ review, ⚠️ verdict | 18 s, $0.09 | Read-only, so it cannot write the verdict file. Fixed: it returns the JSON in its final message |
| 4 | `bmad-architecture -H` | ✅ | **22 min**, $1.21 | Wrote ADR 0001 and the index entry. Web search was denied, so versions were not verified |
| 5 | `bmad-create-epics-and-stories` | ✅ | 59 s, $0.30 | No menu halt. `depends_on` and `touches` on every story |
| 5 | `bmad-sprint-planning -H` | ✅ | 26 s, $0.20 | Readiness PASS; wrote `sprint-status.yaml` |
| 7 | `bmad-build-auto` | ✅ after the GDS removal | 276 s, $1.38 | Implemented one story, ran 4 review layers, set the story to `done`, committed. Without a story in the prompt it stops with `blocked` |
| 7 | `bmad-code-review` | ✅ review, ⚠️ verdict | 33 s, $0.23 | Asked for `SDLC_VERDICT_PATH` through Bash and was denied. Fixed: the path goes in the prompt |
| 7 | code-reviewer | ✅ review, ⚠️ verdict | 13 s, $0.05 | Same as the security reviewer; same fix |
| 9 | `bmad-retrospective -H 1` | ✅ | 51 s, $0.29 | Wrote the retrospective and action items to `sprint-status.yaml` |

Guardrails, headless: G1 and G4 denials confirmed; `--dangerously-skip-permissions` is ignored. Total cost about $5.

---

## 5. Custom agents (Claude Code subagents)

All four are to be created in step 0.2. List them in Claude Code with `/agents`.

| Position | File | Tools | Never |
|---|---|---|---|
| Security & Compliance | `.claude/agents/security-compliance-reviewer.md` | Read, Grep, Glob | Edits code, or approves a retailer |
| DevOps | `.claude/agents/devops-release.md` | Read, Grep, Glob, Edit, Write, Bash | Runs `terraform apply`, or SSHes to the VM |
| Code Reviewer | `.claude/agents/code-reviewer.md` | Read, Grep, Glob | Edits code |
| Knowledge Keeper | `.claude/agents/knowledge-keeper.md` | Read, Grep, Glob, Edit, Write | Changes code or tests |

Reviewers (Security & Compliance, Code Reviewer) return findings as JSON, and every finding carries a severity (`blocker | major | minor`). The Orchestrator gates on these.

---

## 6. Human vs automated

| Human only | Automated (the Orchestrator decides) |
|---|---|
| Approving a retailer in `docs/source-register.md` | Every stage 1–9 and its gate |
| `terraform apply` | Fix rounds, up to N |
| Resolving escalations, then `sdlc resume <id>` | Per-story PRs, CI, and auto-merge |
| Accounts, secrets, spend ceiling | Docs and registers after each merge |
| | Self-healing fix runs started by the health check |

---

## 7. Project phases and tags

| Phase | Content | Tag |
|---|---|---|
| 2 | BMAD setup, agent team, Orchestrator (plan phase 0) | `phase-2-setup` |
| 3 | Stages 1–5, orchestrated | `phase-3-planning` |
| 4 | Walking skeleton (E1) | `phase-4-skeleton` |
| 5 | Core features (E2–E4) | `phase-5-features` |
| 6 | Hardening (E5) and self-healing rehearsal | `phase-6-hardening` |
| 7 | Internal presentation | `phase-7-internal` |
| 8 | Conference presentation | — |

Project phases are not the same as BMAD's own phases. BMAD's analysis, planning, solutioning and implementation all run inside project phases 3 to 5.

---

## 8. Verification checklist

- [x] BMAD version and modules recorded (§1)
- [x] Workflow names match this install (§3, §4)
- [x] Output paths match each workflow's `customize.toml` (§2)
- [x] Headless behaviour of each workflow confirmed (step 0.7, §4)
- [x] `bmad-build-auto` runs without the bmad-loop environment (step 0.7)
- [x] Story statuses confirmed (step 0.7)
- [x] The four custom agent files exist in `.claude/agents/` (step 0.2); `/agents` check pending a session restart
- [x] Persona names applied through `_bmad/custom/<skill>.toml` and `_bmad/custom/config.toml` (done early, with 0.2)
