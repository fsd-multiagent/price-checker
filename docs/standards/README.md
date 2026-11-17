# Standards: the rules every agent works by

Each rule is written **once**, here, and given a stable ID (for example `C-3`). The agent that follows a rule and the agent that verifies it read the same file. Every review finding cites the ID of the rule it breaks.

## Where each kind of rule lives

| Layer | File(s) | What goes there | Loaded how |
|---|---|---|---|
| 1. Project-wide instructions | `CLAUDE.md` / `AGENTS.md` at the root (created by `bmad-project-context`, step 0.6) | Build, test and run commands; repo layout; known pitfalls | Automatically, by every Claude Code session |
| 2. Folder rules | `backend/CLAUDE.md`, `web/CLAUDE.md`, `infra/CLAUDE.md` | Conventions for one area only | Automatically, when an agent works in that folder |
| 3. **Shared standards** | `docs/standards/*.md` (this folder) | Rules with IDs, each followed by one agent and verified by another | Custom agents: their definition tells them to read the file first. BMAD agents: `persistent_facts = ["file:…"]` in `_bmad/custom/<skill>.toml` (step 0.3) |
| 4. Role definition | Custom agents: `.claude/agents/<name>.md`. BMAD agents: `_bmad/custom/<skill>.toml` | Who the agent is, what it reads and writes, which standards apply, what it must never do, and its output format | When the agent is invoked |
| 5. Enforcement | `.claude/settings.json` (permissions, hooks); `sdlc/pipeline.yaml` (gates) | Rules that must not depend on an agent's goodwill | Applied by the tooling and the Orchestrator (steps 0.4 and 0.8) |
| 6. Per-story rules | The story's acceptance criteria | What "done" means for this one story | Passed in by the Orchestrator |

Layer 3 holds the rules themselves. Layer 4 says who the agent is and points it at the rules. If a rule is critical, it is enforced in layer 5 as well.

## Who follows and who verifies

| Standard | Followed by | Verified by | Updated by |
|---|---|---|---|
| [coding.md](coding.md) | Marghioala (Developer) | Filofteia (Code Reviewer) | Loredan (Architect) at stage 4; Aglaia (Knowledge Keeper) after merges |
| [testing.md](testing.md) | Eufrosina (QA), Marghioala (Developer) | Filofteia (Code Reviewer); CI | Eufrosina (QA) |
| [security-compliance.md](security-compliance.md) | Marghioala (Developer), Pacala (DevOps), Loredan (Architect) | Veronel (Security & Compliance) | Veronel (Security & Compliance), with the owner's approval |
| [infrastructure.md](infrastructure.md) | Pacala (DevOps) | Veronel (Security & Compliance); `terraform plan` in CI | Pacala (DevOps) |
| [documentation.md](documentation.md) | Aglaia (Knowledge Keeper), every agent that writes docs | Zamolxe (Orchestrator) gate at stage 9 | Aglaia (Knowledge Keeper) |
| [review-findings.md](review-findings.md) | Every reviewer: Veronel (Security & Compliance), Filofteia (Code Reviewer), and the judge calls | Zamolxe (Orchestrator), which parses the JSON | Owner |

The first versions are seeds. Loredan (Architect) refines `coding.md` and `testing.md` once the architecture decides the details, such as frameworks, formatter and folder layout. A change to a rule is made in a PR, like any other change.
