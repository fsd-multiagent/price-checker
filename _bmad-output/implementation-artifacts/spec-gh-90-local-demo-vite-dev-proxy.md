---
title: 'Local demo: Vite dev proxy for /api and a Run locally guide (issue #90)'
type: 'feature'
created: '2026-11-18'
status: 'done'
baseline_revision: 'fef53d4ba889a7f43582a19a2a9ca4e129640253'
review_loop_iteration: 0
followup_review_recommended: true
context:
  - '{project-root}/web/CLAUDE.md'
warnings: []
deferred:
  - summary: >-
      AGENTS.md Kill switch pitfall and Where things are do not mention the local-run path.
    evidence: |-
      The pitfall still says a local run logs outcome=MISSING; with the README flow the file exists and is accepted. AGENTS.md is an agent-context file, so Aglaia (Knowledge Keeper) updates it at stage 9.
    location: >-
      AGENTS.md
    severity: low
---

<intent-contract>

## Intent

**Problem:** The web app calls `/api/...` on its own origin, but `vite dev` has no proxy, so every lookup in a local run fails; there is also no guide for running the app locally.

**Approach:** Add a dev/preview-only `/api` proxy to `web/vite.config.ts` (target `http://localhost:8080`, overridable by `VITE_API_PROXY_TARGET`), a committed `config/runtime.local.example.yaml` that stops every retailer, and a "Run locally" section in `README.md` with a pointer from `web/CLAUDE.md`.

## Boundaries & Constraints

**Always:** Proxy only under `server.proxy` and `preview.proxy`; keep calls same-origin (`basePath: ''` unchanged). The example runtime file sets `fetching: stopped` for every `config/retailers.yaml` domain and must be accepted by `RuntimeSwitch`. AC ids in test names (T-1). Tests are deterministic (T-5).

**Never:** No backend main-code or production (Caddy, compose, Dockerfile) change. No retailer contacted. No reference to the proxy target or `VITE_API_PROXY_TARGET` from app code bundled by `vite build`. No new dependency.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Default target | `VITE_API_PROXY_TARGET` unset | `/api` proxied to `http://localhost:8080` in dev and preview | No error expected |
| Override | `VITE_API_PROXY_TARGET=http://127.0.0.1:9090` | `/api` proxied to that URL | No error expected |
| Blank override | `VITE_API_PROXY_TARGET=''` or whitespace | Falls back to the default | No error expected |

</intent-contract>

## Code Map

- `web/vite.config.ts` -- current config: `react()` plugin plus Vitest `test` block; becomes `defineConfig(({ mode }) => …)` reading env with `loadEnv(mode, import.meta.dirname, 'VITE_API_PROXY')`.
- `web/src/App.tsx` -- `new LookupApi(new Configuration({ basePath: '' }))`: calls `/api/lookup` same-origin; read-only.
- `web/tsconfig.app.json` -- includes `src`, has `node` + `vite/client` types, so a test in `src` may import `../vite.config`.
- `web/src/scaffold.test.ts` -- pattern for file-reading tests (`resolve(import.meta.dirname, '..')`).
- `config/retailers.yaml` -- domains `evomag.ro`, `cel.ro` (the list the example must mirror).
- `backend/src/main/java/ro/pricechecker/registry/RuntimeSwitch.java` -- `new RuntimeSwitch(Path, Clock)`, `refresh()` returns empty `Optional` when accepted, `isAllowed(domain)`.
- `backend/src/test/java/ro/pricechecker/registry/RuntimeSwitchTest.java`, `MutableClock.java` -- test patterns; backend tests run with cwd `backend/` (see `DeployConfigTest` `Path.of("../deploy/…")`).
- `api/ean-cases.json` -- valid example EAN `5901234123457`.
- `.gitignore` -- `temp/` is ignored; local runtime and blocks copies go there.

## Tasks & Acceptance

**Execution:**
- `web/src/devProxy.ts` -- export `DEFAULT_API_PROXY_TARGET` and pure `apiProxy(env)` returning `{ '/api': { target } }`, blank/missing override falls back -- testable logic, not imported by app code.
- `web/vite.config.ts` -- use `apiProxy(loadEnv(...))` for `server.proxy` and `preview.proxy` -- AC-1, AC-2.
- `web/src/devProxy.test.ts` -- AC-2 tests (default, override, blank) against `apiProxy` and against the exported Vite config for `serve` (dev and preview); AC-5 test: no non-test file in `src` except `devProxy.ts` mentions `localhost:8080` or `VITE_API_PROXY_TARGET` or imports `devProxy`, and the `build` config has no `proxy`.
- `config/runtime.local.example.yaml` -- every `retailers.yaml` domain with `fetching: stopped`, header comment on copying it and allowing an Approved domain (AD-9) -- AC-3.
- `backend/src/test/java/ro/pricechecker/registry/RuntimeLocalExampleTest.java` -- `RuntimeSwitch.refresh()` accepts the file; each `retailers.yaml` domain is listed with `fetching: stopped` and `isAllowed` is false -- AC-3 (test only).
- `README.md` -- "Run locally" section: copy example to `temp/runtime.yaml`, backend command with `PRICE_CHECKER_RUNTIME_FILE`/`PRICE_CHECKER_BLOCKS_FILE`, `npm --prefix web ci` + `npm --prefix web run dev`, URL `http://localhost:5173`, EAN `5901234123457`, expected result -- AC-4.
- `web/CLAUDE.md` -- pointer to the README section and the proxy/env var -- AC-4.

**Acceptance Criteria:**
- Given the backend on `localhost:8080` and `npm --prefix web run dev`, when a lookup is submitted in the browser, then `/api/lookup` reaches the backend through the Vite proxy (AC-1).
- Given no `VITE_API_PROXY_TARGET`, when the dev config is loaded, then `/api` targets `http://localhost:8080`; given an override, then it targets the override (AC-2).
- Given `config/runtime.local.example.yaml`, when `RuntimeSwitch` reads it, then it is accepted and every `retailers.yaml` domain is stopped (AC-3).
- Given `README.md`, when read, then a "Run locally" section has the exact backend and web commands, the URL and an example EAN (AC-4).
- Given `npm --prefix web run build`, when `dist/` is searched, then it contains no `localhost:8080`, no `VITE_API_PROXY_TARGET` and no proxy configuration (AC-5).

## Spec Change Log

## Review Triage Log

### 2026-11-18 — Review pass
- verdicts: 22 findings — high 1, medium 0, low 19, false 2, maybe-false 0
- findings:
  - `[high]` `[patch]` README omits client generation; `dev` has no `predev`, so a fresh clone fails — added `npm --prefix web run generate` to step 3.
  - `[low]` `[patch]` AC-5 import regex misses `.ts`/side-effect/dynamic imports of devProxy — regex now matches any quoted `devProxy(.ts|.js)` specifier.
  - `[low]` `[reject]` `allowImportingTsExtensions` in `tsconfig.app.json` unneeded — needed: the AC-2 test imports `../vite.config`, which pulls `./src/devProxy.ts` into the app project.
  - `[low]` `[reject]` AC-5 never inspects `dist/` — Vite never emits server/preview config; verified by build + grep in Verification; a build-in-test adds cost for no reachable defect.
  - `[false]` `[reject]` `VITE_` prefix exposes target to client — the intent names `VITE_API_PROXY_TARGET`; only `import.meta.env` references inline it and the AC-5 scan forbids them.
  - `[low]` `[patch]` Example file comment "every row is DISABLED" contradicts README empty rows — reworded to "every Approved retailer's row".
  - `[low]` `[reject]` No guidance on example/retailers.yaml coupling — the failing test names the file; adding docs is more than a direct correction.
  - `[low]` `[defer]` AGENTS.md Kill switch pitfall and "Where things are" not updated for the local-run path — agent-context file; Aglaia (Knowledge Keeper) at stage 9.
  - `[low]` `[reject]` README prerequisites wording — cosmetic; commands are exact and were run end to end.
  - `[low]` `[reject]` No `changeOrigin` / no real-request test — localhost target needs none; AC-1 checked manually with curl through the proxy.
  - `[low]` `[patch]` (edge) README missing generate — same root cause as row 1.
  - `[low]` `[patch]` (edge) regex misses `.ts` imports — same root cause as row 2.
  - `[low]` `[patch]` (edge) regex misses side-effect/dynamic imports — same root cause as row 2.
  - `[low]` `[reject]` (edge) scan misses `127.0.0.1:8080` — speculative spelling; adding patterns guards no demonstrated state.
  - `[low]` `[reject]` (edge) malformed override not validated — dev-only; Vite reports the proxy error; adds a branch.
  - `[low]` `[reject]` (edge) `/api` key also matches `/apiary` — no such route exists in the SPA.
  - `[false]` `[reject]` (edge) VITE_ value lands in dist — same refutation as row 5.
  - `[low]` `[reject]` (edge claim) AC-5 not fully enforced by test — covered by rows 2 and 4.
  - `[low]` `[reject]` (verification-gap) example yaml is bundled into the jar under `registry/` — nothing reads it; excluding needs a backend build change the intent forbids.
  - `[low]` `[reject]` (verification-gap) stale comment in `web/e2e/vite.config.ts` — cosmetic, outside touches.
  - `[low]` `[reject]` (intent) AC-1/AC-5 checked at config level, not runtime/artifact — runtime and dist checked manually (curl, grep).
  - `[low]` `[reject]` (intent) files outside touches (backend test, tsconfigs) — test-only and type-config support; recorded as assumptions.

## Design Notes

The issue suggests the `VITE_` prefix. Vite exposes `VITE_*` vars to client code only through `import.meta.env` references, and app code never references this one, so nothing reaches the bundle; the AC-5 test guards that. Under no Approved retailer the lookup returns 200 with no rows; that still proves the proxy path.

## Verification

**Commands:**
- `npm --prefix web run check` -- expected: pass
- `npm --prefix web run build` then search `web/dist` for `localhost:8080` and `VITE_API_PROXY_TARGET` -- expected: no match
- `backend/gradlew -p backend check` -- expected: pass

**Manual checks (if no CLI):**
- Run the README commands; `curl -s 'http://localhost:5173/api/lookup?ean=5901234123457'` returns the backend JSON.

## Auto Run Result

Status: done

**Summary:** `vite dev` and `vite preview` now proxy `/api` to `http://localhost:8080` (override `VITE_API_PROXY_TARGET`, blank falls back); `vite build` gets no proxy. Added an example Kill switch file that stops every retailer and a README "Run locally" guide.

**Files:**
- `web/src/devProxy.ts` -- pure `apiProxy(env)` and `DEFAULT_API_PROXY_TARGET`.
- `web/vite.config.ts` -- config function; proxy only when `command === 'serve'`.
- `web/src/devProxy.test.ts` -- AC-1/AC-2 (function and real config, dev and preview) and AC-5 (build config, source scan).
- `web/tsconfig.app.json`, `web/tsconfig.node.json` -- `allowImportingTsExtensions` for the `.ts` import in the config.
- `config/runtime.local.example.yaml` -- every retailers.yaml domain `fetching: stopped`, AD-9 comment.
- `backend/src/test/java/ro/pricechecker/registry/RuntimeLocalExampleTest.java` -- AC-3: RuntimeSwitch accepts it; domains match retailers.yaml.
- `README.md` -- Run locally section (AC-4). `web/CLAUDE.md` -- pointer.

**Review:** 22 findings; 3 root causes patched (1 high, 2 low), 1 deferred (AGENTS.md, low), the rest rejected with reasons in the triage log.

**Follow-up review:** recommended (a high was patched): the README's fresh-clone flow with the added `generate` step was not re-run end to end after the patch.

**Verification:** `npm --prefix web run check` 152 passed; `npm --prefix web run build` then grep of `web/dist` for `localhost:8080`/`VITE_API_PROXY_TARGET`/`proxy`: no match; `backend/gradlew -p backend check` BUILD SUCCESSFUL; manual curl through the dev proxy returned `{"ean":"5901234123457","rows":[]}` (implementation pass).

**Residual risks:** the README expected output assumes no Approved retailer; the example YAML ships in the jar under `registry/` unused.

**Round 2 (run 20261118-184115-story):** the r1 blockers were the `code_lens` and `security` review steps leaving no valid verdict (AD-10, AD-18), a reviewer-step failure, not a code finding. Code review minors: CR-1 wrapped the long comment line in `config/runtime.local.example.yaml`; CR-3 kept `allowImportingTsExtensions` (dropping the `.ts` extension makes Vite warn about its future native config loader, and `devProxy.test.ts` imports `vite.config.ts` through the app project) and recorded the reason in `web/CLAUDE.md`; CR-2 kept as is (optional; check run is about 3 s). Verified: `npm --prefix web run check` 158 passed, `npm --prefix web run build` ok, `RuntimeLocalExampleTest` passed.
