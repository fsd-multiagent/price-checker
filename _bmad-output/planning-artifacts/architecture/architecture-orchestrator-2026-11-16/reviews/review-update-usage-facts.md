# Review: AD-24 and AD-25 technology facts (spine update 2026-11-17)

- **Target:** `ARCHITECTURE-SPINE.md`, AD-24 (usage ledger) and AD-25 (optional OpenTelemetry)
- **Lens:** reality check of every technology claim against the official docs and the installed CLI
- **Date:** 2026-11-17
- **Verdict:** **fail — one critical fact is wrong.** The ledger design is sound, but AD-24 reads its token counts from the one result field that leaves out subagents, which is the opposite of what the rule says. Fix F1 before any code is written; the rest are small edits.

## Sources checked

| Source | What it settled |
| --- | --- |
| `claude --version` → `2.1.326 (Claude Code)`; `claude --help` | `--output-format json` = "single result"; `--max-budget-usd`, `--no-session-persistence` present |
| https://code.claude.com/docs/en/headless.md | JSON output has `total_cost_usd` and a per-model breakdown; `--resume`/`--continue` report the conversation's whole total |
| https://code.claude.com/docs/en/agent-sdk/cost-tracking.md | Scope of `usage` vs `total_cost_usd` vs `modelUsage`; subagent inclusion; error-result caveats; `modelUsage` inner names |
| https://code.claude.com/docs/en/agent-sdk/typescript.md (`SDKResultMessage`, `ModelUsage`) | Exact result field names and types (the CLI's JSON result is this message) |
| https://code.claude.com/docs/en/monitoring-usage.md | OTel variables, exporter values, defaults, prometheus port, resource-attribute format, content gates |
| https://code.claude.com/docs/en/env-vars.md | `CLAUDE_CODE_OTEL_SHUTDOWN_TIMEOUT_MS`, `CLAUDE_CODE_OTEL_DIAG_STDERR`, which OTel variables project settings can set |
| https://code.claude.com/docs/en/cli-reference.md | `--max-budget-usd` counts subagent spend |
| `price-checker/_bmad-output/sdlc/runs/20261117-174705-full/steps/*.json` and `*.log` | See "Local evidence" below |

### Local evidence

The step files of the real run do **not** hold the raw result envelope. `steps/*.json` is the adapter's parsed subset (`errors, exit_code, is_error, num_turns, permission_denials, resets_at, result, subtype, total_cost_usd, usage_limited`), and `steps/*.log` holds the argv, the prompt and the agent's result text. No file under `runs/` contains `usage`, `modelUsage` or `duration_ms`. They confirm `total_cost_usd`, `num_turns`, `subtype`, `is_error`, `permission_denials` at the top level of the result, and nothing about the usage fields. Field names below therefore rest on the docs, not on a captured envelope (see F6).

## Fact table

| Claim in AD-24/AD-25 | Status | Evidence |
| --- | --- | --- |
| Result has `usage.input_tokens`, `output_tokens`, `cache_read_input_tokens`, `cache_creation_input_tokens` | Correct names | cost-tracking "Track cache tokens"; `SDKResultMessage.usage: NonNullableUsage` |
| Result has `modelUsage` keyed by model name | Correct | `modelUsage: { [modelName: string]: ModelUsage }` |
| `modelUsage` inner fields | Correct, as camelCase: `inputTokens`, `outputTokens`, `cacheReadInputTokens`, `cacheCreationInputTokens`, `costUSD`, `webSearchRequests`, `contextWindow`, `maxOutputTokens`; optional `thinkingTokens`, `canonicalModel`, `provider`, `costBasis` (≥ 2.1.246) | `type ModelUsage` |
| `duration_ms`, `num_turns`, `total_cost_usd` at the top level | Correct (also `duration_api_ms`, `session_id`, `stop_reason`, `terminal_reason`) | `SDKResultMessage`; `num_turns`/`total_cost_usd` seen in real step files |
| "A step's whole usage … subagents included, because `claude -p` reports them in the step's totals" | **Half wrong.** True for `total_cost_usd` and `modelUsage`; **false for `usage`**, which "counts only the top-level agent loop, so tokens consumed inside subagents are not added" | cost-tracking, table under "Get the total cost of a query" |
| `usage` covers the whole step | True for a single-shot `-p` call: the result carries "cumulative `usage`" for the call (main loop only). Per-turn scoping applies only to streaming input mode, which the orchestrator does not use. No restored spend, since steps never `--resume` and use `--no-session-persistence` | cost-tracking "Track costs in streaming input mode" |
| Error results carry usage | Mostly. `error_max_budget_usd`: `usage` leaves out the response that crossed the cap; `total_cost_usd` and `modelUsage` include it. `error_during_execution` after a crash: all may be zero | cost-tracking "Track costs on failed conversations" |
| `CLAUDE_CODE_ENABLE_TELEMETRY=1` required | Correct | monitoring-usage, common configuration table |
| `OTEL_METRICS_EXPORTER` values `otlp`, `console` | Correct; full set is `console`, `otlp`, `prometheus`, `none`, comma-separated | same |
| `OTEL_EXPORTER_OTLP_ENDPOINT` / `OTEL_EXPORTER_OTLP_PROTOCOL` | Correct; protocol values `grpc`, `http/json`, `http/protobuf`; default `http/protobuf` for an `http(s)://` endpoint needs ≥ 2.1.323 (runner 2.1.326 is fine) | same |
| `OTEL_RESOURCE_ATTRIBUTES` can carry custom labels | Correct; comma-separated `key=value`, no spaces, no `"` `,` `;` `\`, percent-encode the rest, `=` in a value as `%3D`. Attributes land on every datapoint by default (`OTEL_METRICS_INCLUDE_RESOURCE_ATTRIBUTES=true`) | "Multi-team organization support" |
| `prometheus` would make parallel processes clash on one port | Correct: fixed `localhost:9464`; env-vars says a "Prometheus port collision otherwise fails silently". Stronger reason the spine misses: a pull exporter in a short-lived `-p` process is gone before most scrapes | monitoring-usage "Example configurations"; env-vars `CLAUDE_CODE_OTEL_DIAG_STDERR` |
| `OTEL_LOG_USER_PROMPTS`, `OTEL_LOG_TOOL_DETAILS` off by default | Correct (both "default: disabled") | common configuration table |
| Telemetry variables can be set per agent process | Correct, through the process environment. Note: project/local `.claude/settings*.json` cannot turn export on; the orchestrator must not try that route | monitoring-usage line on ignored repo settings; env-vars |

## Findings

### F1 — critical — Token totals taken from `usage` miss all subagent tokens

AD-24 says the adapter parses `usage` for "the four token counts" and that the whole usage including subagents is charged to `agent_role` "because `claude -p` reports them in the step's totals". The docs say the opposite for `usage`: it is main-loop only and "undercounts as soon as nesting occurs"; only `total_cost_usd` and `modelUsage` include subagents. BMAD skills in this project fork subagents routinely (review lenses, research), so per-agent token totals would be systematically low while `cost_usd` is right, and `sdlc usage --by model` would disagree with `--by agent`. It also undercounts on `error_max_budget_usd`, a subtype the orchestrator hits because it always passes `--max-budget-usd`.

**Fix:** In AD-24, make `modelUsage` the source of every token count: the four totals are sums over its entries (`inputTokens`, `outputTokens`, `cacheReadInputTokens`, `cacheCreationInputTokens`), and the per-model breakdown is those entries plus `costUSD`. Keep `usage` only as an optional `main_loop` sub-record (or drop it). Reword the attribution bullet to "subagents included, because `total_cost_usd` and `modelUsage` count them; `usage` does not". Add a test fixture with a `modelUsage` containing two models whose sum differs from `usage`.

### F2 — medium — A zeroed crash result is treated as complete

AD-24 sets `usage_complete: false` only when a field is missing or there is no parsable output. A crash produces a parsable `error_during_execution` result whose `usage`, `total_cost_usd` and `modelUsage` "may be zeroed". That record would be stored as complete with 0 cost, and the budget would release the reservation as if nothing was spent.

**Fix:** Treat `subtype == "error_during_execution"` with `total_cost_usd == 0` (or an empty `modelUsage`) like an unparsable attempt: charge the reserved cap (AD-11) and set `usage_complete: false`.

### F3 — medium — "Never sets" does not stop inherited content-capture variables, and the list is short

AD-25 promises to prevent "telemetry that leaks prompts", but only says the orchestrator never *sets* `OTEL_LOG_USER_PROMPTS`, `OTEL_LOG_TOOL_DETAILS`, `OTEL_EXPORTER_OTLP_HEADERS`. AD-23 makes agents inherit the runner's environment, so a value exported in the runner's shell or `~/.claude/settings.json` still reaches every agent. The list also misses the other content gates: `OTEL_LOG_ASSISTANT_RESPONSES` (falls back to `OTEL_LOG_USER_PROMPTS`), `OTEL_LOG_TOOL_CONTENT`, `OTEL_LOG_RAW_API_BODIES` (full request and response bodies).

**Fix:** When telemetry is enabled, set `OTEL_LOG_USER_PROMPTS=0`, `OTEL_LOG_ASSISTANT_RESPONSES=0`, `OTEL_LOG_TOOL_DETAILS=0`, `OTEL_LOG_TOOL_CONTENT=0`, `OTEL_LOG_RAW_API_BODIES=0` explicitly in the agent environment (or have preflight refuse a run where any is truthy). Leave `OTEL_EXPORTER_OTLP_HEADERS` inherited as written. Also state that the off-path ("does not touch any telemetry variable") means a runner-level `prometheus` or content-capture setting still applies; either strip `OTEL_*`/`CLAUDE_CODE_ENABLE_TELEMETRY` when off, or say so in the owner checklist.

### F4 — medium — Short-lived processes can drop their last metrics; `console` may corrupt the JSON result

Metric export interval defaults to 60 000 ms, and most judge steps finish well inside one interval, so their data rides only on the shutdown flush, which is bounded by `CLAUDE_CODE_OTEL_SHUTDOWN_TIMEOUT_MS` (default 2000; docs: "Increase if metrics are dropped at exit"). A step killed on timeout (SIGKILL of the process group) exports nothing. Separately, the docs do not say which stream the `console` exporter writes to. If it is stdout, it would interleave with the `--output-format json` envelope that `parse_result` reads with `json.loads(stdout)` and turn every step into a parse error.

**Fix:** When telemetry is on, also set `CLAUDE_CODE_OTEL_SHUTDOWN_TIMEOUT_MS` (for example 10000) and optionally a shorter `OTEL_METRIC_EXPORT_INTERVAL`; add `CLAUDE_CODE_OTEL_DIAG_STDERR=1` so exporter errors reach the step log. Drop `console` from the allowed values (allow `otlp` only) unless the opt-in live test proves `console` writes to stderr. Say in AD-25 that gaps in telemetry are expected and harmless because the ledger is the source of truth.

### F5 — low — `OTEL_RESOURCE_ATTRIBUTES` encoding is not specified

AD-25 "extends" the runner's value with `sdlc.*` keys. Scope ids such as `story:1-2-slug` are legal (`:` is allowed), but nothing stops a step or run id from containing a space, comma or `=`, and naive appending can produce a leading comma or a duplicate key when the runner already sets `sdlc.*`.

**Fix:** Specify: percent-encode every `sdlc.*` value (RFC 3986 unreserved set kept), join with `,`, skip the separator when the inherited value is empty, and let orchestrator keys replace inherited keys with the same name. Add a unit test for the builder.

### F6 — low — The ledger's fields cannot be audited against the raw envelope

The real run stores no raw result: `steps/*.json` is the parsed subset and `steps/*.log` is the prompt plus result text. If the CLI renames or adds a usage field, nothing on disk shows it, and `usage_complete: false` would be the only clue.

**Fix:** Have the adapter write the raw result envelope minus `result` (that text is already in the log) to `steps/<seq>-…json` under a `raw` key, or to `steps/<seq>-….result.json`. Record `session_id` and `duration_api_ms` in the `UsageRecord` too; both are cheap and help reconcile with OTel data (`session.id` is on every metric by default).

## Other observations (no change required)

- `model` as a single field: a step can use several models (subagents on Haiku). Derive it as the step's configured model and keep the per-model map as the truth; the spine already stores both.
- `total_cost_usd` and `costUSD` are client-side estimates from a bundled price table (cost-tracking, "estimates, not billing"). Fine for budgets; worth one sentence in AD-24 so nobody reconciles them against an invoice. On the subscription, these numbers are notional.
- `OTEL_LOG_TOOL_DETAILS` also gates real agent and skill names on the cost and token counters; with it off, those labels are redacted. The `sdlc.agent` resource attribute is what makes per-agent dashboards work, which matches AD-25's intent.
- Per-step accounting is correct as designed: one `claude -p` call per attempt, no `--resume`, `--no-session-persistence`, so each result is that attempt's own total.
