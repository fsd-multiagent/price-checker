# 0014. Build the backend with Gradle, check it with Spotless and ArchUnit, and declare the gate commands without `cd`

- **Status:** Proposed
- **Date:** 2026-11-17
- **Deciders:** Loredan (Architect)
- **Stage / story:** stage 4, run 20261117-174705-full
- **Rules touched:** C-B4 (resolves the TBD), S-10, S-12, T-8; ADR 0004; NFR-7

## Context

C-B4 leaves the formatter and static analysis to the Architect. T-8 and ADR 0004 need test commands that run from the worktree root, split as words, with no `cd`, pipes or shell. AC-N7.2 and AC-N7.3 need a secret scan and a vulnerability scan in CI.

## Options considered

| Option | Pros | Cons |
|---|---|---|
| A. Gradle (Kotlin DSL) wrapper in `backend/`, run as `backend/gradlew -p backend check` | `-p` runs from the root with no `cd`; one `check` task covers tests, format and architecture rules | Gradle learning curve |
| B. Maven wrapper | Familiar | Running `mvnw` from the root against `backend/pom.xml` without `cd` is less reliable |

## Decision

Option A. `check` runs JUnit 5, Spotless (google-java-format), ArchUnit rules (ADR 0006), a text-scan test that no retailer domain or selector appears outside `retailer.<id>` (ArchUnit cannot see string constants), and the register test (ADR 0009). Compiler runs with `-Xlint:all -Werror`. CI additionally runs a secret scan and a dependency vulnerability scan that fails on high or critical findings.

Proposed `sdlc-commands` `tests` entries (from the worktree root):

```yaml
tests:
  - uv run --directory orchestrator pytest -q
  - backend/gradlew -p backend check
  - npm --prefix web ci
  - npm --prefix web run check
```

## Consequences

- These commands enter the `sdlc-commands` block in `AGENTS.md` only through a reviewed PR to `main` (ADR 0004); this stage does not change the block. Until then the `tests` gate runs only the orchestrator tests.
- The scaffold story adds the Gradle wrapper, `web/package.json` scripts and the CI workflow.
- Which scanners to use (for example gitleaks, OSV-Scanner) is Pacala's (DevOps) call at stage 6; the thresholds are fixed here.

## Compliance

Supports S-10 (secret scan) and S-12 (pinned versions, vulnerability scan blocking high and critical).
