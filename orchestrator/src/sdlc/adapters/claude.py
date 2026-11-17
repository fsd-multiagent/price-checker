"""The one way to start an agent (AD-8): build the `claude -p` argument list, run it, parse it.

The fake adapter uses the same builder and parser, so the dry-run prints exactly what a real
run invokes.
"""

from __future__ import annotations

import json
import os
import re
from collections.abc import Mapping
from datetime import UTC, datetime, timedelta
from typing import Any
from zoneinfo import ZoneInfo, ZoneInfoNotFoundError

from sdlc.adapters.proc import run_proc
from sdlc.core.models import Denial, StepResult
from sdlc.ports import AdapterError, Invocation

FORBIDDEN_FLAGS = frozenset(
    {
        "--bare",
        "--dangerously-skip-permissions",
        "--permission-mode",
        "--allow-dangerously-skip-permissions",
    }
)


# AD-18: the one pattern list that recognises a plan usage limit, matched case-insensitively
# against the error text only. Adjust after the first real limit (save it as a fixture).
USAGE_LIMIT_PATTERNS = (
    re.compile(r"hit your [\w -]*limit", re.IGNORECASE),
    re.compile(r"usage limit reached", re.IGNORECASE),
    re.compile(r"5-hour limit reached", re.IGNORECASE),
)
# "usage limit reached|<epoch seconds>": the reset instant as a Unix timestamp.
_EPOCH = re.compile(r"usage limit reached\|(\d{9,12})", re.IGNORECASE)
# "resets [Nov 20, ][at ]3pm (Zone)" or "resets 15:30 (Zone)". The hour needs am/pm or ":mm",
# and the zone is required: without a known zone the reset time is not used.
_RESETS = re.compile(
    r"resets\s+(?:at\s+)?(?:(?P<mon>[a-z]{3,9})\.?\s+(?P<day>\d{1,2}),?\s+(?:at\s+)?)?"
    r"(?P<h>\d{1,2})(?::(?P<m>\d{2}))?\s*(?P<ap>am|pm)?\s*\((?P<tz>[^)]+)\)",
    re.IGNORECASE,
)
_MONTHS = {
    m: i
    for i, m in enumerate(
        ("jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec"),
        start=1,
    )
}
ISO = "%Y-%m-%dT%H:%M:%SZ"

# AD-8: removed from the agent's environment. Without the watchdog `claude -p` fails fast at a
# plan limit, so only the orchestrator waits; without the effort variable `--effort` holds.
STRIPPED_ENV = ("CLAUDE_CODE_EFFORT_LEVEL",)
STRIPPED_ENV_PREFIXES = ("CLAUDE_CODE_RETRY_WATCHDOG",)

# AD-23: with any of these set, `claude -p` bills per token or goes through another provider.
CREDENTIAL_VARS = (
    "ANTHROPIC_API_KEY",
    "ANTHROPIC_AUTH_TOKEN",
    "ANTHROPIC_BASE_URL",
    "ANTHROPIC_PROFILE",
    "CLAUDE_CODE_USE_BEDROCK",
    "CLAUDE_CODE_USE_VERTEX",
    "CLAUDE_CODE_USE_FOUNDRY",
)


def credential_set(name: str, value: str | None) -> bool:
    """Empty counts as unset; `CLAUDE_CODE_USE_*` also treats `0` and `false` as unset."""
    v = (value or "").strip()
    if name.startswith("CLAUDE_CODE_USE_"):
        return v.lower() not in ("", "0", "false")
    return bool(v)


def build_argv(
    *,
    budget_usd: float,
    allowed_tools: tuple[str, ...],
    restrict_tools: bool,
    agent: str | None = None,
    model: str | None = None,
    effort: str | None = None,
    max_turns: int | None = None,
) -> tuple[str, ...]:
    """`claude -p --output-format json --max-budget-usd <cap> --no-session-persistence
    --allowedTools <a,b,c> [--tools <a,b,c>] [--agent <name>] [--model <m>]
    [--effort <level>] [--max-turns <n>]`."""
    argv = [
        "claude",
        "-p",
        "--output-format",
        "json",
        "--max-budget-usd",
        f"{budget_usd:.2f}",
        "--no-session-persistence",
        "--allowedTools",
        ",".join(allowed_tools),
    ]
    if restrict_tools:
        argv += ["--tools", ",".join(allowed_tools)]
    if agent:
        argv += ["--agent", agent]
    if model:
        argv += ["--model", model]
    if effort:
        argv += ["--effort", str(effort)]
    if max_turns:
        argv += ["--max-turns", str(max_turns)]
    bad = FORBIDDEN_FLAGS.intersection(a.split("=", 1)[0] for a in argv)
    if bad:  # unreachable from the builder's inputs; kept as a tripwire
        raise AdapterError(f"refusing forbidden flags {sorted(bad)}")
    return tuple(argv)


def agent_env(
    parent: Mapping[str, str], extra: Mapping[str, str], *, allow_api_key: bool = False
) -> dict[str, str]:
    """The agent's whole environment: the parent's plus `extra`, minus the stripped names (AD-8)
    and, unless `allow_api_key`, minus the credential variables (AD-23)."""
    merged = {**parent, **extra}
    drop = set(STRIPPED_ENV) | (set() if allow_api_key else set(CREDENTIAL_VARS))
    return {
        k: v for k, v in merged.items() if k not in drop and not k.startswith(STRIPPED_ENV_PREFIXES)
    }


def error_text(result: StepResult) -> str:
    return "\n".join((*result.errors, result.result or ""))


def flag_usage_limit(result: StepResult, now: str) -> StepResult:
    """AD-18: mark a result `usage_limited` when it is an error whose text names a plan limit.

    A successful result is never flagged, even if it quotes a limit message. `now` comes from
    the clock port, so the dry-run never reads the wall clock (T-5).
    """
    if not (result.subtype != "success" or result.is_error):
        return result
    text = error_text(result)
    if not any(p.search(text) for p in USAGE_LIMIT_PATTERNS):
        return result
    return StepResult.model_validate(
        {**result.model_dump(), "usage_limited": True, "resets_at": parse_reset(text, now)}
    )


def parse_reset(text: str, now: str) -> str | None:
    """The reset instant a limit message names, as UTC ISO; None when it names none we trust.

    Reads "usage limit reached|<epoch>", or "resets [Nov 20, ]3pm (Europe/Bucharest)": the next
    such instant after `now` in that zone. A missing or unknown zone, or an hour without am/pm
    or minutes, gives None, so the wait falls back to `usage_limit_wait_min`.
    """
    e = _EPOCH.search(text)
    if e is not None:
        return datetime.fromtimestamp(int(e.group(1)), UTC).strftime(ISO)
    m = _RESETS.search(text)
    if m is None or (m.group("m") is None and m.group("ap") is None):
        return None
    try:
        tz = ZoneInfo(m.group("tz").strip())
    except (ZoneInfoNotFoundError, ValueError):
        return None
    hour, minute = int(m.group("h")), int(m.group("m") or 0)
    ampm = (m.group("ap") or "").lower()
    if ampm and not 1 <= hour <= 12:
        return None
    if ampm == "pm" and hour < 12:
        hour += 12
    elif ampm == "am" and hour == 12:
        hour = 0
    if hour > 23 or minute > 59:
        return None
    local = datetime.strptime(now, ISO).replace(tzinfo=UTC).astimezone(tz)
    try:
        if m.group("mon"):
            month = _MONTHS.get(m.group("mon")[:3].lower())
            if month is None:
                return None
            at = datetime(local.year, month, int(m.group("day")), hour, minute, tzinfo=tz)
            if at <= local:
                at = at.replace(year=local.year + 1)
        else:
            at = local.replace(hour=hour, minute=minute, second=0, microsecond=0)
            if at <= local:
                at += timedelta(days=1)
    except ValueError:  # a day that does not exist in that month
        return None
    return at.astimezone(UTC).strftime(ISO)


def parse_result(stdout: str) -> StepResult:
    """Parse `--output-format json` into a StepResult. Raises AdapterError on unreadable output."""
    try:
        data: Any = json.loads(stdout)
    except json.JSONDecodeError as exc:
        raise AdapterError(f"claude output is not JSON: {exc}") from exc
    if not isinstance(data, dict) or "subtype" not in data:
        raise AdapterError("claude output has no 'subtype'")
    denials = tuple(
        Denial(
            tool_name=str(d.get("tool_name", "?")),
            detail=json.dumps(d.get("tool_input", {}), sort_keys=True)[:500],
        )
        for d in data.get("permission_denials") or ()
        if isinstance(d, dict)
    )
    errors = data.get("errors") or ()
    return StepResult(
        subtype=str(data["subtype"]),
        is_error=bool(data.get("is_error", False)),
        total_cost_usd=float(data.get("total_cost_usd") or 0.0),
        num_turns=int(data.get("num_turns") or 0),
        permission_denials=denials,
        result=data.get("result") if isinstance(data.get("result"), str) else None,
        errors=tuple(str(e) for e in errors) if isinstance(errors, list) else (str(errors),),
    )


class ClaudeCli:
    """Runs `Invocation.argv` as a headless `claude -p` process (AD-3, AD-8)."""

    def __init__(self, allow_api_key: bool = False) -> None:
        self.allow_api_key = allow_api_key

    async def run(self, inv: Invocation) -> StepResult:
        proc = await run_proc(
            inv.argv,
            cwd=inv.cwd,
            env=agent_env(os.environ, inv.env, allow_api_key=self.allow_api_key),
            inherit_env=False,
            stdin=inv.stdin,
            timeout_s=inv.timeout_s,
        )
        if proc.timed_out:
            return StepResult(
                subtype="error_timeout",
                is_error=True,
                total_cost_usd=_budget(inv.argv),  # the call may have spent up to its cap
                errors=(f"claude -p timed out after {inv.timeout_s:g}s; process group killed",),
            )
        try:
            return parse_result(proc.stdout)
        except AdapterError as exc:
            raise AdapterError(f"claude exited {proc.returncode}: {exc}: {proc.tail(500)}") from exc


def _budget(argv: tuple[str, ...]) -> float:
    """The `--max-budget-usd` cap of an argument list, or 0 when it has none."""
    for i, arg in enumerate(argv[:-1]):
        if arg == "--max-budget-usd":
            try:
                return float(argv[i + 1])
            except ValueError:
                return 0.0
    return 0.0
