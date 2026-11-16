"""The local file system."""

from __future__ import annotations

from pathlib import Path


class LocalFs:
    def read_text(self, path: str) -> str | None:
        try:
            return Path(path).read_text(encoding="utf-8")
        except (FileNotFoundError, IsADirectoryError, NotADirectoryError):
            return None

    def write_text(self, path: str, text: str) -> None:
        p = Path(path)
        p.parent.mkdir(parents=True, exist_ok=True)
        p.write_text(text, encoding="utf-8")

    def remove(self, path: str) -> None:
        Path(path).unlink(missing_ok=True)

    def make_dirs(self, path: str) -> None:
        Path(path).mkdir(parents=True, exist_ok=True)
