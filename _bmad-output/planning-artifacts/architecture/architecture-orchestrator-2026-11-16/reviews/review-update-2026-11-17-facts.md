# Review: fact check of the 2026-11-17 spine update (AD-7, AD-8, AD-11, AD-18, AD-23)

- Spine: `../ARCHITECTURE-SPINE.md` (update of 2026-11-17)
- Lens: are the new technology claims checked against the installed CLI and the official docs, or asserted from memory?
- Reviewed: 2026-11-17, on the owner's Mac
- Constraints: no paid `claude -p` call was made (the `--max-turns` probe failed at argument parsing, before any request). No project file was edited except this one.

## Verdict

**Pass with fixes; one high finding.** Most of the claims hold: the `--effort` values, `--max-turns`, the precedence of `ANTHROPIC_API_KEY` in `-p`, and the fact that `total_cost_usd` is a client-side estimate. But the usage-limit wait in AD-8 does nothing as written. `CLAUDE_CODE_RETRY_WATCHDOG_MAX_USAGE_LIMIT_WAIT_MS` only takes effect when `CLAUDE_CODE_RETRY_WATCHDOG=1` is also set. The open question that says "the docs are silent" on what `-p` does at a plan limit is out of date. AD-18's detection strings and AD-23's credential list are also incomplete.

Installed CLI is **2.1.326**, not 2.1.325 as the spine says (`claude --version`). Nothing below depends on the difference.

## Sources

- CLI: `claude --version`, `claude --help`, `strings` on `~/.local/share/claude/versions/2.1.326`
- https://code.claude.com/docs/en/cli-reference.md
- https://code.claude.com/docs/en/model-config.md (section "Adjust effort level")
- https://code.claude.com/docs/en/errors.md ("Tune retry behavior" table; "Usage limits")
- https://code.claude.com/docs/en/env-vars.md
- https://code.claude.com/docs/en/authentication.md ("Authentication precedence")
- https://code.claude.com/docs/en/settings-reference.md (`env`, "Variables Claude Code ignores in env")
- https://code.claude.com/docs/en/costs.md
- https://code.claude.com/docs/en/headless.md
- https://code.claude.com/docs/en/interactive-mode.md ("Wait for a usage limit to reset")
- https://code.claude.com/docs/en/agent-sdk/cost-tracking.md ("Estimates, not billing")
- https://code.claude.com/docs/en/agent-sdk/agent-loop.md ("Handle the result")

## Claim-by-claim

| # | Claim (spine) | Evidence | Result |
| --- | --- | --- | --- |
| 1 | `--effort` takes `low \| medium \| high \| xhigh \| max` (AD-7, AD-8) | `claude --help`: "Effort level for the current session (low, medium, high, xhigh, max)". cli-reference also lists `ultracode` (xhigh plus ultracode, v2.1.203+). model-config: a level the model doesn't support falls back to the highest supported level at or below it; levels above an org cap run at the cap, and with `json` output the warning goes only to the debug log. The flag works in `-p`: model-config documents the `--print` behaviour, and nothing restricts it to interactive mode | Correct. The precedence caveat is in F5 |
| 2 | `CLAUDE_CODE_RETRY_WATCHDOG_MAX_USAGE_LIMIT_WAIT_MS` = the step timeout minus 5 min, so the in-process wait for a plan limit never outlasts the step (AD-8) | errors.md, "Tune retry behavior": "**With `CLAUDE_CODE_RETRY_WATCHDOG` set**, the longest time in milliseconds one request waits for your plan's usage limit … to reset. When the reset is further away than this cap, the request fails at once, unless usage credits have room…". Default: no cap. Requires v2.1.301+. The name is in the 2.1.326 binary. The unit is **milliseconds**. The cap applies **per request**. The variable is not in env-vars.md, only in errors.md | **Name and units are correct, but the variable has no effect unless the watchdog is on.** See F1 |
| 3 | Usage-limit texts "hit your session limit", "hit your weekly limit" or another usage-limit message, found in `result` or `errors` (AD-18) | errors.md: `You've hit your session limit · resets 3:45pm`, `You've hit your weekly limit · resets Mon 12:00am`, `You've hit your Opus limit · …`, `You've hit your Sonnet limit · …`. With usage credits or org billing there are also `You've hit your … spend limit` / `… usage limit` variants. agent-loop.md: `result` exists only on `subtype: success`; error subtypes carry `errors`. No doc says which field the limit text lands in for a `-p` JSON result | Partly right. Opus and Sonnet limits are missing, and the field is unverified. See F3 |
| 4 | "In `-p` mode, `ANTHROPIC_API_KEY` always wins over the subscription login"; preflight blocks `ANTHROPIC_API_KEY` and `ANTHROPIC_AUTH_TOKEN` (AD-23) | authentication.md precedence: 1 cloud provider (`CLAUDE_CODE_USE_BEDROCK/VERTEX/FOUNDRY`), 2 `ANTHROPIC_AUTH_TOKEN`, 3 `ANTHROPIC_API_KEY` ("In non-interactive mode (`-p`), the key is always used when present"), 4 `apiKeyHelper`, 5 `CLAUDE_CODE_OAUTH_TOKEN`, 6 Anthropic profile (`ANTHROPIC_PROFILE`), 7 `/login` subscription | The claim is correct. The list is incomplete. See F2 |
| 5 | `--max-turns` is documented but not confirmed in 2.1.325 (Deferred) | cli-reference: "`--max-turns` Limit the number of agentic turns (print mode only)". `claude -p --max-turns 1 --zz-not-a-flag` fails with "unknown option '--zz-not-a-flag'", so the parser accepts `--max-turns`. A misspelled `--zz-max-turnz` is rejected. The flag appears 13 times in the binary. When the limit is hit the subtype is `error_max_turns` | **It is a real flag, hidden from `--help`.** The Deferred row can be closed. See F6 |
| 6 | `total_cost_usd` and the `--max-budget-usd` cap are API-equivalent client-side estimates, not charges on a subscription (AD-11) | cli-reference `--max-budget-usd`: "checks the cap against its client-side cost estimate, which can differ from your bill … Spend can pass the cap, so leave headroom". cost-tracking: "`total_cost_usd` … are client-side estimates, not authoritative billing data … computed locally from a price table". costs.md: for subscribers "the session cost figure isn't relevant for billing purposes" | Correct. The overshoot caveat is in F4 |

## Findings

### F1 (high): the AD-8 usage-limit cap does nothing without `CLAUDE_CODE_RETRY_WATCHDOG=1`, and the "docs are silent" question is stale

- The cap variable is documented only as "With `CLAUDE_CODE_RETRY_WATCHDOG` set …". AD-8 sets the cap alone, so the cap does nothing.
- What `-p` does at a plan limit is mostly documented:
  - The automatic wait-and-continue at a usage limit applies to "interactive sessions signed in with a claude.ai subscription" (interactive-mode.md, errors.md).
  - With the watchdog on, the request waits for the reset, up to the cap. A reset further away than the cap fails at once.
  - So without the watchdog, `-p` fails at the limit (inferred: no documented wait path applies). With the watchdog, it waits up to the cap.
- Turning the watchdog on has side effects:
  - `429` and `529` errors are retried indefinitely unless `CLAUDE_CODE_RETRY_WATCHDOG_MAX_WAIT_MS` is set.
  - The retry count for transient errors rises to 300, about 3 hours.
  - The cap is per request, so several requests in one step can each wait. The step timeout still bounds the total.
- **Fix, one of these:**
  - (a) Set `CLAUDE_CODE_RETRY_WATCHDOG=1` with both `…_MAX_USAGE_LIMIT_WAIT_MS` and `…_MAX_WAIT_MS` derived from the step timeout. State that the wait is per request and that the step timeout is the real bound. Optionally set `…_USAGE_LIMIT_RECHECK_MS`.
  - (b) Simpler, and enough for the demo: drop the variable, let `-p` fail fast, and rely on AD-18 `limited` plus `resume_at`.
- Either way, rewrite the open question: the behaviour is documented, and only the JSON field still needs a live check (F3). Note the version needs: the cap requires v2.1.301+, the recheck variable v2.1.305+, and both preflight-check `claude --version`.

### F2 (medium): AD-23 misses other credential sources, including ones set outside the process environment

- Credentials that also outrank the subscription:
  - `CLAUDE_CODE_USE_BEDROCK`, `CLAUDE_CODE_USE_VERTEX` and `CLAUDE_CODE_USE_FOUNDRY` (cloud billing)
  - `apiKeyHelper` in any settings file (API billing)
  - `ANTHROPIC_PROFILE` (Console profile)
  - `ANTHROPIC_BASE_URL` routes requests to a gateway
- `CLAUDE_CODE_OAUTH_TOKEN` is subscription auth and fine to allow.
- Settings `env` blocks overwrite exported shell variables. User and managed settings apply them at startup. Project and local settings apply them at startup in `-p`, though credential variables may be in their ignore list. So a key set in `~/.claude/settings.json` `env` passes an `os.environ`-only preflight.
- **Fix:**
  - Extend the preflight to the variables above.
  - Also scan `env` and `apiKeyHelper` in `~/.claude/settings.json` and the project's `.claude/settings*.json`. Or, more robustly, check `claude auth status` (if it reports the method) once per run.
  - Change "never adds credentials" to cover settings too.

### F3 (medium): AD-18 detection strings are incomplete, and the field is unverified

- Missing texts:
  - `You've hit your Opus limit` and `You've hit your Sonnet limit` matter when stages pin `--model`.
  - The usage-credits or org `… spend limit` / `… usage limit` variants.
- The docs do not say whether a `-p` JSON result carries the text in `result` (with `is_error: true` on a `success` subtype) or in `errors` (with `error_during_execution`). The spine checks both, which is right. The case-insensitive pattern `hit your .* limit` is safer than a list of exact strings.
- Binary check (2.1.326 `strings`): the message is built from a template, `` `You've hit your ${e}${n}` `` with an optional ` · progress saved` suffix. The binary also contains `You've hit your limit`, `You've hit your Fable limit` and `You've hit your fast limit`, and the usage-limit wait reads the `anthropic-ratelimit-unified-status: rejected` header. A fixed list of strings will miss variants.
- **Fix:**
  - Prefer a generic case-insensitive `you've hit your .*limit`. Otherwise match on `you've hit your (session|weekly|opus|sonnet) limit` plus `hit your .*(spend|usage) limit`, across `result` and every `errors[]` entry.
  - Treat Opus or Sonnet limits as `limited`, or optionally retry on another model family.
  - Keep the live check in the open question, and add a recorded fixture once seen.

### F4 (low): budget caps can be overshot, and usage credits can turn plan usage into charges

- `--max-budget-usd` is checked against the estimate and "Spend can pass the cap, so leave headroom". AD-11's statement that a step may start only if spent + reserved + cap ≤ `run_budget_usd` treats the cap as hard.
- If the owner has usage credits enabled on the subscription, hitting a plan limit draws paid credits instead of failing. That is also a per-token charge, which AD-23 aims to prevent.
- **Fix:**
  - Add a headroom note to AD-11, and charge actual `total_cost_usd` (already said).
  - Add to AD-23, or the owner checklist, that usage credits stay off for the runner account.

### F5 (low): `CLAUDE_CODE_EFFORT_LEVEL` beats `--effort`

- In the model-config resolution order, the `CLAUDE_CODE_EFFORT_LEVEL` environment variable outranks `--effort`. If the owner's shell exports it, every per-stage `effort` is ignored silently, and with `json` output the warning goes only to the debug log.
- Since Opus 5.5, Sonnet 5.5 and Fable 5.2 default to `medium`, "unset means the CLI default" means `medium` for current models, not `high`.
- **Fix:** have AD-8 unset `CLAUDE_CODE_EFFORT_LEVEL` in the child environment when a stage sets `effort`, or have preflight warn about it. Optionally allow `ultracode` in the enum, or state that it is excluded on purpose.

### F6 (low): stale facts in the Deferred table and version

- `--max-turns` is confirmed:
  - It is in the docs.
  - The 2.1.326 parser accepts it.
  - It produces `error_max_turns`, which AD-18 already maps to `error`.
- The installed CLI is 2.1.326, not 2.1.325.
- **Fix:**
  - Move `--max-turns` from Deferred to an optional stage field (`max_turns`) in AD-7/AD-8, or keep it deferred but drop the "not confirmed" reason.
  - Update the version reference, or say "2.1.325 or later".
