from __future__ import annotations

import json
from dataclasses import dataclass
from pathlib import Path

import pytest

from sdlc.adapters.fake import FixtureBook, fake_ports
from sdlc.app.loop import Orchestrator
from sdlc.core.models import Pipeline, RunKind, RunState
from sdlc.core.pipeline import load_pipeline
from sdlc.ports import Ports

ORCH = Path(__file__).resolve().parents[1]
REPO = ORCH.parent
FIXTURES = ORCH / "tests" / "fixtures" / "dryrun"
PIPELINE_PATH = REPO / "sdlc" / "pipeline.yaml"
PROMPTS = REPO / "sdlc" / "prompts"


def pipeline_text() -> str:
    return PIPELINE_PATH.read_text(encoding="utf-8")


@pytest.fixture
def pipeline() -> Pipeline:
    return load_pipeline(pipeline_text())


@dataclass
class DryRun:
    orch: Orchestrator
    ports: Ports
    runs_dir: Path
    lines: list[str]

    @property
    def state(self) -> RunState:
        assert self.orch.state is not None
        return self.orch.state

    def unit(self, scope: str, stage: str):
        u = self.state.unit(scope, stage)
        assert u is not None, f"no unit {scope}/{stage}"
        return u

    def events(self) -> list[dict]:
        assert self.orch.store is not None
        lines = self.orch.store.events_path.read_text(encoding="utf-8").splitlines()
        return [json.loads(x) for x in lines]


def make_dry_run(
    tmp_path: Path,
    scenario: str | None = None,
    pipeline: Pipeline | None = None,
) -> DryRun:
    runs_dir = tmp_path / "runs"
    book = FixtureBook.from_scenario(FIXTURES, scenario)
    pipeline = pipeline or load_pipeline(pipeline_text())
    ports = fake_ports(book, str(runs_dir), pipeline.settings)
    lines: list[str] = []
    orch = Orchestrator(
        pipeline,
        ports,
        runs_dir,
        REPO,
        PROMPTS,
        echo=lines.append,
    )
    return DryRun(orch, ports, runs_dir, lines)


async def run_dry(dr: DryRun) -> int:
    return await dr.orch.start(RunKind.FULL, dry_run=True, fixtures=str(FIXTURES))


def statuses(state: RunState) -> list[tuple[str, str, str]]:
    return [(u.scope, u.stage, u.status.value) for u in state.units]
