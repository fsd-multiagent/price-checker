"""The real ports for a non-dry run, and the preflight that runs before its run dir exists."""

from __future__ import annotations

import re
import shutil
from pathlib import Path

from sdlc.adapters.claude import ClaudeCli
from sdlc.adapters.clock import SystemClock
from sdlc.adapters.commands import ProjectCommands
from sdlc.adapters.fs import LocalFs
from sdlc.adapters.gh import GhCli
from sdlc.adapters.git import GitCli
from sdlc.adapters.notifier import DesktopNotifier
from sdlc.adapters.proc import ProcResult, run_proc
from sdlc.ports import AdapterError, Ports

TOOLS = ("claude", "git", "gh")
GH_MIN = (2, 40)


def real_ports(repo: str | Path) -> Ports:
    return Ports(
        claude=ClaudeCli(),
        git=GitCli(repo),
        gh=GhCli(repo),
        commands=ProjectCommands(),
        fs=LocalFs(),
        clock=SystemClock(),
        notifier=DesktopNotifier(),
    )


async def preflight(repo: str | Path) -> list[str]:
    """Every reason a real run cannot start, one line each; empty when it can."""
    problems: list[str] = []
    missing = {tool for tool in TOOLS if shutil.which(tool) is None}
    problems += [f"{tool} not found on PATH" for tool in TOOLS if tool in missing]

    async def run(*argv: str) -> ProcResult | None:
        try:
            return await run_proc(argv, cwd=repo, timeout_s=60)
        except AdapterError as exc:
            problems.append(str(exc))
            return None

    if "gh" not in missing:
        version = await run("gh", "--version")
        found = _version(version.stdout) if version is not None and version.ok else None
        if found is None or found < GH_MIN:
            have = ".".join(map(str, found)) if found else "unknown"
            problems.append(f"gh {'.'.join(map(str, GH_MIN))} or newer required (found {have})")
        auth = await run("gh", "auth", "status")
        if auth is not None and not auth.ok:
            problems.append("gh auth status failed: run `gh auth login`")
    if "git" not in missing:
        origin = await run("git", "remote", "get-url", "origin")
        if origin is not None and not origin.ok:
            problems.append("git remote origin not found")
        email = await run("git", "config", "user.email")
        if email is not None and not (email.ok and email.stdout.strip()):
            problems.append("git user.email not set: run `git config user.email <address>`")
        status = await run("git", "status", "--porcelain")
        if status is not None and not status.ok:
            problems.append(f"git status failed in {repo}: {status.tail(200)}")
        elif status is not None and status.stdout.strip():
            problems.append(f"main checkout {repo} is not clean: commit or stash its changes")
    return problems


def _version(text: str) -> tuple[int, ...] | None:
    m = re.search(r"gh version (\d+)\.(\d+)", text)
    return (int(m.group(1)), int(m.group(2))) if m else None
