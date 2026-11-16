"""Pure helpers over agent output text."""

from __future__ import annotations

import json
import re
from typing import Any

_FENCE = re.compile(r"```(?P<lang>[A-Za-z0-9_-]*)[^\n]*\n(?P<body>.*?)```", re.DOTALL)


def fenced_blocks(text: str, lang: str) -> list[str]:
    return [m.group("body") for m in _FENCE.finditer(text or "") if m.group("lang") == lang]


def last_json_block(text: str | None) -> Any | None:
    """The last fenced `json` block of a result, parsed; None if there is none or it is invalid."""
    for body in reversed(fenced_blocks(text or "", "json")):
        try:
            return json.loads(body)
        except json.JSONDecodeError:
            return None
    return None


def result_json(text: str | None) -> Any | None:
    """The result as JSON: the whole text if it parses, else its last fenced `json` block."""
    if not text:
        return None
    try:
        return json.loads(text)
    except json.JSONDecodeError:
        return last_json_block(text)
