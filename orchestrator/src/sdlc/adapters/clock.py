"""The system clock: UTC, ISO 8601 with `Z`."""

from __future__ import annotations

import asyncio
from datetime import UTC, datetime

FORMAT = "%Y-%m-%dT%H:%M:%SZ"


class SystemClock:
    def now(self) -> str:
        return datetime.now(UTC).strftime(FORMAT)

    async def sleep_until(self, until: str) -> None:
        """Sleep until `until` (AD-1, AD-18), in slices so a changed wall clock is noticed."""
        target = datetime.strptime(until, FORMAT).replace(tzinfo=UTC)
        while (left := (target - datetime.now(UTC)).total_seconds()) > 0:
            await asyncio.sleep(min(left, 60.0))
