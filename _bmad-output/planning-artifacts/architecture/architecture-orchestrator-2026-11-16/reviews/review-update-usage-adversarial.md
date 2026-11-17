# Adversarial review: spine update "usage ledger and telemetry" (AD-24, AD-25)

- **Target:** `ARCHITECTURE-SPINE.md` working-tree diff of 2026-11-17: new AD-24, new AD-25, defaults rows `agents`, `usage_warn_ratio`, `telemetry`, Step field `agent_role` (AD-7), the Logging convention row.
- **Scope:** only these additions, attacked against AD-1, AD-4, AD-6, AD-9, AD-11, AD-12, AD-18, AD-21, AD-22 and AD-23. For each hole there are two units that both obey every AD as written and still diverge.
- **Code read for grounding:** `orchestrator/src/sdlc/{cli,store}.py`, `core/{models,state,scheduler}.py`, `app/loop.py`, `adapters/{claude,proc}.py`, `sdlc/pipeline.yaml`; the in-progress run `_bmad-output/sdlc/runs/20261117-174705-full/state.json` in the main checkout (status `running`, 29 step records, spent 15.59 of 50, reserved 18). Only the status, budget, step count and `pipeline_path` of that file were read.

## Verdict

**Not ready to bind.** Two problems block it. First, adding `agent_role` to steps changes the stage-table digest, so the run in progress can no longer be resumed. Second, the ledger is written by a second persistence step whose order relative to `state.json` is not defined. A crash between the two makes the ledger and the state disagree about the same `(run_id, seq)`, and the dedupe rule then keeps the wrong version. AD-25 also contradicts AD-11 on what budgets read, and its "don't touch telemetry variables" rule lets inherited prompt logging through. 1 critical, 3 high, 4 medium, 3 low.

---

## U1 — critical: the run in progress can no longer be resumed

**Units:** `cli._resume` (resume refuses a changed stage table; ADR 0002, plan §4.4) and `core/pipeline.py` (AD-7 + AD-24: `agent_role` is required on `agent` and `judge` steps, unknown or missing is a load error).

**Scenario:** `stage_table_digest` excludes only the stage-level `LIMITS` (`title, max_rounds, timeout_min, budget_usd, model, effort, max_turns`). It hashes every step field. Run `20261117-174705-full` stores `pipeline_path=/Volumes/adata/projects/price-checker/sdlc/pipeline.yaml` and the old digest.
- If `pipeline.yaml` gains `agent_role` (required by the new loader), the digest changes and `sdlc resume` exits 1 with "the stage table changed".
- If `pipeline.yaml` is kept without `agent_role` to keep the digest, the new loader rejects it as a load error.
- `Step` is `extra="forbid"`, so the old code cannot load the new `pipeline.yaml` either.

All three combinations obey the ADs, and none of them resumes the run. Its step records also have no `UsageRecord`. If the aggregator reads only records, $15.59 of spend is missing from `sdlc usage`. If it falls back to `StepRecord.cost`, it has no `agent_role` to group by.

**Fix (AD-7, AD-24, ADR 0002):**
- `agent_role` is an attribution label, not part of the process. Exclude it from the stage-table digest, alongside `LIMITS`. Say in AD-7 that `sdlc resume` accepts changes to `agent_role`, like `model`, `effort` and `max_turns`.
- Snapshot `agent_role` (with `model` and `effort`) into the `StepRecord` at `StepStarted`. A later relabel then does not rewrite history.
- Backfill on load. Every finished `StepRecord` without a usage record gets one, with `cost_usd = cost`, zero tokens, `usage_complete: false`, and `agent_role` from the current pipeline or `unknown`. The record is then appended to the ledger (U2 order).
- Every new `StepRecord` and `RunState` field defaults to empty, so `state.json` written by the current code validates.
- Add a test that loads a `state.json` fixture from before this change and resumes it under the new pipeline.

## U2 — high: crash ordering between `state.json` and the ledger, and an undefined `attempt`

**Units:** `store.save` (AD-4: atomic `state.json`) and the ledger append (AD-24: "`store` appends it … a replay skips keys already in the ledger").

**Scenario A (ledger first):** step 12 finishes `done` at $1.40. Unit A appends the ledger line, then the process dies before `state.json` is renamed. On load, step 12 is still `running`. AD-6 resets the worktree and discards the work, and `StepInterrupted` charges the full cap ($3) with status `failed`, `interrupted`. The new `UsageRecord` has the same `(run_id, 12, attempt)` key, so dedupe drops it. The ledger now says step 12 passed for $1.40. The state says it failed for $3. The rerun gets seq 13. The ledger total is $1.60 below `budget.spent`, and the "failed" count is wrong.

**Scenario B (state first, nothing re-appends):** unit B saves the state, then dies before appending. AD-24 describes skipping on replay but names nothing that replays. The record is in `state.json`, never in the ledger, and `sdlc usage` misses it for good.

**`attempt` is undefined.** `seq` already identifies one start: `_step_started` takes `next_seq`, and a superseded or interrupted step gets a new `seq` when it reruns. One unit sets `attempt = 1` always. Another sets it to the number of earlier records with the same `(scope, stage, step, round)`. Both keys are unique, but "number of attempts" in `sdlc usage` differs between them.

**Fix (AD-4, AD-24):**
- The ledger is a projection of `state.json`, never ahead of it. Order: apply, save `state.json`, then append the ledger lines for records that are finished in the saved state.
- On every load (run, resume and `sdlc usage`), `store` reconciles. Any finished record of the run whose key is missing from the ledger is appended. This is the replay.
- A record is appended only once its step status is final (`passed`, `failed`, `escalated`, `superseded`). It is never rewritten.
- The key is `(run_id, seq)`. `attempt` is an informational field, defined as 1 + the number of earlier records with the same `(scope, stage, step, round)`.
- The ledger can be rebuilt with `sdlc usage --rebuild` from `runs/*/state.json`, so a torn or lost ledger can be repaired.

## U3 — high: AD-25 says budgets read the ledger; AD-11 says they read `state.json`

**Units:** `core/scheduler.py` (AD-11: `spent + reserved + cap ≤ run_budget_usd`, with `spent` in `state.json`) and a reader of AD-25's last bullet ("Gates, budgets and reports use the ledger (AD-24)").

**Scenario:** unit A gates on `budget.spent`. Unit B, following AD-25, sums the cross-run `usage.jsonl` for the run. That sum is not a pure input. It is a file read (AD-1), it reads an audit-like log for a decision (AD-4), and it differs from `spent` after U1 or U2 or after float reordering. On the in-progress run, without a backfill, unit B sees $0 spent, while unit A sees $15.59.

The 80% warning has the same split. AD-24 phrases it with spent + reserved, so it reads `state.json`. A unit reading AD-25 computes it from the ledger.

**Fix (AD-25, AD-24, AD-11):**
- Reword AD-25: "Telemetry is for observing only. Budgets and the warning use `state.json` (AD-11). Reports (`sdlc status`, `sdlc usage`, the retrospective summary) use `core/usage.py` over usage records."
- Say which source each report uses. `sdlc status <run>` sums the `StepRecord`s in `state.json`. `sdlc usage` sums the ledger after reconciling (U2).
- `sum(cost_usd)` of a run equals `budget.spent`, compared to the cent. A test asserts this after the crash scenarios in U2.

## U4 — high: telemetry left on when it is "off", and a stdout exporter

**Units:** `adapters/claude.py` env builder (AD-8: strip `CLAUDE_CODE_RETRY_WATCHDOG*` and `CLAUDE_CODE_EFFORT_LEVEL`; AD-23: agents inherit exactly the preflight-checked env) and AD-25 ("When telemetry is off, the orchestrator does not touch any telemetry variable"; "never sets `OTEL_LOG_USER_PROMPTS`…").

**Scenario A (privacy):** the owner's shell or `~/.claude/settings.json` `env` block has `CLAUDE_CODE_ENABLE_TELEMETRY=1` and `OTEL_LOG_USER_PROMPTS=1` from other work. With `telemetry.enabled: false`, AD-25 forbids touching them, so every agent exports full prompts to the owner's collector, without `sdlc.*` labels. With `enabled: true`, the orchestrator also "never sets" them, but it inherits them. The rule that is meant to prevent leaks only constrains what the orchestrator adds.

**Scenario B (parsing):** `OTEL_METRICS_EXPORTER=console` is allowed. The console exporter writes metric dumps to the process's stdout. That is the same stream `--output-format json` uses, and AD-8 parses it into the `StepResult`. A unit that parses the whole stdout fails every step as `error`: a failed round for every step, the cap charged, then escalation. A unit that parses the last JSON object does not. Verify this on the CLI version in use, and save a fixture.

**Fix (AD-25, AD-8, AD-23):**
- Always remove `OTEL_LOG_USER_PROMPTS`, `OTEL_LOG_TOOL_DETAILS` and any other `OTEL_LOG_*` content switches from the agent env, whether telemetry is on or off.
- When `enabled: false`, also remove `CLAUDE_CODE_ENABLE_TELEMETRY` and `OTEL_*_EXPORTER`. "Off" then means off.
- Preflight warns, or refuses, when a settings-file `env` block sets these variables. The orchestrator cannot strip them there, and the CLI applies settings env after the process env. Use the same files as AD-23.
- `OTEL_EXPORTER_OTLP_HEADERS` (collector credentials) passes through only when `enabled: true`.
- Allow only `otlp` in pipeline runs. Refuse `console`, like `prometheus`, or require a verified stderr destination.

## U5 — medium: the `status` field mixes three vocabularies

**Units:** `core/usage.py` (counts "failed or superseded") and the record writer (AD-24 lists statuses as "passed, failed, error, timeout, superseded or interrupted").

**Scenario:** AD-5 has a closed status set. `error` and `timeout` are not statuses. `error` is an AD-18 outcome, `error_timeout` is a `subtype`, and `interrupted` is a boolean on a `failed` record. Unit A writes `status: "timeout"`. Unit B writes `status: "failed"` and `outcome: "error"`. "How many failed" then differs by unit, and `--by` grouping splits.

**Fix (AD-24, AD-5):** the record carries `status` (AD-5 enum, final only), `outcome` (AD-18 enum or null), `subtype` (raw CLI string or null) and `interrupted` (bool). "Failed" in `sdlc usage` means `status = failed`. Superseded is counted separately.

## U6 — medium: script steps, judges, and `agent` vs `agent_role`

**Units:** the loader (AD-24 requires `agent_role` only on `agent` and `judge` steps) and the recorder (AD-24: "Every finished step attempt gets one `UsageRecord`").

**Scenario:**
- Script steps are `fr_ac_check`, `tests`, `ci_green` and AD-12's commit, rebase, push and PR steps, all with cap 0. Unit A writes records for them with `agent_role: null`. Unit B writes none. `--by agent` gets a `null` bucket in A only. Its attempt and failed counts include every red `ci_green` poll and failed rebase, which are not agent spend.
- A step with `agent: security-compliance-reviewer` can declare `agent_role: Pompiliu (Analyst)`, and the loader accepts it. Two identity fields can disagree.
- Every judge across stages is `Judge (gate check)`. AD-10 says a judge never runs as the agent that produced the work, but no check compares roles.

**Fix (AD-24, AD-7, AD-10):**
- Script steps get no `UsageRecord`. Their count and wall time can go into `events.jsonl`.
- When `agent` is set, `agent_role` must equal the role that `settings.agents` maps that agent name to. Keep the map in `settings.agents` as `{role: agent-name | null}`.
- At load, a `judge` step's `agent_role` must be `Judge (gate check)`, and a writing step's must not be.

## U7 — medium: ledger location under dry-run and `--runs-dir`, and concurrent appends

**Units:** `store` (AD-24 fixes the path `_bmad-output/sdlc/usage.jsonl`) and AD-2 (dry-run uses a temporary run dir, and fakes mutate nothing).

**Scenario:**
- Dry-run passes `runs_dir=mkdtemp(...)`. Unit A writes the ledger at the literal repo path, so every `sdlc run --dry-run` appends fake costs to the real cross-run ledger. Run IDs have one-second resolution and keep the same format, so keys can even collide with real runs.
- Unit B writes the ledger at `runs_dir.parent / "usage.jsonl"`. That is `$TMPDIR/usage.jsonl`, which all dry-runs share.
- `cli.py` has no lock today (AD-22 is not implemented). Two `sdlc` processes, or a writer and `sdlc usage`, can interleave. Python text-mode `write` can split a long line, `modelUsage` makes lines long, and "skip keys already in the ledger" is check-then-append, a race.

**Fix (AD-24, AD-2, AD-22):**
- The ledger lives at `<runs-dir>/../usage.jsonl` only for the default runs dir. With `--dry-run` or `--runs-dir`, it lives at `<runs-dir>/usage.jsonl`. Dry-run never writes the repo ledger.
- Append each record with a single `os.write` on an `O_APPEND` fd, then `fsync`.
- Readers skip an unparsable last line, and `core/usage.py` dedupes by key (first wins), so a duplicate from a race is harmless.
- `UsageRecord` carries a schema version `v` and is read with `extra="ignore"`. Reading is the exception to the project's `extra="forbid"` convention. Otherwise one added field in a later version makes `--all` fail on every older line, or the reverse.

## U8 — medium: subagent tokens and which `usage` to trust

**Units:** `adapters/claude.py` (AD-24: parse top-level `usage` and `modelUsage`) and `core/usage.py`.

**Scenario:** AD-24 says subagents are included "because `claude -p` reports them in the step's totals". That holds for `total_cost_usd` and per-model `modelUsage`. The top-level `usage` block has not been shown to include subagent turns, for example `bmad-code-review` fanning out reviewers, or a skill calling a Haiku subagent. Unit A takes tokens from `usage`. Unit B sums `modelUsage`. For skill steps that spawn subagents, the two disagree, and `cost_usd` does not match the tokens in A.

**Fix (AD-24):**
- Tokens are the sum over `modelUsage`. The top-level `usage` is kept as a second field.
- Set `usage_complete: false` when the sum of `modelUsage[*].costUSD` and `total_cost_usd` differ by more than 1 cent.
- Record a live fixture of a skill step that spawns a subagent. Add it to the Open Questions row about the JSON shape.
- State the meaning: "per agent" means per orchestrated step role. A subagent persona is charged to the step that started it.

## U9 — low: retrospective summary scope and lifecycle

**Units:** the summary writer (AD-24: "the epic's usage by agent, stage and round", written to `<worktree>/.sdlc/usage-summary.md` before the retrospective step) and AD-6 and AD-22.

**Scenario:**
- An epic's stories can finish in later `sdlc story` or `sdlc fix` runs. Unit A summarises the current run's `state.json`. Unit B filters the cross-run ledger by `story:<N>-*` and `learn:<N>-*` scopes. Planning stages (scope `run`) belong to no epic in either unit.
- On a retro fix round, one unit rewrites the summary, which now includes the failed retro attempt. Another keeps the first one.
- The path is fine under AD-9 and AD-6: `.sdlc/` is in the worktree and survives `git clean -fd` without `-x`.
- "Aglaia does not edit `sdlc/pipeline.yaml`" is not enforced. AD-21's revert list does not include `sdlc/**`. A learn PR that edits the stage table changes the main checkout's `pipeline.yaml`, and every open run then hits U1's resume refusal.

**Fix (AD-24, AD-21):**
- The summary is built from the ledger after reconciling. It filters by epic from the scope key, lists planning usage (scope `run`) in a separate section, and is regenerated before every attempt.
- Add `sdlc/pipeline.yaml` and `sdlc/prompts/**` to the paths reverted on `learn/*` branches. Action items go into the retro document only.

## U10 — low: `OTEL_RESOURCE_ATTRIBUTES` formatting and the slug

**Units:** env builder (AD-25: "extended with `sdlc.agent` (a slug such as `marghioala_developer`)…") and any dashboard.

**Scenario:**
- The slug rule is not given. `Veronel (Security & Compliance)` becomes `veronel_security_compliance` in one unit and `veronel_security__compliance` in another. `Judge (gate check)` has a space.
- The variable is a comma-separated `k=v` list. Spaces, commas, `;`, `"` and `\` in values must be percent-encoded, and the CLI rejects or drops values with spaces. Scope values like `story:1-2-x` are legal, but nothing guarantees future step IDs are.
- "Extended" does not say who wins when the runner's value already has an `sdlc.*` key.
- There is no `sdlc.seq`, so telemetry cannot be joined to a ledger row.

**Fix (AD-25):**
- Define one function in `core`: `slug(role) = re.sub(r"[^a-z0-9]+", "_", role.lower()).strip("_")`. Add a table test over `settings.agents` that also asserts the slugs are unique.
- Percent-encode every value.
- The `sdlc.*` keys replace any inherited key of the same name. Other inherited keys are kept.
- Add `sdlc.seq` and `sdlc.round`.

## U11 — low: warning re-arm, float sums, privacy of ledger rows

- **Warning re-arm.** "Once per run" breaks after `sdlc resume` raises `run_budget_usd`. One unit never warns again. Another re-arms against the new limit. **Fix:** store `usage_warned_at_limit` and re-arm when `budget.limit` changes. Use `settings.usage_warn_ratio`, not the literal "80%" in the AD text.
- **Float sums.** `budget.spent` is a running float. `core/usage.py` sums in a different order, so a strict equality test fails at random. **Fix:** compare to the cent, or keep costs as integer micro-dollars in records.
- **Privacy.** AD-24 does not say what the ledger must not contain. A unit could copy `errors[]` or `reason` into the record, and those can quote prompt or file content. **Fix:** the record holds only the fields AD-24 lists: numbers, IDs, enums and a timestamp. It holds no free text. The ledger stays gitignored under `_bmad-output/sdlc/`, which AD-9 already covers.

---

## Checked and found consistent

- AD-1: `core/usage.py` is pure as long as the ledger reaches it as data. `cli`/`store` read the ledger, and the warning is a core action carried out by `app/` through the notifier port.
- AD-9: the summary path is inside the worktree under `.sdlc/`, and no prompt names `_bmad-output/sdlc/`.
- AD-11 and AD-18: an interrupted or timed-out attempt charges its cap. The adapter already returns `total_cost_usd = cap` on a timeout, so AD-24's rule that the cost equals the charged amount matches the code. A superseded attempt charges its reported cost, and both cases get a record.
- AD-23: the orchestrator adds no credentials. Collector headers come only from the runner env. U4 limits them to `enabled: true`.
