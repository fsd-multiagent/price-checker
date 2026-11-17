/bmad-prd -H Validate the PRD under `_bmad-output/planning-artifacts/prds/`. Do not change it; report each problem as a finding.

Run `${run_id}`, scope `${scope}`, stage `${stage}`, step `${step}`, round ${round}.
Your working directory is the worktree `${worktree}`. Write only inside it.

Inputs:
${inputs}

Feedback from the previous round:
${feedback}

End your final message with exactly one fenced `json` block holding your verdict, in the
format of `docs/standards/review-findings.md`. Do not write a verdict file. Every `blocker` must
cite a rule ID or an acceptance criterion ID.
