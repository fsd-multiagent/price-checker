# Automation summary: story 1-1-backend-scaffold

Author: Eufrosina (QA), headless, run 20261117-174705-full, stage build, step qa, round 1.

Command: `backend/gradlew -p backend check` (compile, Spotless, tests). Result: 37 tests, 0 failures.

| AC | Tests (tag `AC-1.1.x`, method prefix `ac_1_1_x_`) | Added in this round |
| --- | --- | --- |
| 1.1.1 | `BuildPinningTest` (10): Java 21 runtime and toolchain, Spring Boot 4.x matching the plugin pin, no dynamic versions, wrapper sha256 pinned (S-12), Spotless + google-java-format, `-Xlint:all -Werror`, the spine Stack table lists the exact Boot, Gradle, ArchUnit, Spotless, google-java-format and foojay versions | all new (AC had no test, T-1) |
| 1.1.2 | `ArchitectureTest` (13), `SpineDiagramTest` (4) | `SpineDiagramTest` parses the spine mermaid diagram and requires `ArchitectureRules.ALLOWED` / `RETAILER_ALLOWED` to be exactly its solid arrows. New fixtures: `lookuptoretailer` (the dotted arrow is runtime-only), `extracttofetch`, `apitofetch` |
| 1.1.3 | `RetailerTextScanTest` (8) | none, coverage already adequate |
| 1.1.4 | `AppConfigTest` (2) | none. The access-log test reads the property and does not inspect the running Tomcat valves; this gap was already deferred in the story spec |

Notes:
- The backend tests are not yet in the AGENTS.md `sdlc-commands` gate or CI (already deferred in the spec, T-8). Until then they run only by hand.
- Tests are deterministic (T-5): they read only files and use no clock, sleep or network.
