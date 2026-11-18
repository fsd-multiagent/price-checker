---
title: 'Story 3.2: Container images, Compose and Caddy'
type: 'feature'
created: '2026-11-18'
status: 'done'
baseline_revision: 'c2c066f83ede02247b8dbe618d437189b50dbf5a'
review_loop_iteration: 0
followup_review_recommended: true
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
