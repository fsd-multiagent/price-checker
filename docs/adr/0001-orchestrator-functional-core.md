# 0001. Build Zamolxe (Orchestrator) as a functional-core Python CLI that drives headless `claude -p`

- **Status:** Accepted
- **Date:** 2026-11-16
- **Deciders:** Loredan (Architect); accepted by Adrian (owner), 2026-11-16
- **Stage / story:** setup step 0.8
- **Rules touched:** D-2, D-6; automation-plan O1, O2, §4, §5

## Context

Plan decision O1 calls for a custom orchestrator rather than bmad-loop. Plan §4.1 fixes its shape:

- a Python CLI
- one fresh `claude -p` process for each agent step
- the pipeline described in YAML
- state kept on disk

The step 0.8 acceptance check is that `sdlc run --dry-run` works. The orchestrator is plain code, so the guard hook does not see its own git and gh calls (plan §5, Limits). Its safety rules therefore have to live in its code.

## Options considered

| Option | Pros | Cons |
|---|---|---|
| A. Functional core, imperative shell: a pure scheduler and gate logic, with all I/O behind ports and one asyncio loop | Dry-run and tests use fake adapters on the same code path; resume is deterministic; no locks | Needs some discipline to keep the core pure |
| B. A procedural script that calls subprocesses inline | Fastest to write | Dry-run becomes a separate path; hard to test or resume |
| C. A workflow engine (Temporal, Prefect, Airflow) | Built-in retries and UI | Heavy infrastructure for a learning POC; hides the orchestration this project exists to learn |
| D. Claude Agent SDK as the runner | Finer control | Plan §4.1 marks it as an upgrade path; the CLI was proven headless in step 0.7 |

## Decision

Option A. A Python ≥ 3.12 package `orchestrator/` (uv, pydantic, PyYAML) with a pure `core/` and adapters for `claude`, `git`, `gh`, `fs`, `clock` and `notifier`. Everything runs in one asyncio event loop. Dry-run means the same loop with fake adapters. The binding rules are AD-1 to AD-3, AD-8 and AD-12 in `_bmad-output/planning-artifacts/architecture/architecture-orchestrator-2026-11-16/ARCHITECTURE-SPINE.md`.

## Consequences

- The core can be unit-tested without network access, git or cost.
- Moving to the Agent SDK only replaces the `claude` adapter.
- The orchestrator's `git` adapter must refuse to push to `main` itself, because the guard hook does not apply to it.
- Follow-up: add Python rules for `orchestrator/` to `docs/standards/` (a `C-O` section) once the code exists.

## Compliance

No compliance impact on S-1 to S-13: the orchestrator fetches nothing from retailers. Retailer approval stays a human decision. The orchestrator only reads `docs/source-register.md`, using the guard's rule (AD-15).
