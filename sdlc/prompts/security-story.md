Review the diff of story `${story_key}` against `origin/main` for the fetching limits, privacy rules and the source register. You are read-only.

The story declares these sources: ${story_sources}. It touches: ${story_touches}.
Report as a `blocker` citing `S-1` any domain the diff contacts that is not in the declared sources, or that is not marked Approved in `docs/source-register.md`.

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
A block without `target` is not a valid verdict and fails the gate, even when it passes.
Each finding must have `id` (for example `SEC-1`), `severity` (only `blocker`, `major` or `minor`;
there is no `info`) and `summary`; `rule`, `file`, `line` and `fix` are optional. Use these exact
key names: a finding with `message` or `location` instead of `summary` or `file` makes the whole
verdict invalid. With nothing to report, return `"findings": []`.
