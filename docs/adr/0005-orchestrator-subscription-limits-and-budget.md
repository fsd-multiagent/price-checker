# 0005. Run on the subscription: pause on plan limits, wait on budget reservations, refuse per-token credentials

- **Status:** Accepted
- **Date:** 2026-11-17
- **Deciders:** Loredan (Architect); accepted by Adrian (owner), 2026-11-17
- **Stage / story:** setup, orchestrator update before phase 1
- **Rules touched:** D-2; orchestrator spine AD-1, AD-5, AD-6, AD-7, AD-8, AD-11, AD-18, AD-23; amends ADR 0002 (adds the `superseded` step status)

## Context

The owner runs Claude Code on a subscription, not an API key. Under subscription auth:

- `total_cost_usd` and `--max-budget-usd` are client-side estimates, not charges.
- Usage counts against the plan's 5-hour and weekly limits.
- In `-p` mode, an `ANTHROPIC_API_KEY` in the environment wins over the subscription and bills per token.

Two behaviours of the first orchestrator caused trouble on a $50 run budget:

- Reserving each step's full cap made parallel stories escalate long before $50 was used.
- A plan limit counted as a failed round.

Sources: code.claude.com/docs/en/costs, authentication, errors, cli-reference, model-config.

## Options considered

| Option | Pros | Cons |
|---|---|---|
| A. The orchestrator waits: a step that only reservations block waits for a slot; a plan limit pauses the run until `resume_at`; preflight refuses per-token credentials | Parallel stories keep working; limits cost no rounds; no surprise bills | A new "waiting" condition in the core, and a new step status |
| B. Let `claude -p` wait in-process (`CLAUDE_CODE_RETRY_WATCHDOG` with a usage-limit wait) | No orchestrator change | The wait collides with the step timeout; other parallel steps keep starting into the same limit |
| C. `max_parallel: 1` and a larger budget | Config only | Gives up parallel stories; plan limits still burn rounds |

## Decision

Option A. The binding rules are in the orchestrator spine:

- **AD-11, budget:** a step waits while it fits after reservations are released and another step is running. Otherwise the run escalates.
- **AD-18, plan limits:** a `limited` attempt is closed as `superseded`, and the run pauses until `resume_at`.
- **AD-8, agent environment:** the in-process watchdog variables are stripped, so `-p` fails fast at a limit.
- **AD-23, credentials:** preflight refuses per-token and third-party provider credentials unless `allow_api_key` is set.
- **AD-7, model and effort:** model, effort and max-turns can be set per stage.

## Consequences

- Real runs need the owner to keep "extra usage" credits off. Otherwise a limit draws paid credits instead of pausing.
- The dry-run gains a `limited` fixture, and the fake clock advances instantly.
- The `limited` detection patterns may need adjusting after the first real limit; the first one seen is saved as a fixture.

## Compliance

No compliance impact on S-1 to S-13. The change covers only scheduling, local state and the agent environment.
