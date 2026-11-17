"""The one way to start an agent (AD-8): build the `claude -p` argument list, run it, parse it.

The fake adapter uses the same builder and parser, so the dry-run prints exactly what a real
run invokes.
"""

from __future__ import annotations

import json
from typing import Any

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


def build_argv(
    *,
    budget_usd: float,
    allowed_tools: tuple[str, ...],
    restrict_tools: bool,
    agent: str | None = None,
    model: str | None = None,
) -> tuple[str, ...]:
    """`claude -p --output-format json --max-budget-usd <cap> --no-session-persistence
    --allowedTools <a,b,c> [--tools <a,b,c>] [--agent <name>] [--model <m>]`."""
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
    bad = FORBIDDEN_FLAGS.intersection(a.split("=", 1)[0] for a in argv)
    if bad:  # unreachable from the builder's inputs; kept as a tripwire
        raise AdapterError(f"refusing forbidden flags {sorted(bad)}")
    return tuple(argv)


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

    async def run(self, inv: Invocation) -> StepResult:
        proc = await run_proc(
            inv.argv, cwd=inv.cwd, env=inv.env, stdin=inv.stdin, timeout_s=inv.timeout_s
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
