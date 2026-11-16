"""core/epics_parser.py, core/register_parser.py, core/text.py, core/outcome.py."""

from __future__ import annotations

from conftest import FIXTURES, REPO

from sdlc.core.epics_parser import parse_epics
from sdlc.core.models import Outcome, OutcomeSource, Step, StepKind, StepResult
from sdlc.core.outcome import derive_outcome
from sdlc.core.register_parser import norm_domain, parse_register
from sdlc.core.text import last_json_block, result_json


def block(body: str) -> str:
    return f"```yaml\n{body}\n```\n"


def test_epics_fixture_parses():
    parsed = parse_epics((FIXTURES / "epics.md").read_text(encoding="utf-8"))
    assert parsed.errors == ()
    assert [s.key for s in parsed.stories] == [
        "1-1-ean-input",
        "1-2-retailer-config",
        "1-3-compare-page",
    ]
    assert parsed.stories[2].depends_on == ("1-1-ean-input", "1-2-retailer-config")
    assert parsed.stories[0].epic == 1


def test_missing_sources_names_the_story():
    text = block("key: 1-1-a\ntitle: A\ndepends_on: []\ntouches: []")
    assert parse_epics(text).errors == ("story '1-1-a': missing 'sources'",)


def test_empty_sources_is_allowed():
    assert (
        parse_epics(block("key: 1-1-a\ntitle: A\ndepends_on: []\ntouches: []\nsources: []")).errors
        == ()
    )


def test_null_sources_is_an_error():
    text = block("key: 1-1-a\ntitle: A\ndepends_on: []\ntouches: []\nsources:")
    assert parse_epics(text).errors == (
        "story '1-1-a': 'sources' must be a list (use [] for none)",
    )


def test_sources_use_the_register_domain_rule():
    text = block(
        "key: 1-1-a\ntitle: A\ndepends_on: []\ntouches: []\n"
        "sources: ['`WWW.Shop.Example`', 'https://other.example/x']"
    )
    assert parse_epics(text).stories[0].sources == ("shop.example", "other.example")


def test_unknown_dependency_names_the_story():
    text = block("key: 1-1-a\ntitle: A\ndepends_on: [2-1-z]\ntouches: []\nsources: []")
    assert parse_epics(text).errors == ("story '1-1-a': depends_on unknown story '2-1-z'",)


def test_bad_key_duplicate_and_cycle():
    assert (
        "key must match N-M-slug"
        in parse_epics(
            block("key: Story1\ntitle: A\ndepends_on: []\ntouches: []\nsources: []")
        ).errors[0]
    )
    dup = block("key: 1-1-a\ntitle: A\ndepends_on: []\ntouches: []\nsources: []") * 2
    assert "story '1-1-a': duplicate key" in parse_epics(dup).errors
    cyc = block("key: 1-1-a\ntitle: A\ndepends_on: [1-2-b]\ntouches: []\nsources: []") + block(
        "key: 1-2-b\ntitle: B\ndepends_on: [1-1-a]\ntouches: []\nsources: []"
    )
    assert "dependency cycle" in parse_epics(cyc).errors[0]


def test_no_blocks_and_non_list_fields():
    assert parse_epics("# nothing here").errors
    bad = block("key: 1-1-a\ntitle: A\ndepends_on: 1-0-x\ntouches: []\nsources: []")
    assert "story '1-1-a': 'depends_on' must be a list" in parse_epics(bad).errors


def test_register_uses_the_guard_rule():
    text = (
        "| Retailer | Domain | Status |\n|---|---|---|\n"
        "| A | `www.a.example` | Approved 2026-11-16 |\n"
        "| B | b.example | Proposed |\n"
        "| C | https://c.example/x | **Approved** |\n"
        "| D | d.example | Not approved |\n"
    )
    reg = parse_register(text)
    assert reg.approved == {"a.example", "c.example"}
    assert reg.listed == {"a.example", "b.example", "c.example", "d.example"}
    assert norm_domain("`WWW.Shop.Example:443/`") == "shop.example"
    assert parse_register(None).approved == frozenset()


def test_real_register_has_no_approved_rows_yet():
    reg = parse_register((REPO / "docs" / "source-register.md").read_text(encoding="utf-8"))
    assert "emag.ro" in reg.listed
    assert reg.approved == frozenset()


def test_json_blocks():
    assert last_json_block('a\n```json\n{"x": 1}\n```\nb\n```json\n{"x": 2}\n```') == {"x": 2}
    assert last_json_block("```json\nnot json\n```") is None
    assert result_json('{"status": "done"}') == {"status": "done"}
    assert result_json(None) is None


AGENT = Step(
    id="a", kind=StepKind.AGENT, skill="s", prompt="p", outcome_source=OutcomeSource.RESULT_STATUS
)


def test_outcome_mapping():
    def out(text, **kw):
        return derive_outcome(AGENT, StepResult(result=text, **kw), None).outcome

    assert out('{"status": "done"}') is Outcome.DONE
    assert out('{"status": "PASS"}') is Outcome.DONE
    assert out('{"status": "FAIL"}') is Outcome.ERROR  # not a readiness step
    assert out('{"status": "CONCERNS"}') is Outcome.ERROR
    assert out('{"status": "blocked"}') is Outcome.BLOCKED
    assert out('{"status": "ambiguous"}') is Outcome.AMBIGUOUS
    assert (
        out('{"status": "done", "open_questions": [{"question": "q", "blocking": true}]}')
        is Outcome.AMBIGUOUS
    )
    assert (
        out('{"status": "done", "open_questions": [{"question": "q", "blocking": false}]}')
        is Outcome.DONE
    )
    assert out("no status here") is Outcome.ERROR
    assert out('{"status": "done"}', subtype="error_max_turns", is_error=True) is Outcome.ERROR


def test_readiness_words_only_for_readiness_steps():
    for word in ("PASS", "CONCERNS", "FAIL"):
        d = derive_outcome(
            AGENT, StepResult(result=f'{{"status": "{word}"}}'), None, readiness=True
        )
        assert d.outcome is Outcome.DONE and d.status_value == word


def test_outcome_from_verdict_and_exit_code():
    judge = Step(
        id="j",
        kind=StepKind.JUDGE,
        prompt="p",
        verdict_source="result_block",
        outcome_source=OutcomeSource.VERDICT,
    )
    assert derive_outcome(judge, StepResult(), None).outcome is Outcome.ERROR
    script = Step(
        id="t", kind=StepKind.SCRIPT, script="tests", outcome_source=OutcomeSource.EXIT_CODE
    )
    assert derive_outcome(script, StepResult(exit_code=0), None).outcome is Outcome.DONE
    assert derive_outcome(script, StepResult(exit_code=1), None).outcome is Outcome.ERROR
