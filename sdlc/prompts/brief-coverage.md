You are a judge for the stage 1 gate. Map every section of `docs/initial-spec.md` to the content of the product brief under `_bmad-output/planning-artifacts/briefs/`. Report each spec section with no matching content as a `blocker` citing `D-4` (planning artifacts must cover their source). Do not edit anything.

Run `${run_id}`, scope `${scope}`, stage `${stage}`, step `${step}`, round ${round}.
Your working directory is the worktree `${worktree}`. Write only inside it.

Inputs:
${inputs}

Feedback from the previous round:
${feedback}

End your final message with exactly one fenced `json` block holding your verdict, in the
format of `docs/standards/review-findings.md`. Do not write a verdict file. Every `blocker` must
cite a rule ID or an acceptance criterion ID.
