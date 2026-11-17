"""The real `gh` adapter: PRs, required checks, auto-merge, escalation issues (AD-13, AD-20)."""

from __future__ import annotations

import asyncio
import json
import time
from pathlib import Path
from typing import Any

from sdlc.adapters.proc import ProcResult, run_proc
from sdlc.ports import AdapterError, StepKey

ESCALATION_LABEL = "escalation"
_PASS = frozenset({"pass", "skipping"})
_FAIL = frozenset({"fail", "cancel"})
_NO_SHA_POLLS = 3  # polls to wait for the merge commit SHA after the PR reports MERGED


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
    ) -> None:
        self.repo = Path(repo).resolve()
        self.poll_s = poll_s
        self.merge_timeout_min = merge_timeout_min
        self.call_timeout_s = call_timeout_s
        self._label_ready = False

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

    async def ensure_pr(self, head: str, base: str, title: str, body: str) -> int:
        """AD-13: PRs are keyed by head branch; reuse the open one, else create it."""
        found = await self._json(
            "pr", "list", "--head", head, "--base", base, "--state", "open", "--json", "number"
        )
        if isinstance(found, list) and found:
            entry = found[0]
            if not isinstance(entry, dict) or "number" not in entry:
                raise AdapterError(f"gh pr list returned an entry without a number: {entry!r}")
            return int(entry["number"])
        res = await self._gh(
            "pr", "create", "--head", head, "--base", base, "--title", title, "--body", body
        )
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

    async def _view(self, pr: int) -> dict[str, Any] | None:
        """`gh pr view`, or None when the call fails (a transient error is polled again)."""
        view = await self._json("pr", "view", str(pr), "--json", "state,mergeCommit", check=False)
        return view if isinstance(view, dict) else None

    async def merge(self, pr: int, head_sha: str, key: StepKey | None = None) -> str | None:
        """Auto-merge pinned to `head_sha` (never `--admin`); the merge SHA, or None.

        A PR that is already merged (a resume) returns its SHA without a new merge call. On a
        timeout, auto-merge is turned off again.
        """
        if not head_sha:
            raise AdapterError("refusing to merge without a head SHA (AD-20)")
        view = await self._view(pr)
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
        if self._label_ready:
            return
        await self._gh(
            "label", "create", ESCALATION_LABEL, "--force",
            "--color", "B60205", "--description", "Zamolxe (Orchestrator) escalation",
        )  # fmt: skip
        self._label_ready = True


def _state(view: dict[str, Any] | None) -> str:
    return str((view or {}).get("state", "")).upper()


def _merge_sha(view: dict[str, Any] | None) -> str | None:
    commit = (view or {}).get("mergeCommit")
    sha = commit.get("oid") if isinstance(commit, dict) else None
    return str(sha) if sha else None
