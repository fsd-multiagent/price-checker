# Deferred work

- source_spec: none
  summary: Real adapters for Zamolxe (Orchestrator) — claude (claude -p invocation and JSON parsing, AD-8), git (worktrees, reset, rebase, push refusal to main, AD-6/AD-12) and gh (PRs, auto-merge, checks, escalation issues, AD-13/AD-20).
  evidence: Split from the orchestrator build intent on 2026-11-16; step 0.8 acceptance (`sdlc run --dry-run`) needs only fake adapters (AD-2), and the real adapters are independently shippable behind the ports.
- source_spec: none
  summary: Run admission for Zamolxe (Orchestrator) — repo lock file, trigger queue, and issue-started `sdlc story|fix <issue>` runs (AD-22).
  evidence: Split from the orchestrator build intent on 2026-11-16; only needed once GitHub Actions triggers exist (step 0.9).
- source_spec: none
  summary: Desktop notifier adapter for escalations (macOS osascript, no-op elsewhere; AD-14).
  evidence: Split from the orchestrator build intent on 2026-11-16; the dry-run uses the fake notifier.
