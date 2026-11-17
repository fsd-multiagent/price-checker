"""Derive a step's outcome from its result (AD-18)."""

from __future__ import annotations

from typing import Any, NamedTuple

from sdlc.core.models import Outcome, OutcomeSource, Step, StepResult, Verdict
from sdlc.core.text import result_json

_DONE = {"done", "complete", "completed", "success", "ok", "pass", "passed"}
_READINESS = {"PASS", "CONCERNS", "FAIL"}


class Derived(NamedTuple):
    outcome: Outcome
    reason: str | None
    status_value: str | None


def derive_outcome(
    step: Step, result: StepResult, verdict: Verdict | None, *, readiness: bool = False
) -> Derived:
    """`readiness` is True for a step that feeds a `readiness` gate: PASS, CONCERNS and FAIL
    are then reported values for the gate to judge. Elsewhere FAIL is an error."""
    failed = result.subtype != "success" or result.is_error
    if failed and result.usage_limited:  # AD-18: checked first, only on an error result
        return Derived(Outcome.LIMITED, f"plan usage limit: {_error_text(result)}", None)
    if failed:
        detail = "; ".join(result.errors) if result.errors else ""
        return Derived(Outcome.ERROR, f"{result.subtype}{': ' + detail if detail else ''}", None)

    source = step.outcome_source or OutcomeSource.SUBTYPE
    if source is OutcomeSource.SUBTYPE:
        return Derived(Outcome.DONE, None, None)
    if source is OutcomeSource.EXIT_CODE:
        if result.exit_code == 0:
            return Derived(Outcome.DONE, None, None)
        return Derived(Outcome.ERROR, f"exit code {result.exit_code}", None)
    if source is OutcomeSource.VERDICT:
        if verdict is None:
            return Derived(Outcome.ERROR, "no valid verdict", None)
        return Derived(Outcome.DONE, None, None)

    data: Any = result_json(result.result)
    if not isinstance(data, dict) or "status" not in data:
        return Derived(Outcome.ERROR, "result reports no status", None)
    status = str(data["status"]).strip()
    value = str(data.get("readiness", status)).strip()
    if (readiness and status.upper() in _READINESS) or status.lower() in _DONE:
        outcome = Outcome.DONE
    elif status.lower() == "blocked":
        return Derived(Outcome.BLOCKED, str(data.get("reason") or "agent reported blocked"), value)
    elif status.lower() == "ambiguous":
        return Derived(Outcome.AMBIGUOUS, _question(data) or "agent reported ambiguous", value)
    else:
        return Derived(Outcome.ERROR, f"unknown status '{status}'", value)

    blocking = _question(data)
    if blocking:
        return Derived(Outcome.AMBIGUOUS, blocking, value)
    return Derived(outcome, None, value)


def _error_text(result: StepResult) -> str:
    return "; ".join((*result.errors, result.result or "")).strip("; ")[:300]


def _question(data: dict[str, Any]) -> str | None:
    """The first blocking open question, if any."""
    for q in data.get("open_questions") or ():
        if isinstance(q, dict) and q.get("blocking"):
            return f"blocking open question: {q.get('question') or q.get('summary') or q}"
    return None
