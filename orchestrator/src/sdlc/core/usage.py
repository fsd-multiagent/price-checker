"""Usage records: the only aggregator (AD-24). Pure: records come in, rows and text go out.

Every report sums records only here: `sdlc status` totals, `sdlc usage --by ...` and the
retrospective summary.
"""

from __future__ import annotations

import re
from collections.abc import Iterable
from dataclasses import dataclass

from sdlc.core.models import ModelUsage, RunState, StepKind, UsageRecord

BY = ("agent", "stage", "model", "round", "run")
UNKNOWN_ROLE = "unknown"
UNATTRIBUTED = "(unattributed)"  # cost no `modelUsage` entry accounts for


def agent_slug(role: str) -> str:
    """`Marghioala (Developer)` -> `marghioala_developer` (a telemetry label, AD-25)."""
    return re.sub(r"[^a-z0-9]+", "_", role.lower()).strip("_") or UNKNOWN_ROLE


@dataclass(frozen=True)
class UsageRow:
    key: str
    attempts: int = 0
    input_tokens: int = 0
    output_tokens: int = 0
    cache_read_input_tokens: int = 0
    cache_creation_input_tokens: int = 0
    cost_usd: float = 0.0
    incomplete: int = 0  # attempts whose tokens are unknown (`usage_complete: false`)

    @property
    def tokens(self) -> int:
        return (
            self.input_tokens
            + self.output_tokens
            + self.cache_read_input_tokens
            + self.cache_creation_input_tokens
        )


def state_records(state: RunState) -> tuple[UsageRecord, ...]:
    """The final usage records on a run state, in step order."""
    return tuple(r.usage for r in state.steps if r.usage is not None)


def dedupe(records: Iterable[UsageRecord]) -> tuple[UsageRecord, ...]:
    """One record per `(run_id, seq)`; the first wins (the ledger is append-only)."""
    seen: dict[tuple[str, int], UsageRecord] = {}
    for r in records:
        seen.setdefault(r.key, r)
    return tuple(seen.values())


def _add(row: UsageRow, tokens: object, cost: float, incomplete: bool) -> UsageRow:
    return UsageRow(
        key=row.key,
        attempts=row.attempts + 1,
        input_tokens=row.input_tokens + getattr(tokens, "input_tokens", 0),
        output_tokens=row.output_tokens + getattr(tokens, "output_tokens", 0),
        cache_read_input_tokens=row.cache_read_input_tokens
        + getattr(tokens, "cache_read_input_tokens", 0),
        cache_creation_input_tokens=row.cache_creation_input_tokens
        + getattr(tokens, "cache_creation_input_tokens", 0),
        cost_usd=row.cost_usd + cost,
        incomplete=row.incomplete + (1 if incomplete else 0),
    )


def _key(r: UsageRecord, by: str) -> str:
    """A record's single key. By model that is its costliest model; `aggregate` splits it."""
    if by == "model":
        return r.model or UNATTRIBUTED
    if by == "agent":
        return r.agent_role
    if by == "stage":
        return r.stage
    if by == "round":
        return f"{r.scope}/{r.stage} r{r.round}"
    if by == "run":
        return r.run_id
    raise ValueError(f"unknown grouping '{by}' (known: {', '.join(BY)})")


def aggregate(records: Iterable[UsageRecord], by: str) -> tuple[UsageRow, ...]:
    """Rows keyed by agent, stage, model, round or run, in first-seen order.

    By model, each record's tokens go to the models of its breakdown, and its per-model costs
    are scaled so they sum to the record's `cost_usd` (what the budget was charged). A record
    without a breakdown, or whose breakdown has no cost, counts its cost under
    `(unattributed)`. So the cost total by model equals the total by any other key.
    """
    if by not in BY:
        raise ValueError(f"unknown grouping '{by}' (known: {', '.join(BY)})")
    rows: dict[str, UsageRow] = {}
    for r in records:
        if by == "model":
            for key, tokens, cost in _model_parts(r):
                rows[key] = _add(rows.get(key, UsageRow(key)), tokens, cost, not r.usage_complete)
            continue
        key = _key(r, by)
        rows[key] = _add(rows.get(key, UsageRow(key)), r, r.cost_usd, not r.usage_complete)
    return tuple(rows.values())


def _model_parts(r: UsageRecord) -> list[tuple[str, object, float]]:
    listed = sum(m.cost_usd for m in r.models)
    if r.models and listed > 0:
        return [(m.model, m, m.cost_usd * r.cost_usd / listed) for m in r.models]
    parts: list[tuple[str, object, float]] = [(m.model, m, 0.0) for m in r.models]
    return [*parts, (UNATTRIBUTED, None, r.cost_usd)]


def totals(records: Iterable[UsageRecord], key: str = "total") -> UsageRow:
    row = UsageRow(key)
    for r in records:
        row = _add(row, r, r.cost_usd, not r.usage_complete)
    return row


def sum_models(models: Iterable[ModelUsage]) -> dict[str, int]:
    """Token totals over `modelUsage` (AD-24)."""
    out = {
        "input_tokens": 0,
        "output_tokens": 0,
        "cache_read_input_tokens": 0,
        "cache_creation_input_tokens": 0,
    }
    for m in models:
        for k in out:
            out[k] += getattr(m, k)
    return out


def format_rows(rows: Iterable[UsageRow], heading: str, *, markdown: bool = False) -> str:
    rows = tuple(rows)
    cols = ("attempts", "input", "output", "cache read", "cache write", "cost USD", "incomplete")
    data = [
        (
            r.key,
            str(r.attempts),
            str(r.input_tokens),
            str(r.output_tokens),
            str(r.cache_read_input_tokens),
            str(r.cache_creation_input_tokens),
            f"{r.cost_usd:.2f}",
            str(r.incomplete),
        )
        for r in rows
    ]
    if markdown:
        lines = [
            f"| {heading} | " + " | ".join(cols) + " |",
            "|---|" + "---:|" * len(cols),
        ]
        lines += ["| " + " | ".join(d) + " |" for d in data]
        return "\n".join(lines)
    header = (heading, *cols)
    widths = [max(len(x) for x in col) for col in zip(header, *data, strict=True)]
    fmt = "  ".join(f"{{:<{widths[0]}}}" if i == 0 else f"{{:>{w}}}" for i, w in enumerate(widths))
    return "\n".join(fmt.format(*line) for line in (header, *data))


def status_line(state: RunState) -> str:
    """The usage line of `sdlc status` (AD-24)."""
    t = totals(state_records(state))
    return (
        f"usage: {t.attempts} attempts (agents and judges), {t.tokens} tokens, ${t.cost_usd:.2f}"
        f"{f' ({t.incomplete} without token counts)' if t.incomplete else ''}"
    )


def epic_records(state: RunState, epic: int) -> tuple[UsageRecord, ...]:
    """The records of an epic's steps in this run: its stories' story and learn scopes, and the
    epic's own scope (`learn:epic-<n>`, earlier retrospective rounds)."""
    keys = {s.key for s in state.stories if s.epic == epic}
    scopes = {f"{family}:{k}" for k in keys for family in ("story", "learn")}
    scopes.add(f"learn:epic-{epic}")
    return tuple(r for r in state_records(state) if r.scope in scopes)


def render_summary(state: RunState, epic: int) -> str:
    """`.sdlc/usage-summary.md` for the epic's retrospective (AD-24)."""
    records = epic_records(state, epic)
    t = totals(records)
    parts = [
        f"# Usage summary: epic {epic}, run {state.run_id}",
        "",
        f"This summary covers run {state.run_id} only: steps of this epic in earlier runs are",
        "not included.",
        "",
        "Written by Zamolxe (Orchestrator) before the retrospective (AD-24). Costs are",
        "API-equivalent estimates (ADR 0005). Tokens are summed over every model, subagents",
        "included. `incomplete` counts attempts whose tokens are unknown (a timeout, crash or",
        "interrupt); their cost is the amount charged to the budget.",
        "",
        f"Total: {t.attempts} attempts, {t.tokens} tokens, ${t.cost_usd:.2f}.",
    ]
    if not records:
        parts += ["", "No agent or judge step of this epic ran in this run."]
        return "\n".join(parts) + "\n"
    for by, heading in (("agent", "agent"), ("stage", "stage"), ("round", "scope/stage round")):
        parts += [
            "",
            f"## By {by}",
            "",
            format_rows(aggregate(records, by), heading, markdown=True),
        ]
    parts += [
        "",
        "## What to recommend",
        "",
        "Record each change you recommend to `model`, `effort` or `max_turns` per stage or step in",
        "`sdlc/pipeline.yaml` as an action item. Do not edit `sdlc/` yourself: the owner",
        "changes the pipeline through a reviewed PR.",
    ]
    return "\n".join(parts) + "\n"


def is_measured(kind: StepKind) -> bool:
    """Agent and judge steps get a usage record; script steps use no model (AD-24)."""
    return kind is not StepKind.SCRIPT


__all__ = [
    "BY",
    "UNKNOWN_ROLE",
    "UsageRow",
    "agent_slug",
    "aggregate",
    "dedupe",
    "epic_records",
    "format_rows",
    "is_measured",
    "render_summary",
    "state_records",
    "status_line",
    "sum_models",
    "totals",
]
