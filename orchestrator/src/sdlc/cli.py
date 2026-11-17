"""`sdlc run [--from <stage>] [--dry-run]`, `sdlc status [<run-id>]`, `sdlc resume <run-id>`.

Exit codes: 0 passed, 2 escalated or blocked, 1 internal error. A real run checks its tools
first (preflight) and keeps its state in `_bmad-output/sdlc/runs`. The repo lock, the trigger
queue and `sdlc story|fix` are deferred (deferred-work.md, AD-22).
"""

from __future__ import annotations

import argparse
import asyncio
import hashlib
import json
import sys
import tempfile
from collections.abc import Awaitable, Sequence
from pathlib import Path

from sdlc.adapters.clock import SystemClock
from sdlc.adapters.fake import FixtureBook, fake_ports
from sdlc.adapters.real import preflight, real_ports
from sdlc.app.loop import LoopError, Orchestrator, format_status
from sdlc.core.models import CoreError, Pipeline, RunKind, Status
from sdlc.core.pipeline import PipelineError, load_pipeline
from sdlc.ports import Ports
from sdlc.store import RunStore, list_runs


def find_repo_root(start: Path | None = None) -> Path:
    here = (start or Path.cwd()).resolve()
    for p in (here, *here.parents):
        if (p / "sdlc" / "pipeline.yaml").is_file():
            return p
    return Path(__file__).resolve().parents[3]


def _parser() -> argparse.ArgumentParser:
    ap = argparse.ArgumentParser(prog="sdlc", description="Zamolxe (Orchestrator)")
    ap.add_argument("--repo", type=Path, help="repo root (default: found from the cwd)")
    ap.add_argument("--runs-dir", type=Path, help="run dirs (default: _bmad-output/sdlc/runs)")
    sub = ap.add_subparsers(dest="cmd", required=True)
    run = sub.add_parser("run", help="start a full run")
    run.add_argument("--from", dest="from_stage", help="start at this stage")
    run.add_argument("--dry-run", action="store_true", help="fake adapters, temporary run dir")
    run.add_argument("--pipeline", type=Path, help="pipeline file (default: sdlc/pipeline.yaml)")
    run.add_argument("--scenario", help=argparse.SUPPRESS)  # a dry-run fixture scenario
    st = sub.add_parser("status", help="show a run's state")
    st.add_argument("run_id", nargs="?")
    rs = sub.add_parser("resume", help="resume a crashed or escalated run")
    rs.add_argument("run_id")
    return ap


def main(argv: Sequence[str] | None = None) -> int:
    args = _parser().parse_args(argv)
    repo = (args.repo or find_repo_root()).resolve()
    try:
        if args.cmd == "run":
            return _run(args, repo)
        if args.cmd == "status":
            return _status(args, repo)
        return _resume(args, repo)
    except CoreError as exc:
        print(f"sdlc: cannot continue: {exc}", file=sys.stderr)
        return 1
    except (PipelineError, LoopError, KeyError, OSError) as exc:
        print(f"sdlc: error: {exc}", file=sys.stderr)
        return 1
    except Exception as exc:  # exit code 1 means an internal error
        print(f"sdlc: internal error: {exc!r}", file=sys.stderr)
        return 1


def _runs_dir(args: argparse.Namespace, repo: Path) -> Path:
    return args.runs_dir or repo / "_bmad-output" / "sdlc" / "runs"


# Per-stage limits a resume may change; together with `settings` they are left out of the digest.
LIMITS = frozenset(
    {"title", "max_rounds", "timeout_min", "budget_usd", "model", "effort", "max_turns"}
)


def stage_table_digest(pipeline: Pipeline) -> str:
    """sha256 of the stage table only: stage ids, order, steps, gates, on_fail, entry, per_story.

    Settings (`run_budget_usd`, `max_parallel`, ...) and per-stage limits are excluded, so the
    owner can fix that config and resume (plan §4.4); a changed stage table refuses a resume.
    """
    table = [
        {k: v for k, v in s.model_dump(mode="json").items() if k not in LIMITS}
        for s in pipeline.stages
    ]
    blob = json.dumps(table, sort_keys=True, separators=(",", ":"))
    return hashlib.sha256(blob.encode("utf-8")).hexdigest()


def _load(path: Path) -> tuple[Pipeline, str]:
    """The pipeline and its stage-table digest, so a resume can refuse a changed stage table."""
    pipeline = load_pipeline(path.read_text(encoding="utf-8"))
    return pipeline, stage_table_digest(pipeline)


def _fixtures(repo: Path) -> Path:
    return repo / "orchestrator" / "tests" / "fixtures" / "dryrun"


def _run(args: argparse.Namespace, repo: Path) -> int:
    pipeline_path = (args.pipeline or repo / "sdlc" / "pipeline.yaml").resolve()
    pipeline, digest = _load(pipeline_path)
    if args.from_stage:
        pipeline.stage(args.from_stage)
    if not args.dry_run:
        return _run_real(args, repo, pipeline, pipeline_path, digest)
    runs_dir = args.runs_dir or Path(tempfile.mkdtemp(prefix="sdlc-dryrun-"))
    fixtures = _fixtures(repo)
    book = FixtureBook.from_scenario(fixtures, args.scenario)
    orch = Orchestrator(
        pipeline, fake_ports(book, str(runs_dir)), runs_dir, repo, repo / "sdlc" / "prompts"
    )
    code = asyncio.run(
        orch.start(
            RunKind.FULL,
            dry_run=True,
            from_stage=args.from_stage,
            fixtures=str(fixtures),
            scenario=args.scenario,
            pipeline_path=str(pipeline_path),
            pipeline_sha256=digest,
            stories_reader=book.written_file,
        )
    )
    assert orch.state is not None
    print()
    print(format_status(orch.state, orch.ports.clock.now()))
    print(f"\nstatus: sdlc --runs-dir {runs_dir} status {orch.state.run_id}")
    return code


def _preflight(repo: Path, pipeline: Pipeline) -> bool:
    problems = asyncio.run(preflight(repo, allow_api_key=pipeline.settings.allow_api_key))
    for problem in problems:
        print(f"sdlc: preflight: {problem}", file=sys.stderr)
    return not problems


async def _drive(ports: Ports, run: Awaitable[int]) -> int:
    """Await the run, then any notification still in flight."""
    try:
        return await run
    finally:
        await ports.notifier.drain()


def _run_real(
    args: argparse.Namespace, repo: Path, pipeline: Pipeline, pipeline_path: Path, digest: str
) -> int:
    if not _preflight(repo, pipeline):  # before the run dir exists
        return 1
    runs_dir = _runs_dir(args, repo)
    ports = real_ports(repo, pipeline.settings.allow_api_key)
    orch = Orchestrator(pipeline, ports, runs_dir, repo, repo / "sdlc" / "prompts")
    code = asyncio.run(
        _drive(
            ports,
            orch.start(
                RunKind.FULL,
                dry_run=False,
                from_stage=args.from_stage,
                pipeline_path=str(pipeline_path),
                pipeline_sha256=digest,
            ),
        )
    )
    assert orch.state is not None
    print()
    print(format_status(orch.state, ports.clock.now()))
    print(f"\nstatus: sdlc status {orch.state.run_id}")
    return code


def _status(args: argparse.Namespace, repo: Path) -> int:
    runs_dir = _runs_dir(args, repo)
    run_id = args.run_id or next(reversed(list_runs(runs_dir)), None)
    if run_id is None:
        print(f"sdlc: no runs in {runs_dir}", file=sys.stderr)
        return 1
    state = RunStore(runs_dir / run_id).load()
    print(format_status(state, SystemClock().now()))
    return 0


def _resume(args: argparse.Namespace, repo: Path) -> int:
    runs_dir = _runs_dir(args, repo)
    state = RunStore(runs_dir / args.run_id).load()
    if state.status is Status.PASSED:
        print(f"sdlc: run {state.run_id} already passed; nothing to resume", file=sys.stderr)
        return 1
    pipeline_path = Path(state.pipeline_path or repo / "sdlc" / "pipeline.yaml")
    pipeline, digest = _load(pipeline_path)
    if state.pipeline_sha256 and digest != state.pipeline_sha256:
        print(
            f"sdlc: the stage table in {pipeline_path} changed since run {state.run_id} started "
            f"(sha256 {state.pipeline_sha256[:12]} then, {digest[:12]} now); restore it or "
            "start a new run. Settings and per-stage limits may change.",
            file=sys.stderr,
        )
        return 1
    if state.budget.exhausted and pipeline.settings.run_budget_usd <= state.budget.limit:
        print(
            f"sdlc: run budget exhausted (${state.budget.spent:.2f} spent of "
            f"${pipeline.settings.run_budget_usd:.2f}): raise run_budget_usd in "
            f"sdlc/pipeline.yaml, then sdlc resume {state.run_id}",
            file=sys.stderr,
        )
        return 2
    if state.dry_run:
        book = FixtureBook.from_scenario(state.fixtures or _fixtures(repo), state.scenario)
        ports = fake_ports(book, str(runs_dir))
    else:
        if not _preflight(repo, pipeline):
            return 1
        ports = real_ports(repo, pipeline.settings.allow_api_key)
    orch = Orchestrator(pipeline, ports, runs_dir, repo, repo / "sdlc" / "prompts")
    code = asyncio.run(_drive(ports, orch.resume(args.run_id)))
    assert orch.state is not None
    print()
    print(format_status(orch.state, ports.clock.now()))
    return code


if __name__ == "__main__":
    raise SystemExit(main())
