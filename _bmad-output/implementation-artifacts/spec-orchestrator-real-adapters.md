---
title: 'Zamolxe (Orchestrator) real adapters'
type: 'feature'
created: '2026-11-16'
status: 'draft'
route: 'dispatch'
review_loop_iteration: 0
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
- [ ] `orchestrator/src/sdlc/adapters/proc.py` -- shared async subprocess helper: argument list, cwd, env, timeout, group kill, captured output.
- [ ] `orchestrator/src/sdlc/adapters/claude.py` -- `ClaudeCli.run`.
- [ ] `orchestrator/src/sdlc/adapters/git.py` -- `GitCli`, every `Git` port method.
- [ ] `orchestrator/src/sdlc/adapters/gh.py` -- `GhCli`, every `Gh` port method.
- [ ] `orchestrator/src/sdlc/adapters/commands.py`, `core/fr_ac.py` -- the `sdlc-commands` reader and the FR/AC check.
- [ ] `orchestrator/src/sdlc/adapters/notifier.py` -- macOS notifier with a no-op fallback.
- [ ] `orchestrator/src/sdlc/adapters/real.py`, `cli.py` -- `real_ports(repo)`, preflight, real `run`/`status`/`resume`; remove `NOT_IMPLEMENTED`.
- [ ] `AGENTS.md` -- the `sdlc-commands` block, and replace the "non-dry run exits 1" note.
- [ ] `orchestrator/tests/` -- one test per matrix row (named `test_real_<row>`), with stubs under `tests/stubs/`. Add an opt-in `@pytest.mark.live` test of one real `claude -p` call, skipped unless `SDLC_LIVE=1`.

**Acceptance Criteria:**
- AC-1: Given a temp repo with a bare origin and stub `claude`/`gh` on PATH, when a real `sdlc run --pipeline <one-stage test pipeline>` runs, then the step runs through the real adapters, state is persisted under `_bmad-output/sdlc/runs`, and `sdlc status` shows it.
- AC-2: Given the repo, when `uv run pytest -q` and `uv run ruff check && uv run ruff format --check` run in `orchestrator/`, then all pass, live tests skipped.
- AC-3: Given the real ports, when `sdlc run --dry-run` runs, then it is unchanged (exit 0).

## Implementation Notes

## Spec Change Log

## Review Triage Log

## Verification

**Commands:**
- `cd orchestrator && uv run pytest -q` -- expected: all pass, live skipped
- `cd orchestrator && uv run ruff check && uv run ruff format --check` -- expected: clean
- `cd orchestrator && uv run sdlc run --dry-run; echo $?` -- expected: 0
