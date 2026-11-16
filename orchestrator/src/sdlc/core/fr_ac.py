"""The FR/AC check of the `prd_validate_and_fr_ac` gate (AD-19). Pure: text in, findings out.

An FR is defined by a line that starts with its ID (`FR-3`, `FR-3.1`), after any blockquote,
heading, list, checkbox, table or bold marker (`## FR-3`, `- [ ] **FR-3**:`, `| FR-3 |`).
Every defined FR must be followed by at least one `AC-` ID before the next FR definition.
An FR defined twice passes when either definition does.
"""

from __future__ import annotations

import re
from collections.abc import Mapping
from typing import NamedTuple

_FR_DEF = re.compile(
    r"^[ \t]*(?:>[ \t]*)*"  # blockquotes
    r"(?:#{1,6}[ \t]*|[-*+][ \t]+(?:\[[ xX]\][ \t]+)?|\|[ \t]*|\d+[.)][ \t]+)?"
    r"(?:\*\*|__|`)?(FR-\d+(?:\.\d+)*)\b",
    re.MULTILINE,
)
_HEADING = re.compile(r"^[ \t]*(#{1,6})[ \t]+\S", re.MULTILINE)
_AC = re.compile(r"\bAC-\d")


class FrAcResult(NamedTuple):
    defined: tuple[str, ...]
    missing: tuple[str, ...]


def _level_at(headings: list[tuple[int, int]], pos: int) -> int:
    """The level of the heading at or before `pos`; 0 when there is none."""
    level = 0
    for start, lvl in headings:
        if start > pos:
            break
        level = lvl
    return level


def check_text(text: str) -> FrAcResult:
    """Each FR's span ends at the next FR definition, or at the next heading of the same or
    higher level than the heading the FR sits under (the FR's own heading, if it is one)."""
    defs = list(_FR_DEF.finditer(text))
    headings = [(m.start(), len(m.group(1))) for m in _HEADING.finditer(text)]
    covered: dict[str, bool] = {}
    for i, m in enumerate(defs):
        end = defs[i + 1].start() if i + 1 < len(defs) else len(text)
        line_end = text.find("\n", m.end(1))
        line_end = len(text) if line_end < 0 else line_end
        level = _level_at(headings, m.start())
        for start, lvl in headings:
            if line_end <= start < end and (level == 0 or lvl <= level):
                end = start
                break
        fr = m.group(1)
        covered[fr] = covered.get(fr, False) or bool(_AC.search(text[m.end(1) : end]))
    missing = tuple(fr for fr, ok in covered.items() if not ok)
    return FrAcResult(tuple(covered), missing)


def check_prds(files: Mapping[str, str]) -> tuple[int, str]:
    """(exit code, message) over the PRD files. No file or no FR at all fails closed."""
    if not files:
        return 1, "fr_ac_check: no PRD files found"
    lines: list[str] = []
    defined = 0
    failed = False
    for path, text in sorted(files.items()):
        res = check_text(text)
        defined += len(res.defined)
        if res.missing:
            failed = True
            lines.append(f"{path}: no AC- after {', '.join(res.missing)}")
    if defined == 0:
        return 1, "fr_ac_check: no FR- definitions in the PRD files"
    if failed:
        return 1, "fr_ac_check: " + "; ".join(lines)
    return 0, f"fr_ac_check: {defined} FR definitions, each with acceptance criteria"
