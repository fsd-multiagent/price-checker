"""The one way the real adapters start a process (AD-3).

An argument list, never a shell string; each process in its own session, so a timeout or a
cancellation kills the whole process group, children included.
"""

from __future__ import annotations

import asyncio
import contextlib
import os
import signal
from collections.abc import Mapping, Sequence
from dataclasses import dataclass

from sdlc.ports import AdapterError

# After the group is killed, wait this long for the pipes to close before giving up on them.
_DRAIN_S = 5.0


@dataclass(frozen=True)
class ProcResult:
    argv: tuple[str, ...]
    returncode: int | None
    stdout: str
    stderr: str
    timed_out: bool = False

    @property
    def ok(self) -> bool:
        return self.returncode == 0 and not self.timed_out

    def tail(self, limit: int = 2000) -> str:
        """The end of stderr, or of stdout when stderr is empty, for error messages."""
        text = (self.stderr.strip() or self.stdout.strip())[-limit:]
        return text or "(no output)"


def _kill_group(pid: int) -> None:
    with contextlib.suppress(ProcessLookupError, PermissionError):
        os.killpg(pid, signal.SIGKILL)


async def run_proc(
    argv: Sequence[str],
    *,
    cwd: str | os.PathLike[str] | None = None,
    env: Mapping[str, str] | None = None,
    inherit_env: bool = True,
    stdin: str | None = None,
    timeout_s: float | None = None,
) -> ProcResult:
    """Run `argv` and capture its output. `env` is merged over the parent environment, or, with
    `inherit_env=False`, is the whole environment.

    Raises AdapterError when the executable is missing. A timeout returns `timed_out=True`
    after the process group is killed.
    """
    args = tuple(str(a) for a in argv)
    if not args:
        raise AdapterError("empty command")
    full_env = {**(os.environ if inherit_env else {}), **(env or {})}
    try:
        proc = await asyncio.create_subprocess_exec(
            *args,
            cwd=None if cwd is None else str(cwd),
            env=full_env,
            stdin=asyncio.subprocess.PIPE if stdin is not None else asyncio.subprocess.DEVNULL,
            stdout=asyncio.subprocess.PIPE,
            stderr=asyncio.subprocess.PIPE,
            start_new_session=True,
        )
    except (FileNotFoundError, PermissionError, NotADirectoryError) as exc:
        raise AdapterError(f"cannot start {args[0]}: {exc}") from exc

    data = stdin.encode("utf-8") if stdin is not None else None
    comm = asyncio.ensure_future(proc.communicate(data))
    timed_out = False
    try:
        out, err = await asyncio.wait_for(asyncio.shield(comm), timeout_s)
    except TimeoutError:
        timed_out = True
        if proc.returncode is None:
            _kill_group(proc.pid)
        try:
            out, err = await asyncio.wait_for(comm, _DRAIN_S)
        except TimeoutError:  # a grandchild in another group still holds the pipes
            comm.cancel()
            out, err = b"", b""
    except asyncio.CancelledError:
        if proc.returncode is None:
            _kill_group(proc.pid)
        comm.cancel()
        with contextlib.suppress(BaseException):
            await proc.wait()
        raise
    finally:
        # Only while the leader is unreaped: its pid (the group id) cannot have been reused yet.
        if proc.returncode is None:
            _kill_group(proc.pid)
    if timed_out:
        with contextlib.suppress(TimeoutError):
            await asyncio.wait_for(proc.wait(), _DRAIN_S)
    return ProcResult(
        argv=args,
        returncode=proc.returncode,
        stdout=out.decode("utf-8", errors="replace"),
        stderr=err.decode("utf-8", errors="replace"),
        timed_out=timed_out,
    )
