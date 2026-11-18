"""The gate registry (AD-19) and verdict normalisation (AD-10).

Every gate returns a `Verdict`. A missing or invalid verdict, a step that did not end `done`,
or an exception inside a gate fails the gate; nothing passes by default.
"""

from __future__ import annotations

import json
import re
from collections.abc import Callable, Mapping
from typing import Any

from pydantic import ValidationError

from sdlc.core.epics_parser import parse_epics
from sdlc.core.models import (
    Finding,
    Gate,
    Outcome,
    Severity,
    Stage,
    StepRecord,
    Story,
    Verdict,
)
from sdlc.core.text import last_json_block

_STANDARD_RULE = re.compile(r"[CTSID]-[A-Z]?\d+[a-z]?")
_AC_ID = re.compile(r"(?:^|[-_.\s])AC-?\d+")
_AD_ID = re.compile(r"AD-\d+")
OUTPUT_TAIL = 4096  # chars of a failed script step's output kept in its finding


def cites_rule(rule: str | None) -> bool:
    """A rule ID from docs/standards/ (C-, T-, S-, I-, D-), a spine ID (AD-n), or an AC ID."""
    if not rule:
        return False
    rule = rule.strip()
    return bool(_STANDARD_RULE.fullmatch(rule) or _AD_ID.fullmatch(rule) or _AC_ID.search(rule))


def normalise(raw: Any) -> Verdict | None:
    """Validate a verdict and apply the review-findings.md rules; None when it is invalid."""
    if isinstance(raw, Verdict):
        raw = raw.model_dump(mode="json")
    if not isinstance(raw, dict):
        return None
    try:
        verdict = Verdict.model_validate(raw)
    except ValidationError:
        return None
    findings = []
    for f in verdict.findings:
        if f.severity is Severity.BLOCKER and not cites_rule(f.rule):
            f = Finding.model_validate({**f.model_dump(), "severity": Severity.MAJOR})
        findings.append(f)
    has_blocker = any(f.severity is Severity.BLOCKER for f in findings)
    return Verdict.model_validate(
        {
            **verdict.model_dump(),
            "verdict": "fail" if has_blocker else "pass",
            "findings": [f.model_dump() for f in findings],
        }
    )


def parse_verdict_text(text: str | None) -> Verdict | None:
    if not text:
        return None
    try:
        return normalise(json.loads(text))
    except json.JSONDecodeError:
        return None


def last_verdict_block(result: str | None) -> Verdict | None:
    """AD-17 `result_block`: the verdict is the last fenced `json` block of the result."""
    return normalise(last_json_block(result))


# --------------------------------------------------------------------------- gate kinds


def _blocker(fid: str, summary: str, rule: str) -> Finding:
    return Finding(id=fid, severity=Severity.BLOCKER, rule=rule, summary=summary)


def _verdict_findings(steps: list[StepRecord], verdict_steps: set[str]) -> list[Finding]:
    out: list[Finding] = []
    for r in steps:
        if r.step not in verdict_steps:
            continue
        if r.verdict is None:
            out.append(
                _blocker(f"{r.step}-verdict", f"step '{r.step}' left no valid verdict", "AD-10")
            )
            continue
        for f in r.verdict.findings:
            out.append(Finding.model_validate({**f.model_dump(), "id": f"{r.step}/{f.id}"}))
    return out


def _review_verdicts(ctx: GateContext) -> list[Finding]:
    return _verdict_findings(ctx.records, ctx.verdict_steps)


def _readiness(ctx: GateContext) -> list[Finding]:
    values = [r.status_value for r in ctx.records if r.status_value]
    value = (values[-1] if values else "").upper()
    if value == "PASS":
        return []
    if value == "CONCERNS":
        return [
            Finding(
                id="readiness-concerns",
                severity=Severity.MAJOR,
                summary="Readiness returned CONCERNS; logged, does not block",
            )
        ]
    return [_blocker("readiness", f"readiness returned '{value or 'nothing'}'", "AD-19")]


def _exit_codes(ctx: GateContext) -> list[Finding]:
    return []  # every step ending `done` (exit code 0) is the whole check


def _docs_updated(ctx: GateContext) -> list[Finding]:
    if not ctx.verdict_steps:
        return [_blocker("docs-verdict", "no step records the docs update (D-5)", "AD-19")]
    return _verdict_findings(ctx.records, ctx.verdict_steps)


class GateContext:
    def __init__(self, stage: Stage, gate: Gate, records: list[StepRecord]) -> None:
        self.stage = stage
        self.gate = gate
        names = {s.id for s in stage.gate_steps(gate)}
        self.records = [r for r in records if r.step in names]
        self.verdict_steps = {s.id for s in stage.gate_steps(gate) if s.verdict_source is not None}


GATES: Mapping[str, Callable[[GateContext], list[Finding]]] = {
    "judge_coverage": _review_verdicts,
    "prd_validate_and_fr_ac": _review_verdicts,
    "review_verdicts": _review_verdicts,
    "readiness": _readiness,
    "terraform_plan": _exit_codes,
    "tests": _exit_codes,
    "ci_green": _exit_codes,
    "docs_updated": _docs_updated,
}
GATE_KINDS = frozenset(GATES)


class StageVerdict:
    def __init__(
        self,
        verdicts: tuple[tuple[str, Verdict], ...],
        failed_gate: str | None,
        stories: tuple[Story, ...] | None,
    ) -> None:
        self.verdicts = verdicts
        self.failed_gate = failed_gate
        self.stories = stories


def evaluate_stage(
    stage: Stage,
    records: list[StepRecord],
    files: Mapping[str, str | None],
    target: str,
    outputs: Mapping[int, str] | None = None,
) -> StageVerdict:
    """Evaluate every gate of a stage for one round. `records` are the round's latest records.

    `outputs` maps a failed script step's `seq` to its output; its tail (at most
    `OUTPUT_TAIL` chars) goes into that step's finding, so it reaches `${feedback}` (AD-15).
    """
    outputs = outputs or {}
    by_step = {r.step: r for r in records}
    extra: dict[str, list[Finding]] = {g.kind: [] for g in stage.gate}

    # A step that did not end `done` fails the gate it feeds (AD-18).
    for step in stage.steps:
        gate = stage.gate_of_step(step.id)
        rec = by_step.get(step.id)
        if rec is None:
            extra[gate.kind].append(
                _blocker(f"{step.id}-missing", f"step '{step.id}' did not run", "AD-18")
            )
        elif rec.outcome is not Outcome.DONE:
            why = f": {rec.reason}" if rec.reason else ""
            out = (outputs.get(rec.seq) or "").strip()
            if out:
                tail = out if len(out) <= OUTPUT_TAIL else "[...]" + out[-(OUTPUT_TAIL - 5) :]
                why += f"\n{tail}"
            extra[gate.kind].append(
                _blocker(
                    f"{step.id}-outcome", f"step '{step.id}' ended {rec.outcome}{why}", "AD-18"
                )
            )

    stories: tuple[Story, ...] | None = None
    if stage.stories_file:
        text = files.get(stage.stories_file)
        first = stage.gate[0].kind
        if text is None:
            extra[first].append(
                _blocker("stories-file", f"{stage.stories_file} is missing", "AD-15")
            )
        else:
            parsed = parse_epics(text)
            for i, err in enumerate(parsed.errors, 1):
                extra[first].append(_blocker(f"stories-{i}", err, "AD-15"))
            if not parsed.errors:
                stories = parsed.stories

    verdicts: list[tuple[str, Verdict]] = []
    failed: str | None = None
    for gate in stage.gate:
        findings = list(extra[gate.kind])
        try:
            findings += GATES[gate.kind](GateContext(stage, gate, records))
        except Exception as exc:  # a gate that raises fails (AD-10)
            findings.append(_blocker(f"{gate.kind}-error", f"gate raised {exc!r}", "AD-10"))
        has_blocker = any(f.severity is Severity.BLOCKER for f in findings)
        verdicts.append(
            (
                gate.kind,
                Verdict(
                    reviewer=f"gate:{gate.kind}",
                    target=target,
                    verdict="fail" if has_blocker else "pass",
                    findings=tuple(findings),
                ),
            )
        )
        if has_blocker and failed is None:
            failed = gate.kind
    return StageVerdict(tuple(verdicts), failed, stories if failed is None else None)
