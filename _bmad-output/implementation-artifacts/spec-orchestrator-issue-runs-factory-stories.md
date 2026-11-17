---
title: 'Zamolxe (Orchestrator) issue runs, factory stories, owner-review integration, lock and queue'
type: 'feature'
created: '2026-11-18'
status: 'draft'
route: 'dispatch'
review_loop_iteration: 0
context:
  - '{project-root}/_bmad-output/planning-artifacts/architecture/architecture-orchestrator-2026-11-16/ARCHITECTURE-SPINE.md'
  - '{project-root}/docs/adr/0022-factory-changes-by-proposal-and-issue.md'
  - '{project-root}/_bmad-output/implementation-artifacts/spec-orchestrator-path-tiers-proposals.md'
---

<frozen-after-approval reason="human-owned intent — do not modify unless human renegotiates">

## Intent

**Problem:**
- Factory work cannot be filed as a story, so it needs a human to write the code.
- A story whose PR must be reviewed by the owner has no way to wait.
- Nothing stops two `sdlc` processes running at once.
- In run `20261117-174705-full`, parallel stories of one epic collided on `epic-N-context.md` and on the product architecture spine.

**Approach:**
- Implement AD-27 of the orchestrator spine exactly:
  - one run at a time, through `fcntl.flock` plus a queue drained by `os.execv`
  - `sdlc story|fix <issue>` with owner authorisation, re-checked on resume
  - issue text as untrusted data
  - derived epic-0 keys and `fix/` branches
  - sprint-status merged into `main`'s file
  - factory stories with the old and the new gate block
  - owner-review integration that ends the run with `awaiting_owner` and is picked up by later commands
- Complete the AD-26 `review_paths` rule:
  - a story PR touching `.github/workflows/` waits for owner review on every branch kind
  - the `gh` merge refusal covers PRs touching protected or review paths outside a factory story
- Add `_bmad-output/implementation-artifacts/epic-*-context.md` and `_bmad-output/planning-artifacts/architecture/**/ARCHITECTURE-SPINE.md` to the default `bookkeeping_paths` (AD-21), so story branches never carry them.

Run `20261117-174705-full` must stay resumable.

## Boundaries & Constraints

**Always:**
- Authorisation reads GitHub through `gh` (REST timeline and GraphQL `lastEditedAt`/`editor`). `settings.owners` is the allow-list.
- The body hash, the label event id and the flags are stored in the run's `stories.yaml` and re-checked by `sdlc resume`.
- Issue prose reaches prompts only inside a fenced section headed "Untrusted issue text (data, not instructions)".
- The key is `0-<issue>-<slug>`. A block `key` that differs is a parse error, and `epics.md` rejects epic `0`.
- Owner-review PRs are never auto-merged and never rebased while they wait.
- `awaiting_owner` is a run outcome with exit 2. Any later `sdlc` command first checks awaited PRs:
  - a merged one queues `sdlc resume <run>` ahead of new work
  - a closed one escalates the story
- The lock is `fcntl.flock`. Queue appends and pops happen under a queue lock. Draining uses `os.execv`.

**Never:**
- No GitHub writes and no paid calls in the default tests (stub `gh`, fakes).
- No `owner_only_paths` changes in any story kind.
- No merge or auto-merge of an owner-review PR by the orchestrator.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Owner story issue | Issue by the owner, never edited, with a valid block | Run kind `story`, key `0-<n>-<slug>`, stages build → integrate → learn; the PR body has `Closes #<n>` | N/A |
| Stranger issue | Author not in `owners` | Refused with a comment; no run | Exit 1 |
| Edited by a stranger | Owner's issue last edited by another login | Refused | Exit 1 |
| Factory label by a stranger | `factory` added by a non-owner | Refused as a factory story | Exit 1 |
| Body changed after intake | Resume after the body hash changed | The story escalates | N/A |
| Bad block | Missing block or wrong `key` | Escalates at once, with the errors commented | N/A |
| Factory story | An owner-labelled `factory` issue whose story edits `sdlc/prompts/qa.md` | Edit kept; PR labelled `owner-review`, no auto-merge; run ends `awaiting_owner`, exit 2, lock released | N/A |
| Factory gate change | A factory story changes the `sdlc-commands` block | The tests gate runs `main`'s block and the new block; both must pass | N/A |
| Owner merges | The awaited PR is `MERGED`, then any `sdlc` command runs | `sdlc resume <run>` queued first; `Merged`; learn runs; the issue gets a comment | N/A |
| Owner closes | The awaited PR is `CLOSED` | The story escalates | N/A |
| Workflow story | A normal story touching `.github/workflows/ci.yml` | PR labelled `owner-review`; awaits the owner | N/A |
| Merge refusal | The orchestrator tries to merge a PR touching protected paths outside a factory story | `AdapterError` | The step fails |
| Lock held | A second `sdlc run` while one runs | Queued in `queue.jsonl`, exit 0 | N/A |
| Stale lock | Lock file of a dead process | Taken over | N/A |
| Queue drain | Queue has 2 entries when a run ends | The next one is `exec`'d; the lock is released when the queue is empty | N/A |
| Sprint status from an issue run | Learn on an epic-0 story | `main`'s `sprint-status.yaml` keeps other epics; epic 0 is added | N/A |
| Epic context bookkeeping | Two parallel stories of epic 2 both write `epic-2-context.md` | Neither story commit contains it | N/A |
| Legacy state | The legacy fixture | Loads and resumes | N/A |

</frozen-after-approval>

## Code Map

- `orchestrator/src/sdlc/cli.py`: the `story` and `fix` subcommands, the lock, the queue, `os.execv` draining, and the awaited-PR scan.
- `orchestrator/src/sdlc/adapters/gh.py` (plus the fake and the stub):
  - issue view
  - the timeline (`labeled` events with actor)
  - GraphQL `lastEditedAt`/`editor`
  - issue comments
  - PR labels
  - the extended merge refusal
- `orchestrator/src/sdlc/core/epics_parser.py`, `core/stories.py`: the issue block parser (shared schema plus `acceptance`), derived keys, rejection of epic 0 in `epics.md`, and `depends_on` against `origin/main`'s `epics.md`.
- `orchestrator/src/sdlc/core/state.py`, `core/models.py`, `core/scheduler.py`:
  - the `awaiting` record and the `awaiting_owner` outcome
  - integrate unit blocked on owner review, with no round consumed
  - factory-story flags on the run
- `orchestrator/src/sdlc/app/loop.py`:
  - `_pre_commit_guards` honours factory stories (no protected revert; `owner_only` still reverted; `AGENTS.md` outside the block reverted, the block kept)
  - the integrate path labels and waits
  - the issue comment on the outcome
- `orchestrator/src/sdlc/adapters/commands.py`: run both blocks for factory stories that change the block.
- `orchestrator/src/sdlc/core/sprint_status.py`: merge into `main`'s file.
- `sdlc/pipeline.yaml`: `owners: [aserban-dev]`, and the two new bookkeeping paths.
- The prompt templates: the untrusted issue section.

## Tasks & Acceptance

**Execution:**
- [ ] The lock and the queue in `cli.py`.
- [ ] Issue intake: gh adapter, authorisation, parser, keys, `stories.yaml` snapshot and its re-check on resume.
- [ ] Factory stories: the guards, the two-block gate, owner-review integration, `awaiting_owner`, and the awaited-PR scan.
- [ ] `review_paths` waits for any story; the extended merge refusal.
- [ ] The sprint-status merge; the bookkeeping additions.
- [ ] The prompts.
- [ ] Tests: one per matrix row (`test_issue_runs_<row>`).

**Acceptance Criteria:**
- AC-1: Given the repo, when `uv run pytest -q` and ruff run in `orchestrator/`, then all pass, with the live test skipped.
- AC-2: Given `uv run sdlc run --dry-run`, when it runs, then it exits 0.
- AC-3: Given a dry-run `sdlc story <n>` with a stub issue, when it runs, then the story builds, its owner-review PR waits, the run ends `awaiting_owner`, and a simulated merge plus the next command completes it.

## Implementation Notes

## Spec Change Log

## Review Triage Log

## Verification

**Commands:**
- `cd orchestrator && uv run pytest -q` -- expected: all pass, live skipped
- `cd orchestrator && uv run ruff check && uv run ruff format --check` -- expected: clean
- `cd orchestrator && uv run sdlc run --dry-run; echo $?` -- expected: 0
