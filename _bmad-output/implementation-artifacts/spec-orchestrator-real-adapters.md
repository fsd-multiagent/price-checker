---
title: 'Zamolxe (Orchestrator) real adapters'
type: 'feature'
created: '2026-11-16'
status: 'done'
route: 'dispatch'
review_loop_iteration: 0
baseline_commit: 'f73cc6e55f0aa6320636a6be11b48030981f0892'
context:
  - '{project-root}/_bmad-output/planning-artifacts/architecture/architecture-orchestrator-2026-11-16/ARCHITECTURE-SPINE.md'
  - '{project-root}/_bmad-output/implementation-artifacts/spec-orchestrator-dry-run.md'
---

<frozen-after-approval reason="human-owned intent — do not modify unless human renegotiates">

## Intent

**Problem:** `sdlc run` without `--dry-run` refuses to start, because only fake adapters exist (deferred-work entry 1). Until real adapters exist, the pipeline cannot run a single real stage.

**Approach:** Implement the real `claude`, `git`, `gh`, `commands` and `notifier` adapters behind the existing ports in `orchestrator/src/sdlc/ports.py`, wire them into a real `sdlc run`, `status` and `resume`, and add a preflight check. The app and core stay unchanged unless a port contract forces a change. No test makes a paid call or touches GitHub.

## Boundaries & Constraints

**Always:**
- Ports are the contract. The real adapters pass the same app-level tests as the fakes where behavior overlaps.
- Subprocesses go through `asyncio.create_subprocess_exec` with an argument list, never a shell string. Each runs in its own session (`start_new_session=True`). A timeout kills the whole process group (AD-3).
- `claude`: run `Invocation.argv` with `stdin`, `cwd` and `env` (the parent environment plus `inv.env`). Parse stdout with the existing `parse_result`. A timeout returns a `StepResult` with `subtype: "error_timeout"` and `is_error: true`. A non-zero exit with unparseable stdout raises `AdapterError`.
- `git`:
  - Run in the main checkout, with worktrees in `<repo-parent>/<repo-name>-worktrees/<branch with "/" replaced by "__">`.
  - Push with `--force-with-lease` and call `refuse_main` first (AD-12).
  - Tags: if the tag already points at the SHA, reuse it; otherwise move it with `tag -f`, locally only (deferred-work, triage row 38).
  - `restore_paths` resets the matching tracked paths to the scope head and removes untracked matches.
  - `reset_hard` is `reset --hard <sha>` plus `clean -fd`, never `-x`.
- `gh`:
  - `ensure_pr` looks up the PR by head branch, then creates it.
  - `checks_green` polls `gh pr checks <n> --required --json` every 60 s up to `timeout_min`. Zero required checks counts as not green (AD-20).
  - `merge` is `gh pr merge <n> --auto --squash --match-head-commit <sha>`, then polls until the PR is `MERGED` and returns the merge commit SHA, or None on timeout or close.
  - `ensure_escalation_issue` uses `gh issue list --label escalation --state all --json number,body`, matches the marker in code, and adds a comment when it reuses an issue.
- `commands`:
  - `tests` and `terraform_plan` run the commands listed in a fenced `yaml` block tagged `sdlc-commands` under "Running and verifying" in the worktree's `AGENTS.md`. The block maps a name to a list of commands. A missing name exits 1 with "no <name> commands in AGENTS.md" (fail closed, T-8).
  - `fr_ac_check` is a pure core function over the PRD files: every `FR-n` defined in the PRD must be followed by at least one `AC-` ID before the next `FR-` definition.
- `notifier`: macOS `osascript -e 'display notification …'`; a no-op on other platforms; errors are logged, never raised.
- Preflight, before a real run creates its run dir:
  - `claude`, `git` and `gh` are on PATH
  - `gh auth status` succeeds
  - remote `origin` exists
  - the main checkout is clean
  - each failure exits 1 with one line naming what is missing
- Real runs use the `_bmad-output/sdlc/runs` runs dir and `SystemClock`.

**Never:**
- No paid `claude` call and no GitHub call in the default test suite. Tests use stub `claude` and `gh` executables on a temporary `PATH`, and real `git` in temporary repos.
- No push to `main`, no `gh pr merge` without `--auto --match-head-commit`, no `--admin`.
- No lock, queue, `sdlc story`/`sdlc fix`, or `--until` (still deferred).

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Claude happy | Stub prints a success JSON | `StepResult` parsed; `cwd` and env applied | N/A |
| Claude timeout | Stub sleeps past `timeout_s` and spawns a child | Process group killed; `error_timeout` result | No orphan process left |
| Claude garbage | Stub exits 1, prints text | `AdapterError` | The app records a failed step |
| Worktree lifecycle | Temp repo with a bare `origin` | `ensure_worktree` creates the sibling dir and branch from `origin/main`; a second call reuses it | N/A |
| Reset keeps `.sdlc/` | Dirty worktree, ignored `.sdlc/x` | Tracked and untracked changes gone; `.sdlc/x` kept | N/A |
| Rebase conflict | Conflicting commit on `origin/main` | `rebase` returns False; `abort_rebase` leaves a clean tree | N/A |
| Push refusal | branch `main` or `+main` | `AdapterError` before any git call | N/A |
| Tag re-pass | Tag exists at an old SHA | Tag moved to the new SHA | N/A |
| PR reuse | Stub `gh` reports an open PR for the head | Number returned; no create call | N/A |
| No required checks | Stub returns `[]` | `checks_green` is False | N/A |
| Merge | Stub reports MERGED on the second poll | Merge SHA returned; argv has `--auto --squash --match-head-commit` | None on timeout |
| Escalation reuse | Stub lists an issue with the marker | Same number; one comment added | N/A |
| Commands missing | `AGENTS.md` without `sdlc-commands` or without the name | exit 1, message names the command | N/A |
| FR/AC | PRD with FR-1 (has AC-1.1) and FR-2 (none) | exit 1 naming FR-2 | N/A |
| Preflight | `gh` missing from PATH | Exit 1, "gh not found", no run dir | N/A |

</frozen-after-approval>

## Code Map

- `orchestrator/src/sdlc/ports.py` -- port Protocols; keep signatures. The `key` arguments exist for fakes only.
- `orchestrator/src/sdlc/adapters/claude.py` -- `build_argv`, `parse_result`; add the runner class here.
- `orchestrator/src/sdlc/adapters/git.py` -- `refuse_main`, `commit_message`; add the real `Git` here.
- `orchestrator/src/sdlc/adapters/fake/__init__.py` -- reference behavior for every port method; mirror its return contracts.
- `orchestrator/src/sdlc/app/loop.py` -- callers: `ensure_worktree` (about line 297), the script step `match` (about line 590), escalation (about line 729).
- `orchestrator/src/sdlc/cli.py` -- `NOT_IMPLEMENTED` at line 24 and the non-dry branches at lines 109 and 151: replace them with real wiring and preflight.
- `AGENTS.md` "Running and verifying" -- add the `sdlc-commands` block, with orchestrator `tests` as the only entry for now.

## Tasks & Acceptance

**Execution:**
- [x] `orchestrator/src/sdlc/adapters/proc.py` -- shared async subprocess helper: argument list, cwd, env, timeout, group kill, captured output.
- [x] `orchestrator/src/sdlc/adapters/claude.py` -- `ClaudeCli.run`.
- [x] `orchestrator/src/sdlc/adapters/git.py` -- `GitCli`, every `Git` port method.
- [x] `orchestrator/src/sdlc/adapters/gh.py` -- `GhCli`, every `Gh` port method.
- [x] `orchestrator/src/sdlc/adapters/commands.py`, `core/fr_ac.py` -- the `sdlc-commands` reader and the FR/AC check.
- [x] `orchestrator/src/sdlc/adapters/notifier.py` -- macOS notifier with a no-op fallback.
- [x] `orchestrator/src/sdlc/adapters/real.py`, `cli.py` -- `real_ports(repo)`, preflight, real `run`/`status`/`resume`; remove `NOT_IMPLEMENTED`.
- [x] `AGENTS.md` -- the `sdlc-commands` block, and replace the "non-dry run exits 1" note.
- [x] `orchestrator/tests/` -- one test per matrix row (named `test_real_<row>`), with stubs under `tests/stubs/`. Add an opt-in `@pytest.mark.live` test of one real `claude -p` call, skipped unless `SDLC_LIVE=1`.

**Acceptance Criteria:**
- AC-1: Given a temp repo with a bare origin and stub `claude`/`gh` on PATH, when a real `sdlc run --pipeline <one-stage test pipeline>` runs, then the step runs through the real adapters, state is persisted under `_bmad-output/sdlc/runs`, and `sdlc status` shows it.
- AC-2: Given the repo, when `uv run pytest -q` and `uv run ruff check && uv run ruff format --check` run in `orchestrator/`, then all pass, live tests skipped.
- AC-3: Given the real ports, when `sdlc run --dry-run` runs, then it is unchanged (exit 0).

## Implementation Notes

- **Commands without a shell:** each `sdlc-commands` entry is split with `shlex` and run with the worktree as cwd; `cd`, `&&`, `|`, `;` and redirections are refused (exit 1). The orchestrator entry is therefore `uv run --directory orchestrator pytest -q`.
- **FR/AC definitions:** an FR is defined by a line that starts with its ID after a heading, list, table or bold marker. An FR defined twice passes when either definition has an `AC-`. No PRD file, or no FR definition at all, fails closed.
- **Tags:** `tag` is local only and runs in the main checkout; `push` pushes `HEAD:refs/heads/<branch>` from the worktree with `--force-with-lease`, after `refuse_main` on both the branch and the refspec.
- **gh timeouts:** `GhCli(poll_s=60, merge_timeout_min=30)`; tests pass small values. The escalation issue path runs `gh label create escalation --force` once before the first create, so a repo without the label still works.
- **Notifier:** `notify` stays synchronous (port unchanged); the `osascript` process runs as a task, and the CLI awaits `drain()` after the run.
- **Re-escalation after resume:** the adapter comments on a reused issue, but the core opens an escalation only while its `issue` is unset, so a scope that escalates again after `sdlc resume` still posts nothing new. Left in deferred-work (core change, out of scope here).
- **Review fixes (pass 1):** the `sdlc-commands` block must equal the one on `origin/main`; commands matching the guard denials (G1-G3) are refused; one deadline per command name; `checks_green` keeps polling on empty or failed reads and needs at least one `pass`; `merge` checks the PR state first, tolerates failed polls and turns off auto-merge on timeout; a closed escalation issue is reopened; push uses an explicit lease (last pushed SHA, or the remote SHA before the first fetch); stale worktrees are rebuilt; a non-conflict rebase failure raises; the preflight lists every problem and also checks `git config user.email` and gh >= 2.40; a timed-out agent step is charged its cap.
- **Obsolete test:** `test_non_dry_run_exits_1` became `test_non_dry_run_stops_at_preflight` (empty PATH), so the suite never starts a real run against the real repo.

## Spec Change Log

## Review Triage Log

Pass 1 (2026-11-16). Reviewers: BH = Blind Hunter, VG = Verification Gap, EC = Edge Case Hunter. "Checked" means the claim was confirmed by reading the source.

| # | Finding | Verdict | Evidence | Route |
|---|---|---|---|---|
| 1 | BH5: gate commands come from the worktree's AGENTS.md, which agents can edit | high | Checked: `ProjectCommands` reads `<worktree>/AGENTS.md`; run-level scopes do not restore it | patch (compare with origin/main) |
| 2 | BH6: gate commands bypass the guard denials | high | Commands run outside the hooks; only shell operators are refused | patch |
| 3 | BH7: bare `--force-with-lease` is defeated by the earlier fetch | medium | `fetch --prune` refreshes the lease ref before push | patch |
| 4 | BH1, EC12, EC13, EC14: `checks_green` fails at once on empty, None or a gh error; an empty bucket set counts as green | high | Checked: `if not checks: return False` on the first poll; `set() <= _PASS` | patch |
| 5 | BH2: all-`skipping` checks count as green | medium | `_PASS` includes skipping | patch |
| 6 | BH3, EC15, EC16, EC17: merge on resume, transient view errors, MERGED without SHA, auto-merge left on after timeout | medium | No initial view; `_json` raises; no `--disable-auto` | patch |
| 7 | BH4: a closed escalation issue is reused without reopening | medium | `--state all` match, then comment only | patch |
| 8 | BH4b, EC19: `--limit 1000` can miss a marker | low | Needs over 1000 escalation issues | reject |
| 9 | BH8, EC27, VG-o1: `killpg` after reap can hit a reused PID | medium | Checked: unconditional `_kill_group` in `finally` | patch |
| 10 | BH9, EC25: diff shows untracked files by name only | medium | Reviewers cannot see new file content | patch |
| 11 | BH11: `fr_ac` loose matching (FR-1.2, last span to EOF) | medium | Regex `\b` and `len(text)` span | patch |
| 12 | EC11: checkbox and blockquote FR definitions missed | low | Direct regex correction | patch |
| 13 | BH11b: AC numbered for another FR still counts | low | Needs an FR-to-AC numbering convention the PRD may not follow | reject |
| 14 | BH12, EC26, EC30: preflight misses user.email and gh version, and stops at the first error | medium | Fails only after a paid step | patch |
| 15 | BH12b: preflight does not check claude login or repo auto-merge settings | medium | No cheap check for login; auto-merge is visible via the API | defer |
| 16 | EC1–EC8: commands parsing (shlex error, attached operators, fenced `#`, unreadable files, per-command timeout, missing cwd) | medium | Each escapes as an exception or misreports | patch |
| 17 | EC4: longer closing fence not recognised | low | Rare; the block is written by Aglaia | reject |
| 18 | EC31: spec claim that redirections are refused does not hold | medium | Attached operators pass | patch (same as row 16) |
| 19 | EC20, EC21, VG4: worktree reuse trusts any `.git`; a leftover dir blocks; re-create untested | medium | `ensure_worktree` checks only `.git` existence | patch |
| 20 | EC22: `a/b` and `a__b` collide | low | Branch names are generated by the orchestrator | reject |
| 21 | EC23: non-conflict rebase failure reported as a conflict | medium | `ok=False` for any non-zero exit | patch |
| 22 | EC24, VG5: renamed or staged-new protected files escape `restore_paths` | medium | Rename detection; the staged path is untested | patch |
| 23 | EC28: unbounded stdout capture | low | Agents' JSON output is small | defer |
| 24 | EC29: a timed-out paid call records $0 | medium | `error_timeout` result has cost 0 | patch |
| 25 | VG1: preflight auth and origin failure branches untested | medium | Pre-verified | patch |
| 26 | VG2: pending-checks polling untested | high | Pre-verified | patch (with row 4) |
| 27 | VG3: commands 127 and 124 untested | medium | Pre-verified | patch (with row 16) |
| 28 | VG5b: label creation unasserted | medium | Pre-verified | patch |
| 29 | BH13: `drain` not in the Notifier port | low | Direct correction | patch |
| 30 | BH13b: commands inherit the full parent env; `cd` refused as a plain word; `--no-verify` uncommented | low | Env is the runner's own; a comment is a direct fix | patch (comment only); rest rejected |
| 31 | VG-o2: `notify` might block outside a running loop | maybe-false | Would need a call path outside `_drive` | reject (low if true) |
| 32 | BH10: deferred-work and ADRs not updated | medium | A new `sdlc-commands` contract without an ADR (D-2) | fixed directly: ADR 0004, deferred-work notes |

## Verification

**Commands:**
- `cd orchestrator && uv run pytest -q` -- expected: all pass, live skipped
- `cd orchestrator && uv run ruff check && uv run ruff format --check` -- expected: clean
- `cd orchestrator && uv run sdlc run --dry-run; echo $?` -- expected: 0
