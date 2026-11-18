<!-- bmad:context -->
<!-- Verified 2026-11-16 against a6764a2. Managed by bmad-project-context; edits inside this block are replaced on refresh. Keep anything you want preserved outside the markers. -->

## price-checker

A learning project for an automated multi-agent SDLC built on BMAD; the app (enter an EAN, compare prices across approved Romanian retailers, React + Java on one Azure VM) is the pretext. When a product question is ambiguous, choose the simplest option that keeps the demo working. The plan is `docs/automation-plan.md`; the spec is `docs/initial-spec.md`; BMAD outputs go to `_bmad-output/`.

## Policy

- Follow the rules in `docs/standards/` and cite their IDs (C-, T-, S-, I-, D-) in findings and commits.
- Never approve a retailer, run `terraform apply`/`destroy`, push or merge to `main`, or open SSH to the VM. The guard hook blocks these; escalate to the owner instead.
- Never contact a retailer domain unless `docs/source-register.md` marks it `Approved`. To propose one, set its Status to `Proposed`.
- Never edit `.claude/settings*.json`, `.claude/hooks/` or `docs/source-register.md` Status cells to get past a block; ask the owner.
- Never read `.env*`, `*.tfstate*`, `*.tfvars`, `~/.ssh` or `~/.azure`.
- Every architecture decision gets an ADR from `docs/adr/0000-template.md`, added to the index in `docs/adr/README.md` (D-2).

## Where things are

- Standards and who verifies each: `docs/standards/README.md`
- Review verdict JSON format: `docs/standards/review-findings.md`
- Custom agents: `.claude/agents/`; BMAD agent overrides: `_bmad/custom/*.toml`
- Guardrails: `.claude/settings.json`, `.claude/hooks/guard.py` (rules G1–G5 in `docs/automation-plan.md` §5)
- Registers: `docs/source-register.md`, `docs/compliance-register.md`
- API contract: `api/openapi.yaml` (OpenAPI 3.0.3: `GET /api/lookup`, `GET /api/status`, 400 as `application/problem+json` with an `EAN_*` code; ADR 0007) and `api/ean-cases.json` (shared EAN cases that the backend and web validators both test against). Change the contract first, then regenerate (C-3). OpenAPI Generator 7.27.0 generates Spring interfaces into `backend/build/generated/openapi` (package `ro.pricechecker.api.generated`, run by `compileJava`) and the web client into `web/src/generated/`. Neither is committed; never edit generated code.
- Web app: `web/` (React + TypeScript + Vite; commands and conventions in `web/CLAUDE.md`; design tokens only in `web/src/styles/tokens.css`)
- Retailer config: `config/retailers.yaml` and `config/known-eans.yaml` (ADR 0009, ADR 0010). The backend build bundles them and `docs/source-register.md` into the jar under `registry/`; `ro.pricechecker.registry.Registry` loads them at start and refuses to start when `ConfigCheck` reports a violation. The rules are in the file headers.
- CI: `.github/workflows/ci.yml` runs jobs `orchestrator`, `backend`, `web` (`check` and `build`), `secret-scan` (gitleaks over the PR's or push's commit range) and `vulnerability-scan` (Trivy, runtime dependencies in lockfiles and the backend boot jar, HIGH and CRITICAL fail). The aggregate job `ci` is the one required check and fails when any job fails or is skipped (ADR 0024).
- Infrastructure: `infra/` (Terraform for the `prod` VM; commands and pitfalls in `infra/CLAUDE.md`, owner bootstrap and apply in `infra/README.md`); PR plan workflow `.github/workflows/terraform-plan.yml`

## Running and verifying

- Guard tests: `python3 .claude/hooks/test_guard.py`; run them after any change to `guard.py`.
- After every BMAD install or update, run `python3 scripts/apply-persona-names.py` (`--check` reports only); the update restores the default persona names.
- Zamolxe (Orchestrator), in `orchestrator/` (Python ≥ 3.12, uv):
  - Tests: `cd orchestrator && uv run pytest -q`
  - Lint and format: `cd orchestrator && uv run ruff check && uv run ruff format --check`
  - Dry-run of the whole stage table with fake adapters: `cd orchestrator && uv run sdlc run --dry-run` (exit 0; prints every `claude -p` it would start and a temporary run dir)
  - Real run: `cd orchestrator && uv run sdlc run` runs real `claude -p`, `git` and `gh` (paid). A preflight first checks that `claude`, `git` and `gh` are on PATH, `gh auth status` passes, `origin` exists and the main checkout is clean; it exits 1 naming what is missing. State goes to `_bmad-output/sdlc/runs`; worktrees go to `../price-checker-worktrees/`.
  - Real runs bill the Claude subscription: the preflight also exits 1 when `ANTHROPIC_API_KEY` or another API-key or provider credential is set (environment, or `env`/`apiKeyHelper` in a Claude settings file) unless `allow_api_key: true` (AD-23); keep "extra usage" credits off so a plan limit pauses the run instead of buying credits.
  - Settings in `sdlc/pipeline.yaml` for subscription runs: `default_model`, `default_effort` (per stage: `model`, `effort`, `max_turns`), `usage_limit_wait_min`, `usage_limit_max_wait_h`, `allow_api_key`. A plan usage limit pauses the run; while paused, `sdlc status` prints `plan usage limit: resume_at …` (also after an episode escalated the run).
  - Dry-run limit scenarios: `uv run sdlc run --dry-run --scenario usage-limit` (one pause, then exit 0) and `--scenario usage-limit-forever` (escalates with scope `run`, exit 2).
  - `uv run sdlc status [<run-id>]` and `uv run sdlc resume <run-id>`; pass `--runs-dir <dir>` for a dry-run's temporary dir.
  - `uv run sdlc usage [<run-id> | --all] [--by agent|stage|model|round|run] [--rebuild]` reports token use and cost from each run's `state.json` (read-only). The run itself appends to the ledger `<runs-dir>/usage.jsonl`; `--rebuild` regenerates it and refuses while a run is running (AD-24).
  - Usage settings in `sdlc/pipeline.yaml`: `agents` (role -> skills and agents it may run; every new agent or judge step needs an `agent_role` from it, and `Judge (gate check)` is the pseudo-role of gate judges), `usage_warn_ratio` (one warning at 80% of the run budget), `telemetry` (off; when on, OTLP metrics only to an http(s) endpoint, labelled `sdlc.agent` etc., with content logging always stripped, AD-25), and `learn_revert_paths` (learn branches revert `sdlc/**`, so pipeline changes go through an owner PR). Before an epic retrospective the orchestrator writes `<worktree>/.sdlc/usage-summary.md`.
  - Path tiers in `sdlc/pipeline.yaml` (AD-26, ADR 0022), each entry a path prefix or an exact file:
    - `owner_only_paths` (`.claude/`, `.github/CODEOWNERS`, `docs/source-register.md`): reverted before every orchestrator commit and never proposed. A reviewer that changes one fails.
    - `protected_paths` (`sdlc/`, `orchestrator/`, `_bmad/custom/`, `docs/standards/`, and the `sdlc-commands` block below): an agent's edit is captured as a proposal, then reverted on its branch.
    - `review_paths` (`.github/workflows/`): never pushed from a proposal. On a learn branch an edit there becomes an issue.
    - A proposal becomes a PR on `proposal/<sha8>` cut from `origin/main`, labelled `owner-review`. When it touches `review_paths` or does not apply cleanly, it becomes an `owner-review` issue with the patch. Zamolxe (Orchestrator) never merges or auto-merges an `owner-review` PR; only the owner does. `sdlc status` lists them as `owner review: PR #n`; the saved search is `is:open label:owner-review`.
  - Issue runs (AD-27): `uv run sdlc story <issue>` and `uv run sdlc fix <issue>` (add `--dry-run` for fakes; issue 12 in `orchestrator/tests/fixtures/dryrun/issues/` is a factory story). The issue must be open, and its author, its last editor, whoever renamed it and whoever applied a `factory` label must be in `settings.owners`, or the command comments why and exits 1. A second run for an issue whose earlier run has not passed or escalated is refused. The issue holds one fenced `yaml` story block (`depends_on`, `touches`, `sources`, optional `acceptance`; `key` is derived as `0-<issue>-<slug>`; `touches`, `sources` and `depends_on` must be plain paths, domains and keys); a bad block escalates the run at once. Issue text reaches prompts only in the fenced "Untrusted issue text" section, and `sdlc resume` escalates the story if the issue changed.
  - A factory story keeps its edits to `protected_paths` and `review_paths` (owner-only paths and bookkeeping are still reverted; in `AGENTS.md` only the `sdlc-commands` block change is kept), and its tests gate runs only the branch point's block (the new block never runs before the owner merges it). A PR touching `protected_paths` in a factory story, `review_paths` in any run, or (any story) the `sdlc-commands` block, is created labelled `owner-review` and waits: the run goes on with independent work and ends `awaiting_owner` (exit 2). After preflight, the next `run`, `resume`, `story` or `fix` checks the PRs of `awaiting_owner` runs: merged (with the head that was opened for review) queues `sdlc resume <run>` ahead of the new work, once; a failed automatic resume is shown by `sdlc status` and not queued again. Closed, or merged with another head, escalates the story. On a dry-run, simulate the owner with `{"pr_states": {"<pr>": "MERGED"}}` in `<runs-dir>/fake-gh.json`.
  - One run at a time: a real `run`, `resume`, `story` or `fix` holds `fcntl.flock` on `_bmad-output/sdlc/lock`, whatever `--runs-dir` says (a dry-run locks beside its own runs dir). A second one is appended to `_bmad-output/sdlc/queue.jsonl` (never with `--repo` or `--pipeline`) and exits 0; when a command completes normally (not after Ctrl-C or a crash), the next queued one replaces the process (`os.execv`), keeping the lock, and its outcome goes to `_bmad-output/sdlc/queue-history.jsonl`. Exit codes: 0 passed or queued; 1 internal error, failed preflight or refused issue; 2 escalated, blocked or `awaiting_owner`.
  - Robust git (AD-27): `git fetch` retries ref-lock errors with backoff, and a fetch that still fails only fails the script step that needed it; `sdlc resume` removes `*.lock` files no process holds from each run worktree's git dir; learn branches rebase onto `origin/main` before their PR.
  - Opt-in live test of one real `claude -p` call (about $0.05): `cd orchestrator && SDLC_LIVE=1 uv run pytest -q -m live`.
  - The stage table is `sdlc/pipeline.yaml`; prompts are `sdlc/prompts/*.md`; dry-run fixtures are `orchestrator/tests/fixtures/dryrun/`.
- Commands the orchestrator's `tests` and `terraform_plan` gates run, from the worktree root (T-8). Each is split like a shell word list and run without a shell, so no `cd`, `&&`, pipes or redirections, and nothing the guard hook denies; a missing name fails the gate. The gate reads this block from `origin/main` at the story's branch point and fails when the story itself changed it (ADR 0023), and before each of its commits on a planning, infra or learn branch the orchestrator puts the block back as it is on `origin/main` (story branches revert all of `AGENTS.md`), so agents cannot change it through their auto-merged PRs (ADR 0004, ADR 0021).

  ```yaml sdlc-commands
  tests:
    - uv run --directory orchestrator pytest -q
    - backend/gradlew -p backend check
    - npm --prefix web ci
    - npm --prefix web run check
  terraform_plan:
    - terraform -chdir=infra fmt -check -recursive
    - terraform -chdir=infra init -backend=false -input=false
    - terraform -chdir=infra validate
    - terraform -chdir=infra test
  ```

  - `backend/gradlew -p backend check` runs the backend's format check (Spotless), compile, tests and architecture rules (ArchUnit) (ADR 0014). It needs a JDK on the runner; Gradle provisions JDK 21 through the toolchain.
  - The `terraform_plan` gate runs offline: formatting, validity, and `infra/tests/*.tftest.hcl` against a mocked provider. No Azure access is needed. The real `terraform plan` runs in `.github/workflows/terraform-plan.yml` once the owner sets the `AZURE_*` and `TF_STATE_*` repository variables.

- `npm --prefix web ci` installs the locked web dependencies and `npm --prefix web run check` runs ESLint, Prettier, the TypeScript check and Vitest (ADR 0013, ADR 0014). They need Node 24 on the runner (`web/package.json` `engines`).

## Conventions that differ from defaults

- Refer to agents as Name (Position), for example "Marghioala (Developer)" (D-6). Names: Pompiliu (Analyst), Onofrei (PM), Safta (UX Designer), Veronel (Security & Compliance), Loredan (Architect), Pacala (DevOps), Marghioala (Developer), Eufrosina (QA), Filofteia (Code Reviewer), Aglaia (Knowledge Keeper), Zamolxe (Orchestrator).

## Known pitfalls

- The guard reads every Bash command as text, heredoc bodies included. Inline code (`python3 -c`, `node -e`) that mentions a retailer domain, or a heredoc whose text contains a blocked command such as `terraform apply`, is denied even though nothing would run. Write the content with the Write tool, or put code in a script file and run that.
- When the owner approves a retailer in `docs/source-register.md`, the backend tests and startup fail until `config/retailers.yaml` has an entry for it and `config/known-eans.yaml` has at least two distinct valid EANs for it. Approval takes effect only after a rebuild and deploy, because the register is bundled into the jar.
- `ro.pricechecker.lookup.AdapterCatalog` refuses to start unless the `RetailerAdapter` bean ids and the `config/retailers.yaml` ids match one-to-one (AD-7). Main code has no adapter beans until Epic 2, so the packaged backend does not start yet (story 1.6 deferral); a new `retailers.yaml` entry needs its adapter bean in the same PR. Spring tests start only because the test-only `lookup/StubAdapters` is found by component scan and registers one no-op adapter per id. When the first real adapter lands, make `StubAdapters` opt-in (`@Import`), or every test context reports duplicate ids.
- `LookupService` catches any `Exception` from an adapter per row and returns `UNAVAILABLE`, so a Lookup stays 200 (AD-5). Without a `RetailerHttp` bean (none until the Epic 2 `fetch` client), every enabled retailer is `UNAVAILABLE`. `retailerName` is the register domain until `retailers.yaml` gets a `name` field. Jackson `default-property-inclusion: non_null` in `application.yaml` keeps non-FOUND rows to their four fields; do not remove it.
- The Kill switch fails closed (AD-9, story 2.3): `registry.RuntimeSwitch` reads `runtime.yaml` from property `price-checker.runtime-file` (env `PRICE_CHECKER_RUNTIME_FILE`, default `/opt/price-checker/config/runtime.yaml`, wired by `ro.pricechecker.RuntimeSwitchConfig` because `registry` reads no property, AC 1.5.4). No such file exists outside the VM until stories 3.2 and 3.4, so a local run shows every row `DISABLED` and logs `event=runtime_config_rejected`, `outcome=MISSING` on the first Lookup after each 15 s. Point the env var at a file with `retailers: { <domain>: { fetching: allowed } }` to fetch locally. There is no scheduler: `LookupService` calls `refresh()` once per Lookup and the file is re-read only when 15 s passed on the injected `Clock`. A stopped retailer is `DISABLED` before the known-EAN check and any adapter call. Every retailer entry needs `fetching: allowed|stopped`; an entry without it rejects the whole file and the last good settings stay.
- CI `secret-scan` checks every commit in the PR, so a secret-like string (for example an AWS-style key in a test fixture) fails it even if a later commit removes it. There is no allowlist; build such strings at runtime from parts, as the canary in `.github/workflows/ci.yml` does.
- Backend main code must not contain retailer domain or selector-like string literals (for example a literal ending in `.yaml`); `backend/src/test/java/ro/pricechecker/arch/RetailerTextScan.java` fails the build. Tests use `*.example` domains. Schema.org values count too: match an availability URL by its text after the last `/`, never as a literal (story 2.1).
- `npm install` on macOS can drop the optional `node_modules/@emnapi/core` and `@emnapi/runtime` entries from `web/package-lock.json`. Local `npm ci` still passes, but CI `web` fails at `npm ci` with `EUSAGE` (story 1.3). After a dependency change, check that both entries are still in the lockfile, or restore them from `origin/main`.
- Spring Boot 4 moved `@WebMvcTest` to the test dependency `spring-boot-starter-webmvc-test` (BOM-managed, already in `backend/build.gradle.kts`); the old `spring-boot-starter-test` import alone does not find it (story 1.4).
- Application events go only through `ro.pricechecker.lookup.LookupLog` (ADR 0017): its fields come from a private enum, so a new field needs an ADR 0017 change first. Never log the raw EAN input, an exception message, an IP or a user agent; a rejection puts the `EAN_*` code in `outcome`. Spring's own text logs still share stdout with these JSON lines (story 1.4 deferral), so do not parse stdout as pure JSON lines.
- `ro.pricechecker.extract` takes HTML as a `String` and parses it only with `Jsoup.parse(String)`; ArchUnit `extractHasNoNetworkAccess` fails the build on `Jsoup.connect`, `java.net` (except `URI`), `java.nio.file`, `java.nio.channels` or `java.io.File*` there. A helper returns `Optional.empty()` only when the page has no Product or product meta tags; a present result can still have every fact `null`, so adapters check the fields before falling back to the next C-6 step.

<!-- /bmad:context -->
