"""A stand-in `gh` executable for the real-adapter tests. Never touches GitHub.

`STUB_LOG` gets one JSON line per call. `STUB_GH_CONFIG` names a JSON file of canned answers:
- `pr_list`, `checks`, `issues`: the JSON printed by `pr list`, `pr checks`, `issue list`
- `checks_exit`: the exit code of `pr checks` (gh exits non-zero while checks are not green)
- `checks_seq`: a list of `pr checks` answers, one per poll, the last one repeating; each is a
  JSON value, or the string "error" for a failed call. Overrides `checks`.
- `view`: a list of `pr view` answers, one per poll, the last one repeating; "error" fails
- `version`: the `gh --version` output
- `auth_exit`: the exit code of `auth status`
- `api_items`: the items `api --paginate .../issues` prints, one JSON object per line, as the
  `--jq` filter would (`number`, `body`, `pr`)
- `issue`: the JSON printed by `issue view`
- `graphql`: the JSON printed by `api graphql`
- `timeline`: the items `api --paginate .../timeline` prints, one JSON object per line
"""

from __future__ import annotations

import json
import os
import sys


def _calls(log: str | None, prefix: list[str]) -> int:
    if not log or not os.path.exists(log):
        return 0
    with open(log, encoding="utf-8") as f:
        lines = [json.loads(x) for x in f if x.strip()]
    return sum(1 for c in lines if c["tool"] == "gh" and c["argv"][: len(prefix)] == prefix)


def main() -> int:
    args = sys.argv[1:]
    log = os.environ.get("STUB_LOG")
    cfg_path = os.environ.get("STUB_GH_CONFIG")
    cfg = {}
    if cfg_path and os.path.exists(cfg_path):
        with open(cfg_path, encoding="utf-8") as f:
            cfg = json.load(f)
    seen = _calls(log, args[:2])  # calls of this subcommand before this one
    if log:
        with open(log, "a", encoding="utf-8") as f:
            f.write(json.dumps({"tool": "gh", "argv": args, "cwd": os.getcwd()}) + "\n")
    cmd = args[:2]
    if args[:1] == ["--version"]:
        print(cfg.get("version", "gh version 2.96.0 (2026-01-01)"))
        return 0
    if args[:2] == ["api", "graphql"]:
        print(json.dumps(cfg.get("graphql", {})))
        return 0
    if args[:1] == ["api"] and any("/timeline" in a for a in args):
        for item in cfg.get("timeline", []):
            print(json.dumps(item))
        return 0
    if args[:1] == ["api"]:
        for item in cfg.get("api_items", []):
            print(json.dumps(item))
        return 0
    if cmd == ["auth", "status"]:
        return int(cfg.get("auth_exit", 0))
    if cmd == ["pr", "list"]:
        print(json.dumps(cfg.get("pr_list", [])))
    elif cmd == ["pr", "create"]:
        print("https://github.com/example/repo/pull/42")
    elif cmd == ["pr", "checks"]:
        if "checks_seq" in cfg:
            seq = cfg["checks_seq"]
            answer = seq[min(seen, len(seq) - 1)]
            if answer == "error":
                print("gh: transient failure", file=sys.stderr)
                return 1
            print(json.dumps(answer))
            return 0
        print(json.dumps(cfg.get("checks", [])))
        return int(cfg.get("checks_exit", 0))
    elif cmd == ["pr", "view"]:
        views = cfg.get("view") or [{"state": "OPEN"}]
        answer = views[min(seen, len(views) - 1)]
        if answer == "error":
            print("gh: transient failure", file=sys.stderr)
            return 1
        print(json.dumps(answer))
    elif cmd == ["issue", "view"]:
        print(json.dumps(cfg.get("issue", {})))
    elif cmd == ["issue", "list"]:
        print(json.dumps(cfg.get("issues", [])))
    elif cmd == ["issue", "create"]:
        print("https://github.com/example/repo/issues/7")
    elif cmd in (
        ["pr", "merge"],
        ["pr", "edit"],
        ["issue", "comment"],
        ["issue", "reopen"],
        ["label", "create"],
    ):
        pass
    else:
        print(f"gh stub: unsupported {args}", file=sys.stderr)
        return 2
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
