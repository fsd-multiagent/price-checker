# Review: technology currency of the Zamolxe (Orchestrator) spine

- Spine: `../ARCHITECTURE-SPINE.md` (draft, 2026-11-16)
- Lens: were the technology claims checked against reality, or asserted from training data?
- Reviewed: 2026-11-16, on the owner's Mac (the planned local runner)
- Constraints: no paid `claude -p` call was made. No retailer domain was contacted. No project file was edited except this one.

## Verdict

**Pass with fixes.** Every version in the Stack table matches the installed binaries or PyPI, except `uv`, which is two minor versions behind. Every `claude -p` flag in AD-8 exists in CLI 2.1.325, and every JSON field it names is in the CLI's result schema. gh 2.96.0 supports everything AD-12, AD-13 and AD-15 need. The gaps are in how the spine uses these tools:

- `result` is missing on error results.
- The issue-search key will not work as written.
- Auto-merge has repository prerequisites that nobody has verified.
- The judges' read-only restriction is weaker than the rule claims.
- One pytest-asyncio setting must be set.

No blockers.

## How each claim was checked

| Claim | Method | Result |
| --- | --- | --- |
| Claude Code CLI 2.1.325 | `claude --version` | `2.1.325 (Claude Code)` ✅ |
| AD-8 flags `-p`, `--output-format json`, `--max-budget-usd`, `--no-session-persistence`, `--allowedTools`, `--agent` | `claude --help` | All present. `--max-budget-usd` and `--no-session-persistence` are marked "only works with --print" ✅ |
| Result fields `subtype`, `is_error`, `total_cost_usd`, `num_turns`, `permission_denials`, `result` | Result-message schema found in the installed CLI binary (`strings`), plus the step 0.7 smoke logs (bmad-reference §4) | All present on `subtype: "success"`. The error variants have **no `result`** and carry `errors: string[]` instead (see F1) ⚠️ |
| Error subtypes | Same schema | `error_during_execution`, `error_max_turns`, `error_max_budget_usd`, `error_max_structured_output_retries` |
| `CLAUDE_CODE_OAUTH_TOKEN` for `claude setup-token` | `claude setup-token --help`; the CLI's own output string | The CLI prints "Use this token by setting: export CLAUDE_CODE_OAUTH_TOKEN=<token>" ✅. There is also a `CLAUDE_CODE_OAUTH_TOKEN_FILE_DESCRIPTOR` variant. The token needs a Claude subscription |
| gh 2.96.0 | `gh --version` | `2.96.0 (2026-07-02)` ✅ |
| Enable auto-merge | gh man page `gh-pr-merge.1` (the guard's G2 rule blocks `gh pr merge --help` as a command, so I read the man page instead) | `--auto`, `--disable-auto`, `--match-head-commit <SHA>`, `-s/-m/-r`, `-d` ✅ |
| Find a PR by branch | `gh pr list --help`, `gh pr view --help` | `gh pr list --head <branch> --state all --json …` and `gh pr view <branch>` ✅. `--head` does not accept `owner:branch` |
| Read merged state | `gh pr view --json` field list | `state`, `mergedAt`, `mergedBy`, `autoMergeRequest`, `mergeStateStatus`, `headRefOid` ✅ |
| `gh pr checks` | `gh pr checks --help` | `--watch`, `--fail-fast`, `--required`, `--json` (with a `bucket` of pass/fail/pending/skipping/cancel). Exit code 8 means pending ✅ |
| Create and search issues by body text | `gh issue create --help`, `gh issue list --help` | `--body`/`--body-file`, `--label`, `--search`, `--state all`, `--json body` ✅. The key format has a problem (F2) |
| git 2.54.0 with `git worktree` | `git --version`, `git worktree -h` | ✅. `add -b`, `remove -f`, `prune` and `list --porcelain` are all available |
| Python ≥ 3.12, runner 3.14.7 | `python3 --version` | 3.14.7 ✅. pydantic, pytest and pytest-asyncio all list Python 3.10–3.15 classifiers, so a 3.12 floor is safe |
| uv 0.11.32 | `uv --version`; PyPI | Local Homebrew has 0.11.32, but PyPI latest is **0.13.8** (2026-11-12) ⚠️ (F6) |
| pydantic 2.14.3 | PyPI JSON | Latest, released 2026-11-05, requires Python ≥ 3.10 ✅ |
| PyYAML 6.0.3 | PyPI JSON | Latest ✅ |
| pytest 9.2.0 | PyPI JSON | Latest, released 2026-10-27 ✅ |
| pytest-asyncio 1.5.0 | PyPI JSON | Latest, released 2026-11-12, `requires pytest>=8.4` ✅ |
| pytest 9.2.0 + pytest-asyncio 1.5.0 together | Ran a sample async subprocess test with `uvx` on Python 3.12 and 3.14 | Passes. Without `asyncio_default_fixture_loop_scope`, pytest-asyncio emits a `PytestDeprecationWarning`, which becomes an INTERNALERROR when warnings are errors (F5) |
| ruff 0.17.5 | PyPI JSON | Latest (2026-11-12) ✅. It is **not installed** on the runner (`ruff: command not found`); add it as a dev dependency |
| pydantic frozen models | Ran a script on 2.14.3 | Assigning to a field raises `frozen_instance` ✅. But nested `dict`/`list` fields can still be changed, and `model_copy(update=…)` skips validation (F4) |

## Findings

### F1 (medium): `StepResult.result` is absent on error results

- **Where:** AD-8 (the `StepResult` fields) and AD-11 (`is_error`).
- **Evidence:** In the CLI 2.1.325 result schema, only `subtype: "success"` carries `result`. The four `error_*` subtypes carry `errors: string[]` instead. `error_max_budget_usd` is the subtype you get when `--max-budget-usd` trips, and the spine sets that cap on every step.
- **Fix:**
  - Make `result` optional in `StepResult` and add `errors: list[str] = []`.
  - Treat any `subtype != "success"` as a failed round, the same as `is_error`.
  - Add a fake-adapter fixture for `error_max_budget_usd` (AD-2).
  - If the step 0.7 logs still exist, save one success JSON and one error JSON from them as fixtures, rather than writing the shape by hand.

### F2 (high): the issue marker cannot be found reliably with GitHub search

- **Where:** AD-13 (the key `<!-- sdlc:<run-id>:<scope> -->`) and AD-14.
- **Evidence:**
  - `gh issue list --search` uses GitHub search syntax, where `word:value` is parsed as a qualifier. `sdlc:20261116-…:story:1-2-x` will be misread unless it is quoted.
  - Search also tokenizes on punctuation, so an exact match on the marker is not guaranteed.
  - The search index lags behind writes by seconds or more, so a quick resume after a crash can miss an issue that was just created and open a duplicate. That is the case AD-13 exists to prevent.
  - Unverified: whether GitHub search indexes text inside HTML comments.
- **Fix:** Do not use search for the idempotency lookup. Run `gh issue list --label escalation --state all --json number,body --limit 500` and match the marker exactly on the client side. Also record the issue number in `state.json` once it is created. Keep `--search` for humans only.

### F3 (medium): auto-merge has repository prerequisites the spine assumes but does not state

- **Where:** AD-12 ("The only route to integration is a PR with auto-merge") and the plan §4.2 "CI green" gate.
- **Evidence:**
  - `gh pr merge --auto` only works when the repository has "Allow auto-merge" enabled and `main` has branch protection or a ruleset with required checks.
  - The gh man page says auto-merge is enabled only "if required checks have not yet passed". When the PR is already mergeable, gh merges it at once. That would skip the "CI green" gate if no checks are required.
  - In a non-interactive run, gh needs a merge strategy (`--squash`, `--merge` or `--rebase`), and the spine names none.
  - The guard's G2 rule blocks the text `gh pr merge`. That is fine for the orchestrator's own subprocesses, which hooks do not cover. But Marghioala (Developer) cannot run that command in a Bash tool while building `adapters/gh.py`, so the adapter has to be tested only through the fake.
- **Fix:**
  - Add the auto-merge setting and the required-check ruleset to the plan §6 human-input list, as a step 0.9 prerequisite.
  - Pin the command: `gh pr merge <n> --auto --squash --match-head-commit <sha>`.
  - Before enabling auto-merge, the adapter checks `gh pr checks --required` and refuses if no required checks exist. The repository rules are owner-only (G2), so the orchestrator should refuse rather than try to change them.

### F4 (medium): "frozen, the core returns copies" is only shallowly true in pydantic 2.14

- **Where:** Consistency Conventions, "Models" row; AD-1 and AD-4.
- **Evidence:** On 2.14.3, with `ConfigDict(frozen=True)`:
  - `s.rounds["g"] = 99` succeeds on a `dict` field.
  - `model_copy(update={"status": 123})` returns an instance holding an `int` without raising, so it bypasses both validation and the closed status set (AD-5).
  - A frozen model with a `dict` field is not hashable.
- **Fix:**
  - Use immutable field types in core models: `tuple[...]`, `frozenset`, and `Mapping` built from `MappingProxyType`, or nested frozen models.
  - Model status as a `Literal[...]` or `StrEnum`.
  - In `core.apply`, build new states with `Model.model_validate({...})` or a `replace()` helper that revalidates. Never use `model_copy(update=…)`.
  - Add a core unit test that changing a returned state does not change the input state.

### F5 (low): pytest-asyncio 1.x needs explicit configuration

- **Where:** Python style row; Stack.
- **Evidence:** With pytest 9.2.0 and pytest-asyncio 1.5.0, an unset `asyncio_default_fixture_loop_scope` emits a `PytestDeprecationWarning`. That warning becomes an INTERNALERROR under `-W error`. Setting `asyncio_default_fixture_loop_scope = "function"` gave a clean pass on Python 3.12 and 3.14, including an `asyncio.create_subprocess_exec(..., start_new_session=True)` call, which is the AD-3 process-group pattern. pytest-asyncio 1.x also removed the `event_loop` fixture.
- **Fix:** In `pyproject.toml` under `[tool.pytest.ini_options]`, set `asyncio_mode = "strict"` (or `"auto"`) and `asyncio_default_fixture_loop_scope = "function"`. Do not use `event_loop`.

### F6 (low): Stack table drift, and pins on very new releases

- **Where:** Stack.
- **Evidence:**
  - The `uv` row records the local Homebrew 0.11.32, not the current release, 0.13.8 on PyPI. Every other row matches PyPI or local `--version`.
  - ruff 0.17.5, uv 0.13.8 and pytest-asyncio 1.5.0 came out 4 days before this review.
  - ruff is not installed on the runner.
- **Fix:**
  - Either upgrade uv or label the row "uv ≥ 0.11 (runner: 0.11.32)", in the same style as the Python row.
  - Commit `uv.lock` and give the dev dependencies (ruff, pytest, pytest-asyncio) lower bounds, not floating versions.
  - Add `uv run ruff` to the "tests pass" commands, so the missing global ruff does not matter.

### F7 (medium): the judge's "Read, Grep, Glob only" depends on `--allowedTools`, which adds permissions and does not remove them

- **Where:** AD-10 (a judge is read-only) and AD-8.
- **Evidence:**
  - `claude --help` describes `--allowedTools` as tools "to allow". It adds to whatever the user, project and local settings already allow. It does not take tools away.
  - The project `settings.json` has no `allow` rules today, but a runner's user settings could.
  - The CLI also has `--tools <tools...>` ("Specify the list of available tools from the built-in set") and `--disallowedTools`.
- **Fix:**
  - For judges and read-only reviewers, add `--tools Read,Grep,Glob` so that nothing else is available at all.
  - Optionally add `--setting-sources project`, so the runner's user settings cannot widen permissions. Check first that the guard hook still loads with it, since the hook lives in project settings.
  - Never pass `--bare`: per `--help` it skips hooks, which would disable G1–G5.
  - Pass `--allowedTools` as one comma-joined argument. The flag is variadic (`<tools...>`) and will swallow any later positional arguments.

## Not verified

- The real JSON from `--output-format json` was not captured in this review, because that needs a paid call. The fields come from the CLI's embedded schema and from the step 0.7 smoke results. A single real call costs about $0.05 and would settle F1 for good. The owner or Zamolxe (Orchestrator) should save that output as a fixture.
- No real repository was used to check whether the auto-merge and branch-protection settings are enabled (F3).
- Whether GitHub search indexes HTML comments (F2). The fix in F2 makes this question irrelevant.
- `--agent <name>` was not tested against `.claude/agents/*.md` (`code-reviewer`, `devops-release`, `knowledge-keeper`, `security-compliance-reviewer`) in this review. Step 0.7 already ran these as `--agent` steps successfully, according to bmad-reference §4.
