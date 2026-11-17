"""Desktop notifications for escalations (AD-14): macOS `osascript`, a no-op elsewhere.

`notify` is synchronous (the port); the process runs as a task on the running loop, and
`drain()` awaits the ones still in flight before the loop closes. Errors are logged, never raised.
"""

from __future__ import annotations

import asyncio
import logging
import sys

from sdlc.adapters.proc import run_proc

log = logging.getLogger(__name__)


def _quote(text: str) -> str:
    """An AppleScript string literal."""
    return '"' + text.replace("\\", "\\\\").replace('"', '\\"') + '"'


def osascript_argv(title: str, message: str) -> tuple[str, ...]:
    script = f"display notification {_quote(message[:240])} with title {_quote(title[:120])}"
    return ("osascript", "-e", script)


class DesktopNotifier:
    def __init__(self, platform: str | None = None) -> None:
        self.enabled = (platform or sys.platform) == "darwin"
        self._pending: set[asyncio.Task[None]] = set()

    def notify(self, title: str, message: str) -> None:
        if not self.enabled:
            return
        try:
            coro = self._send(title, message)
            try:
                loop = asyncio.get_running_loop()
            except RuntimeError:
                asyncio.run(coro)
                return
            task = loop.create_task(coro)
            self._pending.add(task)
            task.add_done_callback(self._pending.discard)
        except Exception as exc:
            log.warning("notification failed: %r", exc)

    async def _send(self, title: str, message: str) -> None:
        try:
            res = await run_proc(osascript_argv(title, message), timeout_s=10)
            if not res.ok:
                log.warning("osascript failed: %s", res.tail(300))
        except Exception as exc:
            log.warning("notification failed: %r", exc)

    async def drain(self) -> None:
        if self._pending:
            await asyncio.gather(*self._pending, return_exceptions=True)
