"""The real ports for a non-dry run, and the preflight that runs before its run dir exists."""

from __future__ import annotations

import json
import os
import re
import shutil
import sys
from collections.abc import Mapping
from pathlib import Path

from sdlc.adapters.claude import (
    CONTENT_LOG_VARS,
    CREDENTIAL_VARS,
    TELEMETRY_SWITCHES,
    ClaudeCli,
    credential_set,
)
from sdlc.adapters.clock import SystemClock
from sdlc.adapters.commands import ProjectCommands
from sdlc.adapters.fs import LocalFs
from sdlc.adapters.gh import GhCli
from sdlc.adapters.git import GitCli
from sdlc.adapters.notifier import DesktopNotifier
from sdlc.adapters.proc import ProcResult, run_proc
from sdlc.core.models import Settings, Telemetry
from sdlc.ports import AdapterError, Ports

TOOLS = ("claude", "git", "gh")
GH_MIN = (2, 40)

_BILLING = (
    "claude -p would bill per token or go through another provider instead of the "
    "subscription; remove it or set allow_api_key: true in sdlc/pipeline.yaml (AD-23)"
)


def real_ports(
    repo: str | Path,
    allow_api_key: bool = False,
    telemetry: Telemetry | None = None,
    settings: Settings | None = None,
) -> Ports:
    """`settings` gives the gh adapter the path tiers its merge refusal reads (AD-26)."""
    tiers = {}
    if settings is not None:
        tiers = {"protected_paths": settings.protected_paths, "review_paths": settings.review_paths}
    return Ports(
        claude=ClaudeCli(allow_api_key=allow_api_key, telemetry=telemetry),
        git=GitCli(repo),
        gh=GhCli(repo, **tiers),
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


EXPORTER_VARS = ("OTEL_METRICS_EXPORTER", "OTEL_LOGS_EXPORTER", "OTEL_TRACES_EXPORTER")


def _switched_on(value: object) -> bool:
    """`0`, `false`, `none` and empty count as off."""
    return str(value or "").strip().lower() not in ("", "0", "false", "none")


def telemetry_warnings(
    repo: str | Path,
    *,
    telemetry_on: bool = False,
    environ: Mapping[str, str] | None = None,
    home: str | Path | None = None,
    platform: str | None = None,
) -> list[str]:
    """AD-25: a settings file's `env` block reaches every agent and cannot be stripped. Warn
    when it turns on a content-logging variable; while telemetry is off, when it turns on a
    telemetry switch; while it is on, when it sets an exporter other than `otlp` or `none`
    (`console` would write into the JSON result)."""
    env = os.environ if environ is None else environ
    warnings: list[str] = []
    for path in settings_files(repo, home, env, platform):
        try:
            data = json.loads(path.read_text(encoding="utf-8"))
        except (OSError, ValueError):
            continue  # credential_problems reports an unreadable file
        block = data.get("env") if isinstance(data, dict) else None
        if not isinstance(block, dict):
            continue
        for name in CONTENT_LOG_VARS:
            if _switched_on(block.get(name)):
                warnings.append(_warning(name, path, "would export prompt, tool or response text"))
        if not telemetry_on:
            for name in TELEMETRY_SWITCHES:
                if _switched_on(block.get(name)):
                    why = "turns telemetry on for every agent although settings.telemetry is off"
                    warnings.append(_warning(name, path, why))
            continue
        for name in EXPORTER_VARS:
            value = str(block.get(name) or "").strip()
            if value and value.lower() not in ("otlp", "none"):
                why = f"sets exporter '{value}'; only otlp is safe (console corrupts the result)"
                warnings.append(_warning(name, path, why))
    return warnings


def _warning(name: str, path: Path, why: str) -> str:
    return (
        f"{name} is set in the env block of {path}: it {why}; the orchestrator cannot remove "
        "it from there (AD-25)"
    )


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
