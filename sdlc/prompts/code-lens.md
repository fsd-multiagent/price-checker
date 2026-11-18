Review the diff of story `${story_key}` against `origin/main` for the project conventions in `docs/standards/`. You are read-only.

${issue_text}

Run `${run_id}`, scope `${scope}`, stage `${stage}`, step `${step}`, round ${round}.
Your working directory is the worktree `${worktree}`. Write only inside it.

Inputs:
${inputs}

Feedback from the previous round:
${feedback}

End your final message with exactly one fenced `json` block holding your verdict, in the
format of `docs/standards/review-findings.md`. Do not write a verdict file. Every `blocker` must
cite a rule ID or an acceptance criterion ID. The block must have all four top-level keys
`reviewer`, `target`, `verdict` and `findings`; set `target` to `story/${story_key} @ <commit>`.
Each finding must have `id` (for example `CL-1`), `severity` (only `blocker`, `major` or `minor`)
and `summary`; `rule`, `file`, `line` and `fix` are optional. Use these exact key names: a finding
with `message` or `location` instead of `summary` or `file` makes the whole verdict invalid, even
when it passes. With nothing to report, return `"findings": []`.
