---
title: 'CI for backend and web with secret and vulnerability scans'
type: 'chore'
created: '2026-11-18'
status: 'done'
baseline_revision: '04280966b48c39f421b430d99429faaed9825a56'
review_loop_iteration: 0
followup_review_recommended: true
context:
  - '{project-root}/docs/adr/0014-backend-build-and-gate-commands.md'
warnings: []
deferred:
  - summary: >-
      Trivy canary covers npm only; no jar canary, and no assertion that real scans found their targets or that the gitleaks range is non-empty.
    evidence: |-
      Blind Hunter and Verification Gap; jar scan verified locally only.
    location: >-
      .github/workflows/ci.yml vulnerability-scan, secret-scan
    severity: medium
  - summary: >-
      No test asserts that the aggregate ci job needs every other job and runs unless cancelled.
    evidence: |-
      Verification Gap; fix belongs in orchestrator/tests, outside this story's touches.
    severity: medium
  - summary: >-
      Gradle wrapper jar not validated in CI; npm install scripts run on PRs.
    evidence: |-
      Blind Hunter; S-12 hardening outside the story ACs.
    severity: medium
  - summary: >-
      Scanner choice (gitleaks + Trivy, range scan, runtime-only deps) needs an ADR (D-2); epic-1-context still says scanner choice is open; an unfixable runtime advisory would block every PR (no .trivyignore process).
    evidence: |-
      Blind Hunter, Edge Case Hunter; docs/adr is outside touches.
    severity: medium
---

<intent-contract>

## Intent

**Problem:** `.github/workflows/ci.yml` runs only the orchestrator job. Backend and web changes merge without build, lint, format or test checks, and nothing scans for committed secrets or vulnerable dependencies (T-8, S-10, S-12, AC-N7.2, AC-N7.3).

**Approach:** Add `backend`, `web`, `secret-scan` and `vulnerability-scan` jobs next to the orchestrator job, and keep `ci` as the one required check by making it an aggregate that fails when any of them fails. Scanners are gitleaks (commit range of the PR or push) and Trivy (runtime dependencies in lockfiles and the backend boot jar), downloaded at pinned versions with pinned SHA-256 checksums. Each scan job first runs a canary that proves the scanner fails on a planted finding.

## Boundaries & Constraints

**Always:** Actions pinned to commit SHAs and binaries to version plus SHA-256 (S-12); `permissions: contents: read`; no secrets used (S-10); severity threshold HIGH,CRITICAL; backend command `backend/gradlew -p backend check`, web commands `npm --prefix web ci` and `npm --prefix web run check` (ADR 0014), plus `npm --prefix web run build`; the required check stays named `ci`.

**Never:** Contact a retailer (T-3); edit `AGENTS.md` `sdlc-commands`, backend or web sources; add a gitleaks baseline or allowlist file; scan the whole git history (55 pre-existing false positives in BMAD docs and test fixtures); use `aquasecurity/trivy-action` or `gitleaks-action` (third-party action supply chain; gitleaks-action needs a licence for orgs).

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Clean PR | this story's PR | all jobs green, `ci` green | none |
| Secret in a PR commit | commit adds an AWS-style key, even if a later commit removes it | `secret-scan` fails, `ci` fails | gitleaks report redacted in log |
| Vulnerable runtime dep | lockfile or boot jar has a HIGH/CRITICAL advisory | `vulnerability-scan` fails, `ci` fails | Trivy table in log |
| Scanner broken | canary finding not detected | job fails before the real scan | step error message |
| Push to main | `github.event.before`..`github.sha` | range scanned | zero `before` (new ref) scans the pushed commit only |

</intent-contract>

## Code Map

- `.github/workflows/ci.yml` -- only file changed; existing job `ci` (orchestrator, guard tests) becomes job `orchestrator`; new aggregate job `ci` with `needs` all jobs and `if: always()`.
- `.github/workflows/terraform-plan.yml` -- style reference (header comment, SHA pins).
- `backend/build.gradle.kts` -- Java 21 toolchain; `bootJar` produces `backend/build/libs/price-checker-backend-0.0.1.jar` (no Gradle lockfile exists, so the jar is what Trivy scans).
- `web/package.json`, `web/.nvmrc` (24) -- `check` = lint, format:check, typecheck, test; `build` = `tsc -b && vite build`.
- `_bmad-output/implementation-artifacts/spec-1-2-web-scaffold.md` -- deferred: web build/check not in CI (closed here).
- Pins: actions/setup-java v6.3.0 `d1374f8ac74474200d16ef8ecee22c7ae7a51880`, actions/setup-node v7.1.0 `949feb2413d6458794dcd2491c4babbbce0c15c1`; gitleaks 8.30.1 linux_x64 sha256 `551f6fc83ea457d62a0d98237cbad105af8d557003051f41f3e7ca7b3f2470eb`; trivy 0.76.2 Linux-64bit sha256 `bc0fc726e017b8927a62b2d3531d7a84ee01769daf5c99ed866dced54089fdbf`.

## Tasks & Acceptance

**Execution:**
- `.github/workflows/ci.yml` -- rename the orchestrator job, add `backend` (setup-java 21, `backend/gradlew -p backend check`), `web` (setup-node from `web/.nvmrc`, `ci`, `check`, `build`), `secret-scan` (canary, then gitleaks over the event's commit range), `vulnerability-scan` (canary, build boot jar, Trivy `fs` over the repo and `rootfs` over the jar), and aggregate `ci` -- AC 1.9.1–1.9.3.

**Acceptance Criteria:**
- AC 1.9.1: Given a PR, when CI runs, then jobs `orchestrator`, `backend` and `web` run the ADR 0014 commands and `ci` fails if any job fails or is skipped.
- AC 1.9.2: Given a commit with a secret in the PR's range, when `secret-scan` runs, then gitleaks exits non-zero and the job fails; the canary step proves this on every run.
- AC 1.9.3: Given a runtime dependency with a HIGH or CRITICAL advisory, when `vulnerability-scan` runs, then Trivy exits non-zero; the canary (lodash 4.17.15 lockfile) proves this on every run.

## Spec Change Log

## Review Triage Log

### 2026-11-18 — Review pass
- verdicts: 30 findings — high 0, medium 13, low 6, false 9, maybe-false 2
- findings (summary; budget-limited run, grouped rows share route):
  - `[medium]` `[patch]` canaries accept exit 1, which scanners also return on fatal errors (edge x2, claim) — canaries now use `--exit-code 3` and require 3; verified locally gl=3, tv=3.
  - `[medium]` `[patch]` unreachable `before` after force-push breaks range (blind, edge) — falls back to `-1 sha` when `git cat-file -e` fails.
  - `[medium]` `[patch]` cancel-in-progress on main skips a push's secret scan (edge) — cancel only on pull_request.
  - `[low]` `[patch]` `always()` posts red ci on superseded runs (edge) — `!cancelled()`.
  - `[medium]` `[defer]` jar not canaried / targets not asserted / empty range (blind x2, verification-gap) — deferred.
  - `[medium]` `[defer]` aggregate ci untested (blind, verification-gap) — deferred.
  - `[medium]` `[defer]` wrapper validation, npm install scripts (blind x2) — deferred.
  - `[medium]` `[defer]` ADR for scanner decisions; epic-context wording; unfixable advisory blocks PRs (blind x3, edge) — deferred.
  - `[low]` `[reject]` `--skip-dirs web/node_modules` no-op, bootJar built twice, `-1` on new ref with several commits (push only on existing main), check list wording — harmless or unreachable.
  - `[maybe-false]` `[reject]` future `.gitleaks.toml` diverging from canary — no config exists; low if true.
  - `[false]` `[reject]` epic-1-context and spec outside touches (blind, intent x2) — workflow artifacts regenerated by build-auto step 1.
  - `[false]` `[reject]` no real CI run, local-only proof, spec narrower than ACs, 1-2 deferral half open, uneven build, scans skip main history/dev deps (intent, blind) — PR run is the end-to-end check; narrowing is recorded in Design Notes; sdlc-commands needs an owner PR.

## Design Notes

Trivy scans runtime dependencies only (its default). With `--include-dev-deps`, `picomatch` 4.0.7 (dev tooling) has an unfixed HIGH advisory, which would block every PR with no possible fix. Backend test-only jars are not in the boot jar and are not scanned.

The canary secret is assembled at runtime from two parts so the workflow file itself does not trip gitleaks.

## Verification

**Commands:**
- `gitleaks git --log-opts=origin/main..HEAD .` -- expected: exit 0 on this branch.
- gitleaks on a temp repo with a planted AWS-style key -- expected: exit 1.
- `trivy fs --scanners vuln --severity HIGH,CRITICAL --exit-code 1 --skip-dirs web/node_modules .` and `trivy rootfs ... backend/build/libs` -- expected: exit 0.
- Trivy on a temp dir with a lodash 4.17.15 lockfile -- expected: exit 1.
- `backend/gradlew -p backend check`, `npm --prefix web ci`, `npm --prefix web run check`, `npm --prefix web run build` -- expected: pass.
- `actionlint .github/workflows/ci.yml` -- expected: no findings.

## Auto Run Result

- Summary: `.github/workflows/ci.yml` now runs jobs `orchestrator`, `backend`, `web`, `secret-scan` (gitleaks 8.30.1, commit range, canary) and `vulnerability-scan` (Trivy 0.76.2, lockfiles + boot jar, HIGH/CRITICAL, canary), aggregated by the required check `ci`.
- Files: `.github/workflows/ci.yml` (CI jobs); `_bmad-output/implementation-artifacts/epic-1-context.md` (recompiled, stale); this spec.
- Review: 4 patches (3 medium, 1 low), 4 deferred groups, rest rejected (see triage log).
- Follow-up review recommended: true — 3 medium patches; the changed canary exit codes and range fallback have not yet run on GitHub Actions.
- Verification: backend check, npm ci/check/build passed; gitleaks range and dir scans clean; canaries exit 3; Trivy fs and rootfs clean; actionlint reported clean by the implementer (not re-run after patches).
- Residual risks: first real run on GitHub; Trivy DB download per run; branch protection still requires only `ci`.
