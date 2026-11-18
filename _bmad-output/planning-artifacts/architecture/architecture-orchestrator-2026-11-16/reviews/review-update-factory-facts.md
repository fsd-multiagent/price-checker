# Review: AD-26 and AD-27 technology facts (spine update 2026-11-18)

- **Target:** `ARCHITECTURE-SPINE.md`, AD-26 (protected-path proposals) and AD-27 (factory stories from issues, owner-review integration)
- **Lens:** reality check of every `gh`, GitHub and `git` claim against the docs, the installed tools and the live repo (read-only)
- **Date:** 2026-11-18
- **Verdict:** **fail — one critical trust gap.** The mechanics (`gh pr create --label`, `Closes #n`, `state`/`mergeCommit`, `git apply --3way`) exist and behave roughly as the spine assumes, but trusting the `factory` label on its own lets anyone who can edit the issue body choose what a factory story changes. Fix F1 before code; F2–F4 are needed for the feature to work as written.

## Sources checked

| Source | What it settled |
| --- | --- |
| `gh --version` → 2.96.0; `gh pr create --help`, `gh label create --help`, `gh issue view --json`, `gh pr view --json`, `gh pr list --help` | Flags and JSON fields that exist |
| `gh api repos/fsd-multiagent/price-checker` and `/rulesets/27479760` | Public, org repo; `allow_auto_merge: true`; ruleset on `~DEFAULT_BRANCH`: PR required, 0 approvals, required check `ci`, no bypass actors |
| `gh label list` | Labels present: GitHub defaults plus `escalation`. **`owner-review` and `factory` do not exist yet** |
| `gh api …/issues/10`, `/issues/10/timeline`, `/issues/10/events`, GraphQL `timelineItems(itemTypes:[LABELED_EVENT])`, `lastEditedAt`, `userContentEdits` | Label actor and edit history are obtainable (tested live on #10) |
| `gh api …/collaborators/<login>/permission` | Returns `permission` / `role_name` for an actor (tested: owner is `admin`) |
| `gh pr view 14 --json state,mergeCommit,mergedAt` | `{"state":"MERGED","mergeCommit":{"oid":"ec00586…"}}` (tested) |
| `git --version` → 2.54.0; scratch repo experiments (scratchpad) | `git diff` pathspec behaviour, untracked files, `git apply --3way` exit code and leftovers |
| https://docs.github.com/en/issues/using-labels-and-milestones-to-track-work/managing-labels | "Anyone with write access … can create a label." "Anyone with triage access … can apply and dismiss labels." |
| https://docs.github.com/en/issues/tracking-your-work-with-issues/using-issues/linking-a-pull-request-to-an-issue | Closing keywords work only when the PR targets the default branch; issue closes on merge |
| https://docs.github.com/en/communities/using-templates-to-encourage-useful-issues-and-pull-requests/syntax-for-issue-forms | Template `labels` "will automatically be added to issues created with this template"; no permission caveat stated |
| https://cli.github.com/manual/gh_pr_create, https://cli.github.com/manual/gh_label_create, https://cli.github.com/manual/gh_issue_view | CLI references |
| https://docs.github.com/en/rest/issues/timeline , https://docs.github.com/en/rest/collaborators/collaborators#get-repository-permissions-for-a-user | Timeline `labeled` events carry `actor`; permission endpoint |
| https://git-scm.com/docs/git-apply , https://git-scm.com/docs/gitglossary#Documentation/gitglossary.txt-aiddefpathspecapathspec | `--3way` semantics; pathspec magic |

## Fact table

| Claim / assumption | Status | Evidence |
| --- | --- | --- |
| `gh pr create --label owner-review`, without auto-merge | Correct. `-l/--label name` exists; auto-merge is only ever a separate `gh pr merge --auto`, so "no auto-merge" means simply not calling it. `allow_auto_merge` is on for the repo, so this is a code convention, not something GitHub enforces | `gh pr create --help` |
| Labels exist | **Not yet.** `owner-review` and `factory` are missing. `gh pr create --label X` with an unknown label fails (gh resolves label names before creating; error `could not add label: 'X' not found`). Not tested live, to avoid creating a PR; `gh pr create --dry-run` exists for a safe check | `gh label list`; `gh pr create --help` line "--dry-run … May still push git changes" |
| `gh label create --force` to ensure labels | Correct: `--force` updates colour/description if the label exists, so it is idempotent. Needs **write** access (triage can apply labels but not create them) | managing-labels doc |
| Read issue body and labels | Correct with `gh issue view <n> --json body,labels,author,title,state`. **`authorAssociation` is not a `gh issue view --json` field** — the command in the brief fails with `Unknown JSON field: "authorAssociation"`. Use `gh api repos/{o}/{r}/issues/<n> --jq .author_association` or GraphQL `issue{authorAssociation}` | tested |
| Who can apply the `factory` label | Triage, write, maintain, admin. Non-collaborators cannot label on a public repo through the UI/API — **except** via an issue template/form with `labels:` (applied automatically to whoever opens it; the docs give no permission caveat) and via any workflow/App holding `issues: write`. The repo has no issue templates today | managing-labels; issue-forms syntax; `ls .github` |
| Label actor obtainable | Yes. REST `GET /repos/{o}/{r}/issues/{n}/timeline` (or `/events`) → `event: "labeled"`, `actor.login`, `label.name`, `created_at`; GraphQL `timelineItems(itemTypes:[LABELED_EVENT,UNLABELED_EVENT])`. Actor's role: `GET /repos/{o}/{r}/collaborators/{login}/permission` | tested on #10 |
| Body edits after labelling are detectable | Yes. GraphQL `issue{lastEditedAt editor{login} userContentEdits(first:n){nodes{editedAt editor{login}}}}` | tested on #10 (no edits) |
| `Closes #<issue>` closes the issue on merge | Correct, **only when the PR's base is the default branch** (story PRs target `main`, so fine). Squash merge does not matter. A `CLOSED` (unmerged) PR leaves the issue open, which matches "a CLOSED PR escalates". Linking is visible as `closingIssuesReferences` on `gh pr view --json` | linking doc; `gh pr view --json` field list |
| `MERGED`/`CLOSED` from `gh pr view --json state,mergeCommit` | Correct. `state` ∈ `OPEN`, `CLOSED`, `MERGED`; `mergeCommit.oid` set on merge; also `mergedAt`, `mergedBy` | tested on PR #14 |
| Patch applied with `git apply --3way` on a branch from `origin/main` | Works because worktrees share the object store, so the preimage blobs named in the patch's `index` lines exist. On conflict: **exit 1**, conflict markers in the file, unmerged entries in the index, and other files of the patch already staged (`--3way` implies `--index`). The orchestrator must reset the branch before turning the patch into an issue | scratch repo: `exit=1`, `UU sdlc/prompts/p.md`, `A sdlc/prompts/new.md` |
| `git diff -- 'sdlc/**'` | Works, but not for the reason implied: without `:(glob)`, pathspec wildcards are fnmatch with `*` crossing `/`, so `sdlc/**` behaves like `sdlc/*`. `:(glob)sdlc/**` gives the same result. Plain `sdlc/` is clearer. `CLAUDE.md` matches the root file only; `:(glob)**/CLAUDE.md` matches nested ones | scratch repo |
| `git diff -- <pathspec>` captures the agent's change | **Incomplete:** `git diff` (worktree vs index) omits **untracked** files, so a new prompt or new test under `orchestrator/` is lost. Capture after `git add -A` with `git diff --cached --binary HEAD -- <pathspecs>` | scratch repo: `new.md` absent without staging, present with it |

## Findings

### F1 — critical: the `factory` label alone is not a trust boundary
AD-27 makes a factory story — which may rewrite `orchestrator/**`, `.claude/**`, `.github/**` and the gate commands — from any issue carrying `factory`. Three holes:
1. **TOCTOU on the body.** The issue author (anyone, on a public repo) can edit the body after the owner applies the label; `sdlc story` reads the current body.
2. **Who labelled it.** A future issue form with `labels: [factory]`, or any workflow/App with `issues: write`, applies the label without the owner.
3. The spine never says the label's actor or the author is checked.

The owner-review merge is a backstop, but by then a paid run has executed agent work driven by attacker-chosen text (prompt injection into agents with Bash, AD-8/AD-9).
**Fix:** in AD-27, before parsing: (a) find the latest `labeled` event for `factory` (timeline API) and require its actor to have `admin` or `maintain`/`write` via the collaborator-permission endpoint, with no later `unlabeled`; (b) require `lastEditedAt` (GraphQL) to be null or earlier than that label event, else escalate; (c) record the body's SHA-256 and the label event id in `stories.yaml`/state so a resume re-checks them. Consider applying (a)–(b) to every `sdlc story`/`fix`, not only factory ones, since non-factory stories also feed issue text to agents. Add: no issue template may carry `factory`.

### F2 — high: proposals and owner-review PRs produce no notification for the owner
AD-26 step 5 promises "one notification per proposal". The orchestrator's `gh` is authenticated as the owner (`aserban-dev`, `admin`; issues #6 and #10 are authored and labelled by that login). GitHub does not notify a user about their own PRs, issues or labels, and a user cannot request their own review. Likewise `mergedBy` cannot distinguish "the owner merged" from "the orchestrator merged".
**Fix:** either run the orchestrator's GitHub calls as a separate identity (a GitHub App or bot account with write access; it also makes `mergedBy` meaningful and allows requesting the owner's review), or drop the notification claim and name the channel (saved search `is:open label:owner-review`, run summary). State which in AD-12/AD-26.

### F3 — high: the capture command as implied loses new files and grabs non-protected `AGENTS.md` text
- `git diff -- <pathspecs>` omits untracked files (tested). Capture with `git add -A -- <pathspecs>` then `git diff --cached --binary --full-index HEAD -- <pathspecs>` (`--binary` for binary files, `--full-index` makes the patch self-contained for `--3way`).
- "The `sdlc-commands` block of `AGENTS.md`" is part of a file; no pathspec selects it. A whole-file diff of `AGENTS.md` carries every other `AGENTS.md` edit, which AD-21 says is bookkeeping for Aglaia (Knowledge Keeper). Specify: build the proposed `AGENTS.md` as `origin/main`'s file with only the block replaced, then diff that. The same splice is needed for factory stories (keep the block, revert the rest), which inverts the ADR 0021 restore.
- Replace `sdlc/**` style patterns with directory prefixes or explicit `:(glob)`; say whether nested `CLAUDE.md` files are protected (`CLAUDE.md` matches only the root one).

### F4 — medium: "applies cleanly" and conflict cleanup are undefined
`git apply --3way` can succeed through a real 3-way merge (not a clean context apply) and, on failure, exits 1 leaving conflict markers and a partly staged index (tested). Define success as exit 0 with no unmerged entries (`git diff --name-only --diff-filter=U` empty); on failure `git reset --hard` / remove the proposal worktree before opening the issue, and never push a partially applied branch. Also note 3-way needs the preimage blobs: keep the patch's `index` lines (`--full-index`) and apply in a worktree of the same repo.

### F5 — medium: "no auto-merge" and "owner only merges" are conventions, not enforcement
The ruleset requires a PR with **0 approvals** and the `ci` check; `allow_auto_merge` is on; the orchestrator holds an admin token. Nothing in GitHub stops a bug (or an agent that reaches `gh`) from merging an `owner-review` PR. **Fix:** in `adapters/gh.py`, refuse `pr merge` (with or without `--auto`) on any PR labelled `owner-review`, with a unit test; make the integrate step re-check the label before every auto-merge; and, if a bot identity is adopted (F2), add a ruleset rule requiring 1 approval for paths in `protected_paths` or for PRs from that identity (rulesets cannot filter by label, so state the limit). Also: the guard hook should deny `gh pr merge` and `gh label` for agents (check G-rules coverage).

### F6 — low: setup and smaller gaps
- `owner-review` and `factory` labels do not exist: add an idempotent `gh label create <name> --force --color … --description …` to the orchestrator's preflight (needs write; the token has admin).
- Use `gh pr list --head proposal/<run-id>/<seq> --state all --json number,state` for the AD-13 key; `--head` does not accept `owner:branch`, fine for same-repo branches.
- Polling "every tick through `gh`" for a PR that may wait days: one GraphQL call per tick is cheap (5,000 points/hour), but the run lock (AD-27 "one run at a time") is held while waiting on the owner, so every queued command waits too. Say whether a run blocked only on owner review releases the lock or keeps it.
- `CLOSED` then reopened PRs: decide whether a reopen after escalation is ignored.
- Epic 0 keys (`0-<issue>-<slug>`): confirm `core/epics_parser.py` and `sprint-status.yaml` rendering accept epic 0 (unverified here).
