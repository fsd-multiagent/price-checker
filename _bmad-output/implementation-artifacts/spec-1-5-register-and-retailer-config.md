---
title: 'Story 1.5: Source register gate and retailer config'
type: 'feature'
created: '2026-11-17'
status: 'done'
baseline_revision: 'da39396bd7cec31303c2ad8f80b82a58ae190e94'
review_loop_iteration: 0
followup_review_recommended: true
context:
  - '{project-root}/docs/adr/0009-retailer-config-and-register.md'
warnings: []
deferred:
  - summary: >-
      Backend tests (incl. the ConfigCheck gate) run in no CI job or sdlc-commands gate, so a config that violates the register can merge green.
    evidence: |-
      ci.yml has no Gradle step; AGENTS.md sdlc-commands lists only orchestrator pytest. Fix edits AGENTS.md (owner, ADR 0004) and ci.yml (story 1.9).
    location: >-
      AGENTS.md sdlc-commands block; .github/workflows/ci.yml
    severity: high
  - summary: >-
      Registry loads the first classpath match of the bundled register resource; a second copy on the classpath could shadow it.
    evidence: |-
      Unverified: settle by checking whether any dependency or test resource can add that path, or fail when getResources returns more than one match.
    location: >-
      backend/src/main/java/ro/pricechecker/registry/Registry.java
    severity: medium (unverified)
  - summary: >-
      retailers.yaml has no name field, which the spine AD-9 config table lists; story 1.6 needs a display name.
    evidence: |-
      Story 1.5 ACs list adapter, resolution, limits and deadlineMs only; add name with the lookup skeleton.
    location: >-
      config/retailers.yaml
    severity: low
---

<intent-contract>

## Intent

**Problem:** Nothing in the backend reads `docs/source-register.md`, and there is no retailer config, so nothing stops code from contacting a retailer the owner has not approved (FR-9, AD-8, S-1).

**Approach:** Bundle the register and `config/*.yaml` into the backend artifact at build time; add one `registry` parser for the register's `Domain`/`Status`/`Limits` columns, loaders for `retailers.yaml` and `known-eans.yaml`, a pure consistency check, and a `Registry` bean that loads fixed classpath resources at start and refuses to start on violations.

## Boundaries & Constraints

**Always:** Only `Approved YYYY-MM-DD` (a real date) approves; any register parse error yields an empty approved set. Resource locations are constants, never properties or env vars (AC 1.5.4). Constructor injection (C-B2); tests tagged `AC-1.5.x` (T-1); no retailer domain or selector literal in `src/main/java` (AD-1 text scan); tests use `*.example` domains for synthetic data. `registry` imports no other feature package (spine arrows: registry has none).

**Never:** No change to `docs/source-register.md` Status cells. No lookup, adapter, fetch, kill-switch (`runtime.yaml`) or BlockStore code (later stories). No Known EAN URLs added (file starts empty; no retailer may be contacted). No `sdlc-commands` edit in `AGENTS.md`.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Approved row | Status `Approved 2026-11-20` | `isApproved` true | — |
| Not approved | `Proposed`, `Approved`, `Approved 2026-13-40`, `approved 2026-11-20`, `Retired 2026-11-20` | false | — |
| Parse error | no Retailers table, missing Domain/Status column, row with wrong cell count, duplicate domain | nothing approved, `errors()` non-empty | — |
| Limits | `1 request / 2 s, concurrency 1` | 2000 ms interval, concurrency 1 | `—` or garbage → unparseable |
| Enabled not approved | `adapter: enabled` on Proposed domain | violation | startup fails |
| Approved, no entry | Approved domain absent from retailers.yaml | violation | — |
| Resolution | missing or `scrape` | violation | — |
| Looser limits | `minIntervalMs` < register interval, or `concurrency` > register | violation | — |
| Known EAN | bad check digit, `http://`, other host, Approved retailer with < 2 entries | violation | — |
| Bypass attempt | properties like `registry.approved=…`, `pricechecker.registry.*` | approval unchanged | — |

</intent-contract>

## Code Map

- `backend/build.gradle.kts` -- add `processResources` copying `../docs/source-register.md` to `registry/source-register.md` and `../config/*.yaml` to `registry/` in the jar (AC 1.5.1). SnakeYAML comes with `spring-boot-starter` (use `SafeConstructor`/`LoaderOptions`, no new dependency).
- `backend/src/main/java/ro/pricechecker/registry/` -- empty except `package-info.java`. New: `SourceRegister` (parse markdown table under `## Retailers`, strip backticks), `RegisterLimits` (record + parse), `RetailerConfig` (record, raw nullable fields), `KnownEan` (record), `RegistryYaml` (parse both YAML files), `ConfigCheck` (pure `violations(...)` → `List<String>`), `Registry` (`@Component`, loads constants, exposes `isApproved`, `retailers`, `knownEans`; throws `IllegalStateException` on violations).
- `backend/src/test/java/ro/pricechecker/arch/RetailerTextScan.java:23` -- DOMAIN pattern flags `*.ro|com|…` literals; `BARE_SELECTOR` flags literals like `".yaml"`. Avoid both in main code.
- `docs/source-register.md` -- read-only; cel.ro and evomag.ro are `Proposed`, Limits `1 request / 2 s, concurrency 1`.
- Spine AD-8 (`ARCHITECTURE-SPINE.md:125-129`), config table `:139-140`, AD-12 `:162` (all `resolution: curated`).

## Tasks & Acceptance

**Execution:**
- `backend/build.gradle.kts` -- bundle register and config files -- AC 1.5.1.
- `config/retailers.yaml` -- cel.ro and evomag.ro: `adapter: disabled`, `resolution: curated`, `minIntervalMs: 2000`, `concurrency: 1`, `deadlineMs: 4000` -- AC 1.5.2.
- `config/known-eans.yaml` -- `knownEans: {}` with a comment on the format -- AC 1.5.3.
- `registry/*.java` (above) -- parser, loaders, check, bean -- AC 1.5.1-1.5.4.
- `backend/src/test/java/ro/pricechecker/registry/SourceRegisterTest.java`, `ConfigCheckTest.java`, `RegistryYamlTest.java` -- unit tests for every matrix row -- AC 1.5.1-1.5.3.
- `backend/src/test/java/ro/pricechecker/registry/BundledConfigTest.java` -- reads the classpath copies; register parses with no errors and equals the repo file; `ConfigCheck` on real files has no violations -- AC 1.5.1-1.5.3.
- `backend/src/test/java/ro/pricechecker/registry/ApprovalBypassTest.java` -- `@SpringBootTest` with bypass properties; approved set equals the parsed bundled register's -- AC 1.5.4.

**Acceptance Criteria:**
- Given the built backend, when the classpath is read, then `registry/source-register.md` equals the repo file and `Registry` reports approval only for `Approved YYYY-MM-DD` rows.
- Given the repo config, when `ConfigCheck` runs, then it reports no violations, and each matrix violation case is reported.
- Given properties that try to approve a retailer, when the app starts, then `Registry.isApproved` is unchanged.

### 2026-11-17 — Review pass
- verdicts: 33 findings — high 1, medium 3, low 21, false 3, maybe-false 5
- findings:
  - `[medium]` `[patch]` Blind: interval floor-divides (3 req/1 s → 333) — `Math.ceilDiv`, test added.
  - `[low]` `[patch]` Blind: duplicate EANs count toward the 2-entry minimum — counts distinct EANs, test added.
  - `[low]` `[reject]` Blind: domain cell format/case not enforced — owner-edited register; current rows valid; adds guards.
  - `[low]` `[reject]` Blind: future approval dates approve — the date records when the owner approved; no rule compares with today.
  - `[maybe-false]` `[defer]` Blind: bypass via a second classpath copy — deferred medium (unverified).
  - `[low]` `[reject]` Blind: ApprovalBypassTest passes by construction — it pins that the bean ignores properties, AC 1.5.4's claim.
  - `[low]` `[patch]` Blind: retailers.yaml comment says "fails the build", omits deadlineMs — comment corrected.
  - `[low]` `[reject]` Blind: test oracle regex lacks calendar check — real register has no Approved row; synthetic tests cover dates.
  - `[low]` `[reject]` Blind: isApproved rebuilds the set per call — negligible on 7 rows.
  - `[low]` `[reject]` Blind: no deadlineMs upper bound — no AC or ADR value to bound it by.
  - `[low]` `[reject]` Blind: integer parsing messages misleading — a violation is still reported; message only.
  - `[low]` `[reject]` Blind: untested YAML shapes / `Path.of("..")` — Gradle sets test cwd to backend/ (story 1.1 precedent).
  - `[false]` `[reject]` Blind: spec missing from diff — excluded on purpose (claims file); ADR 0009 already records bundling.
  - `[low]` `[reject]` Edge: null retailers.yaml key gives NPE — fails loudly at test/startup; not reachable from repo config.
  - `[medium]` `[patch]` Edge: interval rounds down — same fix as above.
  - `[low]` `[patch]` Edge: seconds overflow makes interval negative — `Math.multiplyExact`, now unparseable; test added.
  - `[low]` `[patch]` Edge: duplicate EAN entries — same fix as above.
  - `[low]` `[reject]` Edge: URL with port or no path passes — still https on the retailer host; no AC asks more.
  - `[low]` `[reject]` Edge: table without separator row drops first row — owner-maintained markdown always has it.
  - `[maybe-false]` `[reject]` Edge: future approval date — see above; if true, low.
  - `[false]` `[reject]` Edge: missing register file builds silently — BundledConfigTest and startup fail on the missing resource.
  - `[maybe-false]` `[reject]` Edge: comment claims build failure — resolved by the comment patch.
  - `[high]` `[defer]` VerifGap: backend tests run in no CI or gate — fix edits AGENTS.md and ci.yml (owner / story 1.9).
  - `[medium]` `[patch]` VerifGap: deadlineMs rule untested — `ac_1_5_2_deadlineMissingOrNonPositive` added.
  - `[low]` `[patch]` VerifGap: concurrency < 1 untested — `ac_1_5_2_concurrencyBelowOne` added.
  - `[low]` `[patch]` VerifGap: known EANs for unconfigured domain untested — test added.
  - `[low]` `[reject]` VerifGap: backend parser stricter than guard/orchestrator — fails safe; the story asks for the exact form.
  - `[maybe-false]` `[defer]` Intent: "fail CI" not reachable — same root as the high CI deferral.
  - `[low]` `[reject]` Intent: bundling checked on resources, not the jar — bootJar packages processResources output.
  - `[low]` `[reject]` Intent: startup refusal beyond the AC — spec design choice (fail closed), in Design Notes.
  - `[maybe-false]` `[reject]` Intent: bypass test uses properties, not OS env — env vars reach Spring only as properties; Registry reads none.
  - `[low]` `[defer]` Intent: no `name` field per AD-9 — deferred to story 1.6.
  - `[false]` `[reject]` Intent: global parse error empties approval — that is AC 1.5.1 as written.

## Design Notes

EAN validity in `registry` uses a private GS1 check (8 or 13 digits) because `registry` may not import `ean` (spine arrows) and `ean.Ean` lands in story 1.4. Limits: `N request(s) / M s` → interval `M*1000/N` ms. Running `ConfigCheck` at startup makes the gate fail closed in production as well as in tests.

## Verification

**Commands:**
- `backend/gradlew -p backend check` -- expected: BUILD SUCCESSFUL, new `AC-1.5.x` tests pass, spotless clean.

## Auto Run Result

- **Summary:** `registry` package with one register parser (only `Approved YYYY-MM-DD` approves; any parse error approves nothing), Limits parsing, YAML loaders, a pure `ConfigCheck`, and a `Registry` bean that loads fixed bundled resources and refuses to start on violations; `processResources` bundles the register and `config/*.yaml`.
- **Files:** `backend/build.gradle.kts` (bundling); `registry/{SourceRegister,RegisterLimits,RetailerConfig,KnownEan,RegistryYaml,ConfigCheck,Registry}.java`; tests `SourceRegisterTest`, `ConfigCheckTest`, `RegistryYamlTest`, `BundledConfigTest`, `ApprovalBypassTest`; `config/retailers.yaml` (cel.ro, evomag.ro disabled/curated); `config/known-eans.yaml` (empty).
- **Review:** 9 patch rows (2 medium, 7 low) applied by the parent session to save budget (implementation subagent not re-engaged); 4 rows deferred; 20 rejected (reasons in the triage log). Before review the parent also widened `resolution` to {search, catalog, curated} per AC 1.5.2.
- **Follow-up review recommended:** true. Two medium entries were patched; the unverified risk is that ceil-rounded intervals and distinct-EAN counting are checked only by the new unit tests.
- **Verification:** `backend/gradlew -p backend check` passes: BUILD SUCCESSFUL, 90 tests, 0 failed or skipped, spotless clean.
- **Residual risks:** CI and the gate do not run the backend tests yet (deferred, high). Approving a retailer will stop startup until two known EANs are added for it; this is intended, but it is a tripwire.
