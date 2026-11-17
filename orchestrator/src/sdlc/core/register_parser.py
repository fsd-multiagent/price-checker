"""Read `docs/source-register.md` with the guard's rule (AD-15, guard.py G4).

A table whose header has a `Domain` and a `Status` column lists retailers. A domain is
approved only when its Status cell starts with `Approved`.
"""

from __future__ import annotations

from typing import NamedTuple
from urllib.parse import urlparse


class Register(NamedTuple):
    listed: frozenset[str]
    approved: frozenset[str]


def norm_domain(raw: str) -> str:
    raw = raw.strip().strip("`*_<> ").lower()
    if "://" in raw:
        raw = urlparse(raw).hostname or ""
    raw = raw.split("/")[0].split(":")[0].rstrip(".")
    return raw[4:] if raw.startswith("www.") else raw


def parse_register(text: str | None) -> Register:
    listed: set[str] = set()
    approved: set[str] = set()
    d_col = s_col = None
    for line in (text or "").splitlines():
        line = line.strip()
        if not line.startswith("|"):
            d_col = s_col = None
            continue
        cells = [c.strip() for c in line.strip("|").split("|")]
        lower = [c.lower().strip("* ") for c in cells]
        if "domain" in lower and "status" in lower:
            d_col, s_col = lower.index("domain"), lower.index("status")
            continue
        if d_col is None or s_col is None or set(line) <= set("|-: "):
            continue
        if max(d_col, s_col) >= len(cells):
            continue
        domain = norm_domain(cells[d_col])
        if not domain:
            continue
        listed.add(domain)
        if cells[s_col].strip("*_` ").lower().startswith("approved"):
            approved.add(domain)
    return Register(frozenset(listed), frozenset(approved))
