# 0019. Measure every agent step's usage in a ledger derived from the run state, with optional OpenTelemetry export

- **Status:** Accepted
- **Date:** 2026-11-17
- **Deciders:** Loredan (Architect); accepted by Adrian (owner), 2026-11-17
- **Stage / story:** setup, orchestrator update during phase 1
- **Rules touched:** D-2, D-6; orchestrator spine AD-7, AD-24, AD-25; extends ADR 0002 (state is the source) and ADR 0005 (budgets are estimates)

## Context

The owner wants the token use of every agent measured. Until now no one owned usage: the orchestrator stored only one estimated cost per step, and `sdlc status` showed only the run total.

Each orchestrator step runs exactly one agent. Its `claude -p --output-format json` result reports the step's tokens per model (`modelUsage`, subagents included) and `total_cost_usd`. Claude Code can also export per-request metrics through OpenTelemetry, with custom labels.

Two constraints shaped the design:

- A phase 1 run is in progress and must stay resumable after the upgrade.
- On the owner's subscription, costs are estimates; the real limits are the plan's windows (ADR 0005).

Sources: code.claude.com/docs/en/headless, agent-sdk/cost-tracking, monitoring-usage.

## Options considered

| Option | Pros | Cons |
|---|---|---|
| A. A per-step usage record on the run state, projected to a ledger across runs, attributed by a required `agent_role` per step; OpenTelemetry opt-in | Needs nothing extra; exact per agent; crash-safe; works offline | Only steps the orchestrator starts are measured |
| B. OpenTelemetry only | Per-request detail and dashboards | Needs a collector; data is lost when a step is killed; nothing to gate on |
| C. A dedicated agent that reviews usage after each stage | Gives a judgement, not just numbers | Spends tokens to save tokens; the numbers still have to come from somewhere |

## Decision

Option A, with OpenTelemetry as an opt-in setting:

- Every agent and judge step names its role; loading the pipeline fails otherwise.
- Token totals come from `modelUsage`.
- `state.json` holds the records, and `<runs-dir>/usage.jsonl` is a rebuildable projection of them.
- `sdlc usage` reports by agent, stage, model, round or run.
- A warning fires at 80% of the run budget.
- The epic retrospective receives a usage summary and recommends `model` and `effort` changes per stage.

The binding rules are AD-24 and AD-25 in the orchestrator spine.

## Consequences

- Every agent's usage, including failed rounds and attempts cut short by a plan limit, becomes visible per run and across runs.
- `pipeline.yaml` gains `agent_role` on every agent step, plus the `agents` map. A future project (`AGENT_TEAM_REUSE.md`) must keep the roster in that map.
- Interactive sessions stay outside the ledger. Only telemetry turned on in the owner's own settings can observe them.
- The two usage entries in `deferred-work.md` from 2026-11-17 are covered by this decision.

## Compliance

No compliance impact on S-1 to S-13. Ledger rows and telemetry carry no prompt, result or tool content, and the content-logging telemetry variables are always removed from agent processes.
