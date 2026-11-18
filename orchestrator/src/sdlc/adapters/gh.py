"""The real `gh` adapter: PRs, required checks, auto-merge, escalation issues (AD-13, AD-20)."""

from __future__ import annotations

import asyncio
import json
import time
from pathlib import Path
from typing import Any

from sdlc.adapters.proc import ProcResult, run_proc
from sdlc.core.issues import IssueInfo, LabelEvent
from sdlc.core.models import Settings
from sdlc.core.proposals import OWNER_REVIEW_LABEL, in_tier, proposal_marker
from sdlc.ports import AdapterError, ProposalRef, StepKey

ESCALATION_LABEL = "escalation"
OWNER_REVIEW_COLOR = "D93F0B"
OWNER_REVIEW_DESCRIPTION = "Factory change proposed by an agent; only the owner merges (AD-26)"
_PASS = frozenset({"pass", "skipping"})
_FAIL = frozenset({"fail", "cancel"})
_RUN_FAILED = frozenset({"failure", "timed_out", "startup_failure"})
_NO_SHA_POLLS = 3  # polls to wait for the merge commit SHA after the PR reports MERGED
_DEFAULTS = Settings(run_budget_usd=1)
# AD-27: one query, so the text, the state and its last editor are read together.
_ISSUE_QUERY = (
    "query($owner: String!, $repo: String!, $number: Int!) { repository(owner: $owner, "
    "name: $repo) { issue(number: $number) { number title body state author { login } "
    "lastEditedAt editor { login } labels(first: 100) { nodes { name } } } } }"
)
_LABEL_EVENTS_JQ = (
    '.[] | select(.event == "labeled" or .event == "unlabeled" or .event == "renamed") | '
    '{id: ((.id // .node_id) | tostring), event: .event, label: (.label.name // ""), '
    "actor: .actor.login}"
)


def escalation_marker(run_id: str, scope: str) -> str:
    return f"<!-- sdlc:{run_id}:{scope} -->"


def _number_from_url(out: str) -> int:
    """`gh pr create` and `gh issue create` print the new URL; its last segment is the number."""
    for line in reversed(out.strip().splitlines()):
        tail = line.strip().rstrip("/").rsplit("/", 1)[-1]
        if tail.isdigit():
            return int(tail)
    raise AdapterError(f"no number in gh output: {out.strip()[-300:]!r}")


class GhCli:
    """Every call runs in the main checkout, so `gh` resolves the repo from `origin`.

    `poll_s` is 60 s (AD-19); tests pass a smaller value. `merge_timeout_min` bounds the wait
    for auto-merge after the checks are green.
    """

    def __init__(
        self,
        repo: str | Path,
        poll_s: float = 60.0,
        merge_timeout_min: float = 30.0,
        call_timeout_s: float = 120.0,
        protected_paths: tuple[str, ...] = _DEFAULTS.protected_paths,
        review_paths: tuple[str, ...] = _DEFAULTS.review_paths,
    ) -> None:
        self.repo = Path(repo).resolve()
        self.protected_paths = protected_paths
        self.review_paths = review_paths
        self.poll_s = poll_s
        self.merge_timeout_min = merge_timeout_min
        self.call_timeout_s = call_timeout_s
        self._labels_ready: set[str] = set()

    async def _gh(self, *args: str, check: bool = True) -> ProcResult:
        res = await run_proc(("gh", *args), cwd=self.repo, timeout_s=self.call_timeout_s)
        if check and not res.ok:
            what = "timed out" if res.timed_out else f"exit {res.returncode}"
            raise AdapterError(f"gh {' '.join(args[:2])} {what}: {res.tail(500)}")
        return res

    async def _json(self, *args: str, check: bool = True) -> Any:
        res = await self._gh(*args, check=check)
        text = res.stdout.strip()
        if not text:
            return None
        try:
            return json.loads(text)
        except json.JSONDecodeError as exc:
            if not check:
                return None
            raise AdapterError(f"gh {' '.join(args[:2])}: output is not JSON: {exc}") from exc

    async def ensure_pr(
        self, head: str, base: str, title: str, body: str, labels: tuple[str, ...] = ()
    ) -> int:
        """AD-13: PRs are keyed by head branch; reuse the open one, else create it. `labels`
        are set when the PR is created (AD-27: an owner-review PR is never unlabelled), and
        added to a reused one."""
        for name in labels:
            if name == OWNER_REVIEW_LABEL:
                await self._owner_review_label()
        found = await self._json(
            "pr", "list", "--head", head, "--base", base, "--state", "open", "--json", "number"
        )
        if isinstance(found, list) and found:
            entry = found[0]
            if not isinstance(entry, dict) or "number" not in entry:
                raise AdapterError(f"gh pr list returned an entry without a number: {entry!r}")
            number = int(entry["number"])
            for name in labels:
                await self._gh("pr", "edit", str(number), "--add-label", name)
            return number
        flags = [x for name in labels for x in ("--label", name)]
        res = await self._gh(
            "pr", "create", "--head", head, "--base", base, "--title", title, "--body", body,
            *flags,
        )  # fmt: skip
        return _number_from_url(res.stdout)

    async def checks_green(self, pr: int, timeout_min: int, key: StepKey | None = None) -> bool:
        """AD-20: every required check passes, at least one of them `pass`.

        No checks yet, or a failed `gh` call, keeps polling until the deadline: zero required
        checks is never green. A failure or cancellation, or only skipped checks, is final.
        """
        deadline = time.monotonic() + timeout_min * 60
        while True:
            # `gh pr checks` exits non-zero while checks are pending or failing; read the JSON.
            checks = await self._json(
                "pr", "checks", str(pr), "--required", "--json", "name,state,bucket", check=False
            )
            entries = [c for c in checks if isinstance(c, dict)] if isinstance(checks, list) else []
            buckets = {str(c.get("bucket", "")).lower() for c in entries}
            if buckets & _FAIL:
                return False
            if buckets and buckets <= _PASS:
                return "pass" in buckets  # only skipped checks is not green
            if time.monotonic() + self.poll_s > deadline:
                return False
            await asyncio.sleep(self.poll_s)

    async def failed_ci_log(self, pr: int, key: StepKey | None = None) -> str:
        """Issue #87: `gh run view --log-failed` of every failed run on the PR head SHA.

        Only runs whose `headSha` is the PR's head are read; any failing call raises, and the
        caller treats that as "no excerpt"."""
        view = await self._json("pr", "view", str(pr), "--json", "headRefOid")
        sha = str(view.get("headRefOid") or "") if isinstance(view, dict) else ""
        if not sha:
            raise AdapterError(f"no head SHA for PR #{pr}")
        runs = await self._json(
            "run", "list", "--commit", sha, "--limit", "20",
            "--json", "databaseId,headSha,conclusion",
        )  # fmt: skip
        logs: list[str] = []
        for run in runs if isinstance(runs, list) else []:
            if not isinstance(run, dict) or run.get("headSha") != sha:
                continue
            if str(run.get("conclusion", "")).lower() not in _RUN_FAILED:
                continue
            res = await self._gh("run", "view", str(run["databaseId"]), "--log-failed")
            logs.append(res.stdout)
        return "\n".join(logs)

    async def _view(self, pr: int) -> dict[str, Any] | None:
        """`gh pr view`, or None when the call fails (a transient error is polled again)."""
        view = await self._json(
            "pr", "view", str(pr), "--json", "state,mergeCommit,labels,files,changedFiles",
            check=False,
        )  # fmt: skip
        return view if isinstance(view, dict) else None

    async def _first_view(self, pr: int) -> dict[str, Any]:
        """The first view of a merge must succeed: it carries the labels the refusal reads."""
        for attempt in range(_NO_SHA_POLLS + 1):
            view = await self._view(pr)
            if view is not None:
                return view
            if attempt < _NO_SHA_POLLS:
                await asyncio.sleep(self.poll_s)
        raise AdapterError(f"cannot read PR #{pr} before merging it")

    async def merge(
        self,
        pr: int,
        head_sha: str,
        key: StepKey | None = None,
        factory: bool = False,
        paths: list[str] | None = None,
    ) -> str | None:
        """Auto-merge pinned to `head_sha` (never `--admin`); the merge SHA, or None.

        A PR that is already merged (a resume) returns its SHA without a new merge call. On a
        timeout, auto-merge is turned off again. Refused (AD-26, AD-27): a PR labelled
        owner-review, and one touching protected or review paths outside a factory story.
        `paths` are the branch's own changed paths (`git diff`); without them the PR's file
        list is read, and a missing or truncated list refuses (fail closed).
        """
        if not head_sha:
            raise AdapterError("refusing to merge without a head SHA (AD-20)")
        view = await self._first_view(pr)
        refuse_owner_review(pr, view)
        if not factory:
            files = paths if paths is not None else pr_files(pr, view)
            refuse_paths(pr, files, self.protected_paths, self.review_paths)
        state = _state(view)
        if state != "MERGED":
            if state == "CLOSED":
                return None
            await self._gh(
                "pr", "merge", str(pr), "--auto", "--squash", "--match-head-commit", head_sha
            )
        deadline = time.monotonic() + self.merge_timeout_min * 60
        no_sha = 0
        while True:
            if state == "MERGED":
                sha = _merge_sha(view)
                if sha:
                    return sha
                no_sha += 1
                if no_sha > _NO_SHA_POLLS:
                    raise AdapterError(f"PR #{pr} merged without SHA")
            elif state == "CLOSED":
                return None
            if time.monotonic() + self.poll_s > deadline:
                await self._gh("pr", "merge", str(pr), "--disable-auto", check=False)
                return None
            await asyncio.sleep(self.poll_s)
            view = await self._view(pr)
            state = _state(view)
            if state != "MERGED" and _labelled(view):  # labelled while auto-merge waited
                await self._gh("pr", "merge", str(pr), "--disable-auto", check=False)
                refuse_owner_review(pr, view)

    async def ensure_escalation_issue(self, run_id: str, scope: str, title: str, body: str) -> int:
        """AD-13: matched by marker in code, never through search. A reused issue is reopened
        when closed, and gets the new body as a comment."""
        marker = escalation_marker(run_id, scope)
        issues = await self._json(
            "issue", "list", "--label", ESCALATION_LABEL, "--state", "all",
            "--limit", "1000", "--json", "number,body,state",
        )  # fmt: skip
        for issue in issues if isinstance(issues, list) else ():
            if isinstance(issue, dict) and marker in str(issue.get("body") or ""):
                number = int(issue["number"])
                if str(issue.get("state", "")).upper() == "CLOSED":
                    await self._gh("issue", "reopen", str(number))
                await self._gh("issue", "comment", str(number), "--body", body)
                return number
        await self._ensure_label()
        res = await self._gh(
            "issue", "create", "--title", title, "--label", ESCALATION_LABEL,
            "--body", f"{marker}\n{body}",
        )  # fmt: skip
        return _number_from_url(res.stdout)

    async def _ensure_label(self) -> None:
        await self.ensure_label(ESCALATION_LABEL, "B60205", "Zamolxe (Orchestrator) escalation")

    async def ensure_label(self, name: str, color: str, description: str) -> None:
        """Create the label, or update it when it exists (`--force`); once per adapter."""
        if name in self._labels_ready:
            return
        await self._gh(
            "label", "create", name, "--force", "--color", color, "--description", description
        )
        self._labels_ready.add(name)

    async def find_proposal(self, sha256: str) -> ProposalRef | None:
        """AD-26: the PR or issue labelled owner-review whose body carries the marker, in any
        state (a closed or merged one is reused, never reopened). One paginated listing of the
        labelled items; the marker is matched in code, never through search."""
        marker = proposal_marker(sha256)
        res = await self._gh(
            "api", "--paginate",
            f"repos/{{owner}}/{{repo}}/issues?labels={OWNER_REVIEW_LABEL}&state=all&per_page=100",
            "--jq", ".[] | {number: .number, body: .body, pr: (.pull_request != null)}",
        )  # fmt: skip
        for line in res.stdout.splitlines():
            try:
                item = json.loads(line)
            except json.JSONDecodeError as exc:
                raise AdapterError(f"gh api issues: a line is not JSON: {exc}") from exc
            if isinstance(item, dict) and marker in str(item.get("body") or ""):
                return ProposalRef(kind="pr" if item.get("pr") else "issue",
                                   number=int(item["number"]))  # fmt: skip
        return None

    async def ensure_proposal_pr(
        self, sha256: str, head: str, title: str, body: str, looked_up: bool = False
    ) -> ProposalRef:
        """AD-26: a PR from `head` to main, labelled owner-review; auto-merge is never enabled.
        `looked_up`: the caller has just run `find_proposal` and found nothing."""
        found = None if looked_up else await self.find_proposal(sha256)
        if found is not None:
            return found
        await self._owner_review_label()
        res = await self._gh(
            "pr", "create", "--head", head, "--base", "main", "--title", title,
            "--label", OWNER_REVIEW_LABEL, "--body", f"{proposal_marker(sha256)}\n{body}",
        )  # fmt: skip
        return ProposalRef(kind="pr", number=_number_from_url(res.stdout), created=True)

    async def ensure_proposal_issue(
        self, sha256: str, title: str, body: str, looked_up: bool = False
    ) -> ProposalRef:
        found = None if looked_up else await self.find_proposal(sha256)
        if found is not None:
            return found
        await self._owner_review_label()
        res = await self._gh(
            "issue", "create", "--title", title, "--label", OWNER_REVIEW_LABEL,
            "--body", f"{proposal_marker(sha256)}\n{body}",
        )  # fmt: skip
        return ProposalRef(kind="issue", number=_number_from_url(res.stdout), created=True)

    async def _owner_review_label(self) -> None:
        await self.ensure_label(OWNER_REVIEW_LABEL, OWNER_REVIEW_COLOR, OWNER_REVIEW_DESCRIPTION)

    # ------------------------------------------------------------------ AD-27

    async def issue(self, number: int) -> IssueInfo:
        """The issue's title, body, state, author, labels and last editor (GraphQL
        `lastEditedAt` and `editor`), in one query so they describe the same moment."""
        data = await self._json(
            "api", "graphql", "-F", "owner={owner}", "-F", "repo={repo}",
            "-F", f"number={number}", "-f", f"query={_ISSUE_QUERY}",
        )  # fmt: skip
        try:
            node = data["data"]["repository"]["issue"]
            if not isinstance(node, dict):
                raise TypeError("not an object")
        except (KeyError, TypeError) as exc:
            raise AdapterError(f"gh api graphql: no issue #{number} in {data!r}"[:300]) from exc
        editor = node.get("editor")
        nodes = (node.get("labels") or {}).get("nodes") or ()
        return IssueInfo(
            number=int(node.get("number") or number),
            title=str(node.get("title") or ""),
            body=str(node.get("body") or ""),
            author=str((node.get("author") or {}).get("login") or ""),
            state=str(node.get("state") or "").upper(),
            labels=tuple(str(x.get("name", "")) for x in nodes if isinstance(x, dict)),
            last_edited_at=node.get("lastEditedAt"),
            editor=str(editor["login"]) if isinstance(editor, dict) and editor.get("login")
            else None,
        )  # fmt: skip

    async def label_events(self, number: int) -> tuple[LabelEvent, ...]:
        """The `labeled`, `unlabeled` and `renamed` events of the issue's REST timeline."""
        res = await self._gh(
            "api", "--paginate", f"repos/{{owner}}/{{repo}}/issues/{number}/timeline?per_page=100",
            "--jq", _LABEL_EVENTS_JQ,
        )  # fmt: skip
        out: list[LabelEvent] = []
        for line in res.stdout.splitlines():
            if not line.strip():
                continue
            try:
                item = json.loads(line)
            except json.JSONDecodeError as exc:
                raise AdapterError(f"gh api timeline: a line is not JSON: {exc}") from exc
            out.append(
                LabelEvent(
                    id=str(item.get("id")),
                    event=str(item.get("event")),
                    label=str(item.get("label") or ""),
                    actor=item.get("actor"),
                )
            )
        return tuple(out)

    async def comment_issue(self, number: int, body: str) -> None:
        await self._gh("issue", "comment", str(number), "--body", body)

    async def label_pr(self, pr: int, name: str) -> None:
        if name == OWNER_REVIEW_LABEL:
            await self._owner_review_label()
        await self._gh("pr", "edit", str(pr), "--add-label", name)

    async def pr_state(self, pr: int) -> tuple[str, str | None, str | None]:
        """`(state, merge commit SHA, head SHA)`."""
        view = await self._json("pr", "view", str(pr), "--json", "state,mergeCommit,headRefOid")
        if not isinstance(view, dict):
            raise AdapterError(f"gh pr view {pr}: no PR")
        head = view.get("headRefOid")
        return _state(view), _merge_sha(view), str(head) if head else None


def _labelled(view: dict[str, Any] | None) -> bool:
    labels = (view or {}).get("labels") or []
    names = {str(x.get("name", "")) if isinstance(x, dict) else str(x) for x in labels}
    return OWNER_REVIEW_LABEL in names


def refuse_owner_review(pr: int, view: dict[str, Any] | None) -> None:
    """AD-26: the orchestrator never merges or auto-merges a PR labelled owner-review."""
    if _labelled(view):
        raise AdapterError(
            f"refusing to merge PR #{pr}: it is labelled {OWNER_REVIEW_LABEL}; only the owner "
            "merges it (AD-26)"
        )


def pr_files(pr: int, view: dict[str, Any] | None) -> list[str]:
    """The PR's changed paths; refuses (fail closed) when the list is missing or shorter
    than `changedFiles` (gh caps it)."""
    files = (view or {}).get("files")
    count = (view or {}).get("changedFiles")
    if not isinstance(files, list) or not isinstance(count, int) or count != len(files):
        raise AdapterError(
            f"refusing to merge PR #{pr}: its file list is missing or incomplete, so its "
            "paths cannot be checked (AD-26, AD-27)"
        )
    return [str(f.get("path")) for f in files if isinstance(f, dict) and f.get("path")]


def refuse_paths(
    pr: int, files: list[str], protected: tuple[str, ...], review: tuple[str, ...]
) -> None:
    """AD-26, AD-27: outside a factory story, a PR touching protected or review paths is the
    owner's to merge."""
    hit = [f for f in files if in_tier(f, protected) or in_tier(f, review)]
    if hit:
        raise AdapterError(
            f"refusing to merge PR #{pr}: it touches {', '.join(hit[:5])} (protected or review "
            "paths); only the owner merges it (AD-26, AD-27)"
        )


def _state(view: dict[str, Any] | None) -> str:
    return str((view or {}).get("state", "")).upper()


def _merge_sha(view: dict[str, Any] | None) -> str | None:
    commit = (view or {}).get("mergeCommit")
    sha = commit.get("oid") if isinstance(commit, dict) else None
    return str(sha) if sha else None
