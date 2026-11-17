"""The system clock: UTC, ISO 8601 with `Z`."""

from __future__ import annotations

from datetime import UTC, datetime


class SystemClock:
    def now(self) -> str:
        return datetime.now(UTC).strftime("%Y-%m-%dT%H:%M:%SZ")
