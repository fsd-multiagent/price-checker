---
title: 'Story 3.2: Container images, Compose and Caddy'
type: 'feature'
created: '2026-11-18'
status: 'done'
baseline_revision: 'c2c066f83ede02247b8dbe618d437189b50dbf5a'
review_loop_iteration: 0
followup_review_recommended: false
context: []
warnings: []
deferred:
  - summary: >-
      No check builds the images, parses compose or runs caddy validate on the Caddyfiles.
    evidence: |-
      No Docker daemon or caddy binary on the dev host and no CI job builds images; a Caddyfile
      syntax error, a multi-jar COPY or a missing chown path would surface only at deploy.
      Story 3.3's workflow should run docker build for both images, docker compose config with
      dummy IMAGE_TAG/SITE_ADDRESS, and caddy validate on both Caddyfiles.
    location: >-
      backend/Dockerfile, web/Dockerfile, deploy/Caddyfile, web/Caddyfile
    severity: medium
  - summary: >-
      Host /opt/price-checker/state must exist and be writable by BACKEND_UID before the backend starts.
    evidence: |-
      cloud-init creates only /opt/price-checker/config (deploy:deploy 0750); a failed blocks.json
      write is not logged (story 2.6 deferral), so Blocks would live in memory only. The story 3.3
      deploy script must create the state dir and pass BACKEND_UID/BACKEND_GID (deploy user's ids).
    location: >-
      deploy/compose.yaml backend.user and volumes
    severity: medium
  - summary: >-
      AGENTS.md has no entry for deploy/, the root build context, IMAGE_TAG/SITE_ADDRESS or the new test inputs.
    evidence: |-
      Agent-context files are updated by Aglaia (Knowledge Keeper) at stage 9.
    location: >-
      AGENTS.md
    severity: low
  - summary: >-
      Caddy loggers other than the access log may carry a client IP in the message text, which the request> field filter does not touch.
    evidence: |-
      Reviewers named the http.stdlib "TLS handshake error from <ip>:<port>" line. Whether Caddy 2.10.2 emits it
      at the default INFO level (or only at DEBUG) was not checked. To settle it, run the proxy image, make a failed
      TLS handshake and read the logs. If it shows up, add `exclude http.stdlib` (or a level) to the global log and
      check it with caddy validate (NFR-7, AD-14).
    location: >-
      deploy/Caddyfile global log, web/Caddyfile global log
    severity: medium (unverified)
---

<intent-contract>

## Intent

**Problem:** Nothing packages the backend and the SPA for the VM, and nothing serves them over HTTPS from one origin without logging client IPs (NFR-5, NFR-7, AD-14, AD-15).

**Approach:** Add a multi-stage `backend/Dockerfile` and `web/Dockerfile` (build context = repo root, base images pinned by exact tag), a `deploy/compose.yaml` with `proxy` (Caddy 2), `web` and one `backend`, a `deploy/Caddyfile` with automatic HTTPS and a privacy log filter, and a JUnit test that inspects these files as text/YAML.

## Boundaries & Constraints

**Always:** base images pinned by exact version tag (S-12); `runtime.yaml` and `blocks.json` reach the backend through host bind mounts of `/opt/price-checker/config` (read-only) and `/opt/price-checker/state`; Caddy has no `log` directive in a site block (no access log) and the global default logger deletes `remote_ip`, `client_ip`, headers and URI fields (AD-14); `/api/*` → `backend:8080`, everything else → `web:8080`; port 80 redirects via Caddy automatic HTTPS; containers run as non-root; a root `.dockerignore` keeps `.env*`, `*.tfstate*`, `*.tfvars`, `.git`, `node_modules` and build outputs out of the build context (S-10).

**Never:** no deploy workflow or VM scripts (story 3.3); no `runtime.yaml` writer (story 3.4); no published ports on `web` or `backend`; no `auto_https off` or `disable_redirects`; no secrets in images or compose; no edit of `sdlc-commands`.

</intent-contract>

## Code Map

- `backend/build.gradle.kts:90-101` -- `processResources` reads `../docs/source-register.md` and `../config/*.yaml`; `openApiGenerate` reads `../api/openapi.yaml`, so the backend image builds from the repo root. Test task config at `tasks.withType<Test>`.
- `backend/src/main/resources/application.yaml` -- runtime-file `/opt/price-checker/config/runtime.yaml`, blocks-file `/opt/price-checker/state/blocks.json`; Tomcat access log already off.
- `backend/src/test/java/ro/pricechecker/BuildPinningTest.java` -- pattern for text tests reading `../` paths (working dir is `backend/`).
- `web/package.json` -- `prebuild` runs the OpenAPI generator (needs Java); `web/openapitools.json` pins 7.27.0. Build output `web/dist/`.
- `infra/cloud-init.yaml.tftpl:97-98` -- creates `/opt/price-checker/config` (deploy:deploy 0750); no state dir yet (story 3.3).
- `docs/standards/README.md` -- I-* rules for infra.

## Tasks & Acceptance

**Execution:**
- `.dockerignore` -- exclude secrets, VCS, deps and build outputs -- S-10.
- `backend/Dockerfile` -- `eclipse-temurin` JDK stage runs `backend/gradlew -p backend bootJar`; JRE stage runs the jar as uid 10001 on 8080 -- AC 3.2.1.
- `web/Dockerfile` -- `openapitools/openapi-generator-cli:v7.27.0` stage generates `web/src/generated`; `node` stage runs `npm ci` and `npm run build --ignore-scripts`; `caddy` stage serves `dist` on 8080 with `web/Caddyfile` -- AC 3.2.1.
- `web/Caddyfile` -- `:8080` file server with SPA fallback and the same privacy log filter -- AD-14.
- `deploy/compose.yaml` -- services `proxy`, `web`, `backend` (fixed `container_name`, no replicas), bind mounts, named volumes `caddy_data`/`caddy_config`, images `ghcr.io/fsd-multiagent/price-checker-{backend,web}:${IMAGE_TAG}` -- AC 3.2.1.
- `deploy/Caddyfile` -- global log filter, `{$SITE_ADDRESS}` site with `handle /api/*` and fallback `handle` -- AC 3.2.2, 3.2.3.
- `backend/src/test/java/ro/pricechecker/DeployConfigTest.java` -- tests tagged AC-3.2.1..3 -- T-1.
- `backend/build.gradle.kts` -- declare `../deploy`, the Dockerfiles and `web/Caddyfile` as test inputs so Gradle re-runs the test on change.

**Acceptance Criteria:**
- Given `deploy/compose.yaml`, when parsed, then it has exactly services `proxy` (Caddy 2 image pinned by exact tag), `web` and one `backend` (fixed container name, no replicas/scale), and the backend mounts `/opt/price-checker/config` and `/opt/price-checker/state` from the host; every `FROM` in both Dockerfiles is an exact tag (3.2.1).
- Given `deploy/Caddyfile`, when read, then the site address is not `http://`, auto HTTPS redirects are not disabled, `/api/*` proxies to `backend:8080` and the rest to `web:8080` (3.2.2).
- Given both Caddyfiles, when read, then no site block has a `log` directive and the global log filter deletes `request>remote_ip`, `request>client_ip`, `request>headers`, `request>uri` (3.2.3).

## Spec Change Log

## Review Triage Log

### 2026-11-18 — Review pass
- verdicts: 28 findings — high 0, medium 7, low 15, false 1, maybe-false 0, plus 3 descriptive intent-alignment observations logged as reject (counted in low)
- findings:
  - Blind Hunter:
    - `[medium]` `[patch]` `.dockerignore` excluded `.env`/tfvars/tfstate only at the root -- patterns made `**/`; S-10 test checks exact lines.
    - `[medium]` `[patch]` EXACT_TAG accepted floating `caddy:2` -- regex now needs `x.y.z` or `@sha256:` digest.
    - `[low]` `[patch]` non-root check matched USER in any stage -- test now checks the last USER is non-zero.
    - `[medium]` `[defer]` state dir ownership for uid 10001 -- belongs to story 3.3 deploy script.
    - `[medium]` `[defer]` nothing builds the images -- story 3.3 workflow.
    - `[low]` `[reject]` no test that caddy_data/config volumes persist -- present in compose; extra guard not worth it.
    - `[low]` `[reject]` no healthchecks / ready ordering -- not in ACs; 502 window during deploy is story 3.3's concern.
    - `[false]` `[reject]` Docker logs uncapped -- cloud-init writes `/etc/docker/daemon.json` (ADR 0020) before the daemon starts.
    - `[low]` `[reject]` no HSTS/security headers, proxy admin API on -- not in ACs; admin listens on localhost inside the container.
    - `[low]` `[patch]` `request>remote_port` not deleted -- added to both Caddyfiles and the test.
    - `[low]` `[reject]` contact URL / JVM memory not set in compose; mount defaults untied -- defaults are the intended values; mount tie patched below.
    - `[low]` `[defer]` docs/AGENTS.md lack deploy notes -- agent-context file, stage 9.
  - Edge Case Hunter:
    - `[medium]` `[defer]` state dir not writable -- same root cause as above.
    - `[medium]` `[patch]` floating tags pass -- same patch as above.
    - `[low]` `[patch]` USER only in a build stage passes -- same patch as above.
    - `[low]` `[reject]` lowercase FROM / `--platform` -- not used; adds parsing complexity.
    - `[low]` `[reject]` brace scan breaks on comments/other placeholders -- none present.
    - `[low]` `[patch]` IMAGE_TAG `contains()` too loose -- now a full-match regex.
    - `[low]` `[reject]` `/api` without slash goes to web -- the API has no `/api` route.
    - `[low]` `[reject]` stale hashed asset returns index.html -- SPA fallback is standard; rare.
    - `[low]` `[reject]` proxy runs as root -- stock Caddy image binding 80/443; hardening is outside the ACs.
  - Verification Gap:
    - `[medium]` `[defer]` no image build / caddy validate -- same as Blind Hunter deferral.
    - `[medium]` `[patch]` generator args duplicated between web/Dockerfile and package.json -- new test `ac_3_2_1_webImageGeneratesTheClientLikeTheNpmScript` ties version and args.
    - `[medium]` `[patch]` mount paths not tied to application.yaml -- mount test now reads `runtime-file`/`blocks-file` parents.
    - `[low]` `[reject]` filter fields checked as text, a comment would pass -- unlikely edit; a real parse is the deferred caddy validate.
    - `[low]` `[patch]` `.dockerignore` substring check -- same patch as the first row.
  - Intent Alignment (descriptive):
    - `[low]` `[reject]` ACs 3.2.1/3.2.2 are runtime outcomes, tests are textual -- covered by the image-build deferral.
    - `[low]` `[patch]` test rejected digest pins that the AC allows -- regex now accepts `@sha256:`.
    - `[low]` `[reject]` extra files beyond `touches` (`.dockerignore`, `web/Caddyfile`, test, gradle inputs) -- needed for S-10, AD-14 and T-1.

### 2026-11-18 — Review pass (follow-up, round 2)
- Context: round 1 failed only because Veronel (Security & Compliance) left no valid verdict, a reviewer step error. Both code reviews passed. Before this pass, code_review CR-2 was patched: the global `servers { protocols h1 h2 }` turns HTTP/3 off, because only TCP 443 is published.
- verdicts: 36 findings — high 0, medium 4, low 26, false 1, maybe-false 5
- findings:
  - Blind Hunter:
    - `[low]` `[reject]` spec says 9 tests, there are 14 -- the fix edits the spec; the count is corrected in Auto Run Result below.
    - `[low]` `[reject]` filter check is not tied to the global block, so a commented line would pass -- carried: same claim rejected in round 1; caddy validate is deferred.
    - `[maybe-false]` `[defer]` http.stdlib/TLS handshake messages may carry a client IP -- deferred, medium (unverified); needs a run of the proxy image.
    - `[low]` `[patch]` non-root check accepted `USER 10001:0` -- the regex now requires a non-zero gid.
    - `[low]` `[reject]` BACKEND_UID=0 override runs as root -- the operator sets the value in story 3.3's deploy step; a guard adds complexity.
    - `[false]` `[reject]` web has no compose `user:` -- the image's final `USER 10001:10001` applies and is tested.
    - `[low]` `[patch]` `.dockerignore` had no key/cert patterns -- added `**/*.pem`, `**/*.key`, `**/*.p12`, `**/*.pfx`, `**/.claude/settings.local.json`, and the S-10 test checks them.
    - `[low]` `[reject]` no Cache-Control for index.html -- carried, same root cause as the stale-asset reject in round 1.
    - `[low]` `[reject]` image has no git revision label and the user agent reads 0.0.1 -- not in the intent; the deploy tags images by SHA (story 3.3).
    - `[low]` `[reject]` the Gradle layer is not cached -- build-time cost only, not a defect.
    - `[low]` `[reject]` `*.jar` COPY breaks if a plain jar appears -- only `bootJar` runs; the deferred image build would catch it.
    - `[low]` `[reject]` no check for SERVER_PORT in compose; NPE on a missing `server` key -- unlikely edit; the test still fails loudly.
  - Edge Case Hunter:
    - `[maybe-false]` `[defer]` host dirs absent at first `compose up` become root-owned -- carried: the state dir deferral.
    - `[maybe-false]` `[defer]` UID override or wrong owner makes blocks.json writes fail -- carried: same deferral.
    - `[low]` `[reject]` no healthchecks, 502 during deploy -- carried.
    - `[low]` `[reject]` SITE_ADDRESS could be set to http:// or an IP -- the deploy step sets it (story 3.3); the compose check requires it to be set.
    - `[maybe-false]` `[defer]` other loggers carry the IP in msg -- same as the Blind Hunter deferral.
    - `[low]` `[reject]` `/api` without a slash goes to web -- carried.
    - `[low]` `[reject]` a missing hashed asset returns index.html -- carried.
    - `[low]` `[reject]` brace scan breaks on other placeholders or comments -- carried.
    - `[low]` `[reject]` FROM in lowercase or with `--platform` -- carried.
    - `[low]` `[patch]` `USER 10001:0` passes -- same patch as above.
    - `[maybe-false]` `[defer]` the AC 3.2.3 test does not prove no IPs in all logs -- same as the logger deferral.
  - Verification Gap:
    - `[medium]` `[patch]` HTTP/3 opt-out had no check -- added `ac_3_2_2_http3IsOffWhileNoUdpPortIsPublished`.
    - `[medium]` `[defer]` no image build, compose config or caddy validate -- carried: story 3.3 deferral.
    - `[low]` `[reject]` `--ignore-scripts` is not asserted -- covered by the deferred image build.
    - `[low]` `[reject]` uncommitted edits and the stale test count -- committed in this pass; the count is fixed below.
  - Intent Alignment (descriptive):
    - `[low]` `[reject]` packaging, serving and redirect are runtime outcomes, tested as text -- carried: the image-build deferral.
    - `[medium]` `[defer]` C-broad privacy (other loggers) is not covered -- same as the logger deferral.
    - `[low]` `[reject]` proxy runs as root (D-broad) -- carried.
    - `[low]` `[reject]` `.env*` names beyond `.env.` (for example `.envrc`) are not excluded -- the repo has no such files; the S-10 globs cover `.env` and `.env.*`.
    - `[low]` `[reject]` SITE_ADDRESS value is not checked -- same as the Edge Case reject.
    - `[low]` `[reject]` no-secrets rule has no test -- holds by inspection; the COPY lines take only api/, config/, the register, backend/ and web/.
    - `[low]` `[reject]` backend mount writability is untested -- carried: the state dir deferral.
    - `[medium]` `[reject]` extra files and the HTTP/3 opt-out go beyond the named touches -- needed for S-10, AD-14, T-1 and code_review CR-2.
    - `[low]` `[reject]` `web` also has a fixed container_name -- harmless; one replica.

## Design Notes

The backend runs as `${BACKEND_UID:-10001}:${BACKEND_GID:-10001}` so story 3.3's deploy script can pass the `deploy` user's ids (the config dir is `deploy:deploy 0750`) and create `/opt/price-checker/state` writable by them. The web image reuses Caddy so one pinned server (with the same log filter) serves the SPA instead of nginx, whose default access log holds IPs.

## Verification

**Commands:**
- `backend/gradlew -p backend check` -- expected: pass, including `DeployConfigTest`.

**Manual checks (if no CLI):**
- No Docker daemon or `caddy` binary on the dev host: image builds and `caddy validate` are first exercised by story 3.3's workflow.

## Auto Run Result

Status: done

**Summary:** Multi-stage images for backend (Temurin 21.0.8_9 JDK build, JRE run as uid 10001) and web (OpenAPI Generator v7.27.0 stage, Node 24.11.1 build, Caddy 2.10.2 serving `dist` on 8080), `deploy/compose.yaml` with `proxy`, `web` and one fixed-name `backend` bind-mounting `/opt/price-checker/config` (ro) and `/opt/price-checker/state`, and `deploy/Caddyfile` with automatic HTTPS, `/api/*` → `backend:8080`, rest → `web:8080`, no access log and a global filter deleting `remote_ip`, `remote_port`, `client_ip`, headers and URI.

**Files:**
- `.dockerignore` -- keeps secrets, VCS, deps and build output out of the repo-root context (S-10).
- `backend/Dockerfile`, `web/Dockerfile` -- the two images, base images pinned by exact tag.
- `web/Caddyfile` -- static SPA server on :8080 with the same log filter.
- `deploy/compose.yaml`, `deploy/Caddyfile` -- the production stack and proxy.
- `backend/src/test/java/ro/pricechecker/DeployConfigTest.java` -- 9 tests tagged AC-3.2.1..3, S-10.
- `backend/build.gradle.kts` -- test task inputs for the deploy files.

**Review:** patched 10 rows (medium 5, low 5) in 8 entries; deferred 3 (image build/validate, state dir ownership, docs); rejected rows and reasons are in the triage log. Patches were applied by Marghioala (Developer) directly to save run budget instead of re-engaging the implementation subagent.

**Follow-up review recommended:** true. Unverified risk: no image has been built and neither Caddyfile has been run through `caddy validate`; the base-image tags and the generator image's `docker-entrypoint.sh` were chosen without a registry check.

**Verification:** `backend/gradlew -p backend spotlessApply check` pass; `DeployConfigTest` 9 tests, 0 failures. No Docker daemon or `caddy` on the host.

**Residual risks:** story 3.3 must create `/opt/price-checker/state` writable by `BACKEND_UID`/`BACKEND_GID`, set `IMAGE_TAG` and `SITE_ADDRESS`, and build/validate the images before deploy.

### Round 2 (follow-up pass, 2026-11-18)

**Trigger:** round 1 failed only because the security step left no valid verdict (AD-18, AD-10). That is a reviewer step error with no finding attached, so no code change answers it; the security review simply needs to run again on this round. Both code reviews passed.

**Changes:**
- `deploy/Caddyfile` -- global `servers { protocols h1 h2 }`: HTTP/3 is off because compose publishes only TCP 443 (code_review CR-2, S-13).
- `.dockerignore` -- adds `**/*.pem`, `**/*.key`, `**/*.p12`, `**/*.pfx`, `**/.claude/settings.local.json` (S-10).
- `backend/src/test/java/ro/pricechecker/DeployConfigTest.java` -- new `ac_3_2_2_http3IsOffWhileNoUdpPortIsPublished`; the non-root check rejects gid 0; the S-10 check covers the new patterns. 15 tests (the round-1 note "9 tests" was stale: there were 14).

**Review:** 36 findings. Patched 3 entries (medium 1, low 2). Deferred 1 new item (client IP in other Caddy loggers, medium unverified); the rest carried or rejected, with reasons in the triage log. code_review CR-1 (state dir ownership) and code_lens CR-1/CR-2 (no image build or caddy validate) were already deferred to story 3.3.

**Follow-up review recommended:** false. This was a follow-up pass and no `high` entry was patched.

**Verification:** `backend/gradlew -p backend spotlessApply check` passes, including all 15 `DeployConfigTest` tests. There is no Docker daemon or `caddy` binary on the host, so no image was built and neither Caddyfile was validated.

**Residual risks:** unchanged from round 1. The `servers`/`protocols` global option is new Caddyfile syntax that only the deferred `caddy validate` will check.
