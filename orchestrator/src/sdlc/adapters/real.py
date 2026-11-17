"""The real ports for a non-dry run, and the preflight that runs before its run dir exists."""

from __future__ import annotations

import json
import os
import re
import shutil
import sys
from collections.abc import Mapping
from pathlib import Path

from sdlc.adapters.claude import CREDENTIAL_VARS, ClaudeCli, credential_set
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

_BILLING = (
    "claude -p would bill per token or go through another provider instead of the "
    "subscription; remove it or set allow_api_key: true in sdlc/pipeline.yaml (AD-23)"
)


def real_ports(repo: str | Path, allow_api_key: bool = False) -> Ports:
    return Ports(
        claude=ClaudeCli(allow_api_key=allow_api_key),
        git=GitCli(repo),
        gh=GhCli(repo),
        commands=ProjectCommands(),
        fs=LocalFs(),
        clock=SystemClock(),
        notifier=DesktopNotifier(),
    )


MANAGED_SETTINGS = {
    "darwin": Path("/Library/Application Support/ClaudeCode/managed-settings.json"),
    "linux": Path("/etc/claude-code/managed-settings.json"),
}


def settings_files(
    repo: str | Path,
    home: str | Path | None = None,
    environ: Mapping[str, str] | None = None,
    platform: str | None = None,
) -> tuple[Path, ...]:
    """The Claude Code settings files whose `env` and `apiKeyHelper` the preflight checks: the
    user's (under `CLAUDE_CONFIG_DIR` when set), the project's, and the managed one."""
    env = os.environ if environ is None else environ
    home_dir = Path(home) if home is not None else Path.home()
    user_dir = (
        Path(env["CLAUDE_CONFIG_DIR"]) if env.get("CLAUDE_CONFIG_DIR") else home_dir / ".claude"
    )
    files = [
        user_dir / "settings.json",
        Path(repo) / ".claude" / "settings.json",
        Path(repo) / ".claude" / "settings.local.json",
    ]
    managed = MANAGED_SETTINGS.get(platform or sys.platform)
    if managed is not None:
        files.append(managed)
    return tuple(files)


def credential_problems(
    repo: str | Path,
    environ: Mapping[str, str] | None = None,
    home: str | Path | None = None,
    platform: str | None = None,
) -> list[str]:
    """AD-23: every per-token or third-party credential source found, one line each.

    An empty value counts as unset. Checks the process environment, then the `env` block and
    `apiKeyHelper` of each settings file.
    """
    env = os.environ if environ is None else environ
    problems = [
        f"{name} is set in the environment: {_BILLING}"
        for name in CREDENTIAL_VARS
        if credential_set(name, env.get(name))
    ]
    for path in settings_files(repo, home, env, platform):
        try:
            data = json.loads(path.read_text(encoding="utf-8"))
        except FileNotFoundError:
            continue
        except (OSError, ValueError) as exc:
            problems.append(f"cannot read {path} to check for credentials: {exc}")
            continue
        if not isinstance(data, dict):
            continue
        block = data.get("env")
        if isinstance(block, dict):
            problems += [
                f"{name} is set in the env block of {path}: {_BILLING}"
                for name in CREDENTIAL_VARS
                if credential_set(name, str(block.get(name) or ""))
            ]
        if str(data.get("apiKeyHelper") or "").strip():
            problems.append(f"apiKeyHelper is set in {path}: {_BILLING}")
    return problems


async def preflight(
    repo: str | Path,
    *,
    allow_api_key: bool = False,
    environ: Mapping[str, str] | None = None,
    home: str | Path | None = None,
    platform: str | None = None,
) -> list[str]:
    """Every reason a real run cannot start, one line each; empty when it can."""
    problems: list[str] = []
    if not allow_api_key:
        problems += credential_problems(repo, environ, home, platform)
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
