# 0025. Let a step set its own model, effort and max turns, so read-only reviewers can run cheaper than the developer

- **Status:** Proposed
- **Date:** 2026-11-18
- **Deciders:** Marghioala (Developer) in story `0-55-tune-model-and-effort-per-step` (issue #55); recorded by Aglaia (Knowledge Keeper); Loredan (Architect) and the owner to accept
- **Stage / story:** `0-55-tune-model-and-effort-per-step`
- **Rules touched:** D-2; orchestrator spine AD-7, AD-24; amends the AD-7 bullet of ADR 0005 ("model, effort and max-turns can be set per stage")

## Context

ADR 0005 let `model`, `effort` and `max_turns` be set per stage. The build stage runs Marghioala (Developer), Eufrosina (QA) and three reviewers together. So the reviewers could not be tuned without also changing the developer.

Run `20261117-174705-full` cost about $10–12 of estimated usage per story. The build stage was 87% of it: Marghioala (Developer) 50%, Filofteia (Code Reviewer) 24%, Eufrosina (QA) 9% (`sdlc usage --by agent`). The read-only reviewer steps and the gate judges do narrow checks against written rules.

## Options considered

| Option | Pros | Cons |
|---|---|---|
| A. Optional `model`, `effort`, `max_turns` on a step; step, then stage, then `default_model` / `default_effort` | Tunes one step without touching its siblings; same validation as the stage fields | One more level to resolve |
| B. Split the reviewers into their own stage | No new field | Changes the stage table and its gates; breaks resume of existing runs |
| C. Lower the whole build stage | Config only | Weakens the developer step, which is half the usage and the hardest work |

## Decision

Option A. A step value overrides the stage value, which overrides `default_model` / `default_effort` (`max_turns`: step, then stage). Step values are validated like stage values (`ultracode` excluded, `max_turns > 0`). An error names the stage and the step. Script steps may not set them. The step values are left out of the stage-table digest, so `sdlc resume` accepts a tuning change. The `claude -p` argv and the usage ledger (`model_requested`, `effort`, AD-24) get the resolved values.

`sdlc/pipeline.yaml` sets these values:

| Step | `model` | `effort` |
|---|---|---|
| `coverage` (judge), `security_prd`, `security_arch` | `sonnet` | `medium` |
| `code_review` | stage value | `medium` |
| `dev`, `qa` | stage value | stage value |

## Consequences

- Reviewer and judge cost can be tuned per step, and the tuning does not block `sdlc resume`.
- The ledger resolves `model_requested` and `effort` from the pipeline loaded now, not from the values the attempt was started with. After a retune and resume, or after `sdlc usage --rebuild`, older records show the new values. This was already true for stage values (story deferral).
- If a cheaper reviewer misses findings, raise its `model` or `effort` again. Compare the findings across runs with `sdlc usage --by agent`.

## Compliance

No compliance impact on S-1 to S-13. The change covers only which model runs an orchestrator step and how hard it thinks. Reviewer prompts, tools and rules are unchanged.

## Amendment 2026-11-18

`code_lens` and `security` in the build stage go back to the stage model and effort. On `sonnet` with `medium` effort they often ended with an empty final message, so their `result_block` verdict was missing and the round failed: 15 rounds in run `20261117-174705-full` and all 3 rounds of story 0-90. Stories 3-3, 4-1 and 0-90 escalated because of it. The planning reviewers keep the cheaper model.
