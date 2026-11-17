# Adversarial review: spine update 2026-11-17

- **Target:** `ARCHITECTURE-SPINE.md` working-tree diff of 2026-11-17 (AD-5 diagram, AD-7, AD-8, AD-11, AD-18, new AD-23)
- **Scope:** only the amended rules. For each hole there are two units that both obey every AD and still build incompatibly, deadlock or hang.
- **Code read for grounding:** `orchestrator/src/sdlc/core/{scheduler,outcome,state,models}.py`, `app/loop.py`, `adapters/{claude,proc,real,clock}.py`, `cli.py`. Installed CLI: `claude` 2.1.326. `--effort` appears in `--help`. `CLAUDE_CODE_RETRY_WATCHDOG_MAX_USAGE_LIMIT_WAIT_MS` was not verified.

## Verdict

**Not ready to bind.** The `limited` and budget-wait rules add a third run state, "waiting". The exit rule (AD-11), the pure-core contract (AD-1), the reset rule (AD-6) and the gate evaluation do not know about it. As written, a run that hits a plan limit exits with code 2 (or raises "no progress") instead of waiting. If it does wait, the retry runs on a dirty worktree, and the gate then charges a round for the step. 1 critical, 3 high, 2 medium, 3 low.

---

## H1 — critical: the run exits while waiting for `resume_at`

**Units:** `core/scheduler.py` (AD-18: start no step before `resume_at`) and `app/loop.py` (AD-11: "When nothing is runnable or running, the run exits").

**Scenario:** one step is running and a limit is hit. It returns `limited` and goes back to `pending`. `resume_at` = now + 30 min, and no other step is running. On the next tick the scheduler correctly starts nothing. Nothing is running and nothing is runnable, so AD-11 exits. Today's code then emits `Finish(exit_code=2)`, because `not t.actions and not t.running_steps`. The run ends with exit 2, but no scope is escalated and no escalation issue is opened. If an implementation instead returns no action and does not finish, `loop._ticks` raises `LoopError("no progress")`. Both readings obey the ADs. Neither one waits.

**Contributing gaps:**
- AD-1 says time comes in as event fields, but no input carries `now` to `decide` (`TickInputs` has only `approved_domains`). The scheduler cannot compare `now` with `resume_at` without a new input, and the spine does not name one.
- The loop only blocks on `asyncio.wait(self._tasks)`. There is no port or action for "sleep until T". AD-3 forbids threads, so the app needs a `Clock.sleep_until`. The `Clock` port has only `now()`.
- AD-2: dry-run must exit 0 quickly. A `limited` fixture with a real clock would sleep 30 real minutes. Without one, the path is never exercised.

**Fix (AD-11, AD-1, AD-2):**
- Define three run states for each tick: *runnable*, *running*, *waiting*. Waiting means work is pending and held only by `resume_at` or by budget reservations.
- `decide` returns `Wait(until=<resume_at>)` when it is waiting. `TickInputs` gains `now`.
- The app carries out `Wait` through a new `Clock.sleep_until(t)`, or through `asyncio.wait(tasks, timeout=t-now)` when steps are still running.
- The run exits only when nothing is runnable, running or waiting.
- The fake clock advances instantly on `sleep_until`. AD-2 requires at least one `limited` fixture in dry-run.

## H2 — high: a `limited` step keeps its partial edits, and the gate counts it as a failed round

**Units:** AD-6 reset rule versus AD-18 "step goes back to `pending`, consumes no round", and the gate evaluation (`round_records` / `EvaluateGate`).

**Scenario A (dirty retry):**
1. Marghioala (Developer) is limited halfway through editing.
2. AD-6 resets only on a failed round, a timeout, or a step still `running` at load. `limited` is none of these, so nothing resets.
3. "Leftover changes are committed only when a step ends `done`" holds, so the edits stay in the worktree, uncommitted.
4. The retry starts with `base_sha == head_sha`, which AD-6 still holds, but on a dirty tree. The retry prompt contains no discarded patch.
5. When the retry ends `done`, the orchestrator commits whatever the two attempts left behind.
6. A crash during the wait does not help. On load the step is `pending`, not `running`, so AD-6's load-time reset skips it as well.

**Scenario B (round charged):** the round contains a step record whose status is not `passed` or `running`. The current scheduler treats any such record as a failure (`failed = any(r.status not in (RUNNING, PASSED))`) and emits `EvaluateGate`. The gate sees no verdict for the step and fails (AD-10: "a missing verdict fails the gate"). That uses up a round, which contradicts AD-18. Under AD-5 a step record may go `running → pending`, but no AD says whether the gate and round logic see that record or skip it.

**Fix (AD-6, AD-18):**
- Add `limited` to AD-6's reset triggers: save `steps/<seq>.discarded.patch`, then reset to `head_sha`, as for a timeout.
- The limited attempt is closed as a superseded record, like `interrupted` today. It is excluded from round records, gates and verdict paths. A new attempt with a new `seq` is started after `resume_at`.
- State it in one transition: release the reservation, charge the reported cost, mark the record superseded.

## H3 — high: the in-process usage-limit wait turns into a timeout and costs a round plus the full cap

**Units:** `adapters/claude.py` (AD-8 sets `CLAUDE_CODE_RETRY_WATCHDOG_MAX_USAGE_LIMIT_WAIT_MS = timeout − 5 min`) versus outcome derivation (AD-18: any `subtype` other than `success`, so also `error_timeout`, is `error`, which is a failed round) and AD-6 (a timeout resets the worktree).

**Scenario:**
1. A stage has `timeout_min` 30, so the watchdog may wait 25 min.
2. The limit is hit at minute 12. The CLI waits up to 25 min in the process, but only 18 min remain.
3. The orchestrator kills the process group at minute 30. `ClaudeCli` reports `error_timeout` and charges the full `--max-budget-usd` cap, as the code does today.
4. Result: a failed round, a reset, and a full cap charged, for a plan limit that AD-18 promises costs no round.

"Timeout − 5 min" only guarantees the wait fits if the limit is hit in the first 5 minutes.

Other effects:
- While the CLI waits inside the process, the run-wide `resume_at` is not set. Other parallel steps keep starting and hitting the same limit.
- Each one holds a story slot and its reservation for up to 25 min. That is the cause of H6's budget stall.
- With `timeout_min` ≤ 5, the clamp gives 0, which is harmless. The rule only works in that degenerate case.
- The variable name and its meaning are unverified. The spine defers `--max-turns` for exactly that reason.

**Fix (AD-8):**
- Set the variable to `0`, or leave it unset, so the CLI fails fast. Waiting then has one owner, the orchestrator's `resume_at`.
- If the variable cannot be confirmed in the CLI, move it to Deferred, as with `--max-turns`.
- Optional: if a timed-out step's stderr shows a usage-limit wait, classify it as `limited`, not `error`.

## H4 — high: matching `limited` by substring "before any other rule" catches successful results

**Units:** `adapters/claude.py` / `core/outcome.py` (AD-18: `limited` when the result or `errors` *contains* "hit your session limit" and so on, checked first) versus any agent or reviewer whose `result` quotes that text.

**Scenario:** this project builds Zamolxe (Orchestrator) itself.
1. A story that touches `core/outcome.py`, a review of this spine, or a test fixture has a result or `result_block` verdict that quotes "hit your weekly limit".
2. The step ends `subtype: success`, but it is classified `limited`.
3. It retries with no round, re-quotes the text, and is `limited` again. This repeats until `usage_limit_max_wait_h` (12 h) escalates.
4. Each attempt is charged.

"Or another usage-limit message" is an open set, so two implementers will match different strings.

**Fix (AD-18):**
- Test for `limited` only when `subtype != success` or `is_error` is true, and only against the error text: `errors`, or `result` when `is_error`.
- Use one anchored pattern list in one function, with a fixture for each string.
- If the message carries a reset time ("resets 3pm", or the older `usage limit reached|<epoch>` form), set `resume_at = max(parsed reset, now + usage_limit_wait_min)`.

## H5 — medium: the lifecycle of `resume_at` and the cumulative wait is undefined (crash, parallel steps, `sdlc resume`)

**Units:** `store`/`core.apply` (AD-4) versus `sdlc resume` (AD-6 resets only the round counter) versus AD-14 (escalation stops *one scope*, keyed `<!-- sdlc:<run-id>:<scope> -->`).

**Scenarios:**
1. **Persistence.** AD-18 says "the run records `resume_at`". It does not say this happens through an event in `state.json`. One implementer keeps it in memory. After a crash and `sdlc resume`, the run starts at once and hits the limit again.
2. **Parallel results.** Three running steps each return `limited` a few seconds apart.
   - Implementer A adds 3 × 30 min to the cumulative wait, so the run escalates after about 4 h of real time.
   - Implementer B counts wall-clock time.
   - A third resets `resume_at` each time, so it keeps moving later.
3. **Resume after the max-wait escalation.**
   - AD-6 resets only "the round counter of the escalated `(scope, gate)`".
   - The cumulative wait is not reset, so the first `limited` after a resume escalates at once.
   - Or, if the resume clears `escalated` but not `resume_at`, the run can sit until an old `resume_at` passes.
4. **Escalation scope.** "The run escalates" names no scope. The AD-14 marker and the AD-13 idempotency key need one.

**Fix (AD-18, AD-6, AD-14):**
- Record a `UsageLimited(at, resume_at)` event through `core.apply`. Set `resume_at = max(existing, new)`.
- Cumulative wait is wall-clock time from the first `limited` of an episode. Any `done` step ends the episode and sets the counter to 0.
- `sdlc resume` clears `resume_at` and the cumulative wait.
- Run-level escalations (usage limit and budget) use the scope `run`, marker `<!-- sdlc:<run-id>:run -->`.

## H6 — medium: the budget wait can stall with nothing running, and can starve a large step

**Units:** `core/scheduler.py` (AD-11: wait if `spent + cap ≤ limit` but `spent + reserved + cap > limit`) versus `core/state.py` reservation bookkeeping.

**Scenarios:**
1. **Stall (exit or hang).** The wait condition is computed from the `reserved` total. It does not check that some step holding a reservation is running. A reservation can stay held by a step that is no longer running, for example a `limited` record sent back to `pending` without release (H2), or a crash path. Then the scheduler waits, nothing is running, and under H1 the run exits 2 with no escalation, or hangs.
2. **Starvation.** A $10 Architect step waits for reservations. In the same tick a later unit's $2 step fits and starts. This repeats with each new story. Under `max_parallel` > 1 the big step can wait until the stories run out. No AD gives the order.

**Fix (AD-11):**
- Invariant, checked in `core.apply`: `reserved == Σ cap of step records in running`.
- Wait only while at least one step is running. Otherwise escalate as budget exhausted (cannot happen if the invariant holds; keep it as a tripwire).
- Head-of-line: once a step waits for budget, no step queued after it may start in that tick.

---

## Lower-priority findings

- **L1 — low (AD-23 coverage).**
  - Allowed, and not refused: `CLAUDE_CODE_OAUTH_TOKEN` (`claude setup-token`) and the `/login` keychain session. Both are subscription auth.
  - Not seen by the preflight, though they also bill outside the plan:
    - an `apiKeyHelper` in user or project `settings.json`
    - `CLAUDE_CODE_USE_BEDROCK`, `CLAUDE_CODE_USE_VERTEX` and `CLAUDE_CODE_USE_FOUNDRY`
    - `ANTHROPIC_BASE_URL`
  - "Is set" should mean "non-empty". Otherwise `ANTHROPIC_API_KEY=` is refused.
  - Tests: `preflight` reads `os.environ` directly. Tests that monkeypatch it work. The opt-in live test (`SDLC_LIVE=1`) calls `claude` without a preflight, so it can bill a developer's key without warning. Either route the live test through the same check, or say that it is exempt.
  - `sdlc resume` already runs the preflight (`cli.py:218`), which is good. The spine should say so, because AD-23 binds only "real run".
- **L2 — low (AD-7 effort versus the resume digest).** `cli.py` `LIMITS` lets a resume change `model` but not `effort`. Changing a stage's `effort` therefore refuses the resume as a changed stage table. The spine has no rule that lists which per-stage fields a resume may change. Add `effort` beside `model`, and write the list into AD-7 or ADR 0002.
- **L3 — low (AD-7/AD-8 effort validity).**
  - `xhigh` and `max` are not accepted by every model.
  - A bad `--model`/`--effort` pair makes `claude` exit with non-JSON output. That raises `AdapterError`, which stops the run with an internal error (exit 1) instead of failing one step.
  - Fix: validate `effort` against `model` at pipeline load where it is known. Otherwise, map a non-JSON non-zero exit to `subtype: error_cli`, so that it is a failed round.
