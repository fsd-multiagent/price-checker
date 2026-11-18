# backend

Spring Boot 4.1.2 on Java 21, built with the Gradle 9.9.1 wrapper (Kotlin DSL). Rules: `docs/standards/coding.md` C-B1 to C-B4. Decisions: ADR 0006 (ports and adapters, packages) and ADR 0014 (build and checks).

## Commands

Run from the repository root. No `cd` is needed (T-8, ADR 0014).

| Task | Command |
|---|---|
| Compile, format check, all tests | `backend/gradlew -p backend check` |
| Format check only | `backend/gradlew -p backend spotlessCheck` |
| Fix formatting | `backend/gradlew -p backend spotlessApply` |

`check` is not yet in the `sdlc-commands` gate or in CI (deferred in story 1.1; the gate change needs a PR to `main`, ADR 0004). Run it by hand before you hand work over.

## Layout

| Path | What it holds |
|---|---|
| `src/main/java/ro/pricechecker/` | Root wiring only: `PriceCheckerApplication`, `ClockConfig` (the one UTC `java.time.Clock` bean, T-5) |
| `src/main/java/ro/pricechecker/<feature>/` | `api`, `ean`, `adapter`, `lookup`, `cache`, `registry`, `fetch`, `extract`, `health` (C-B1) |
| `src/main/java/ro/pricechecker/retailer/<id>/` | One package per retailer; the only place a retailer domain or selector may appear (C-5) |
| `src/main/resources/application.yaml` | App config. Tomcat access log is off (AD-14, C-9) |
| `src/test/java/ro/pricechecker/arch/` | `ArchitectureRules` + `ArchitectureTest` (AD-1 arrows, known packages, retailer isolation, HTTP client only in `fetch`), `SpineDiagramTest`, `RetailerTextScan` + test |
| `src/test/java/ro/pricechecker/archfixtures/` | Deliberate violations. Each proves one rule fails. Not production code |
| `src/test/java/ro/pricechecker/AppConfigTest.java` | Checks that the Tomcat access log is off and the UTC `Clock` bean exists (AC-1.1.4) |
| `src/test/java/ro/pricechecker/BuildPinningTest.java` | Checks pinned versions, wrapper checksum (S-12), Spotless and `-Xlint:all -Werror` |

## Conventions

- Tag every test with its acceptance criterion and prefix the method name: `@Tag("AC-1.1.2")`, `void ac_1_1_2_...()` (T-1).
- Constructor injection only (C-B2). This includes test classes.
- Every new architecture rule gets a fixture under `archfixtures/` and a test that asserts the rule fails on it.

## Known pitfalls

- A new top-level package under `ro.pricechecker` fails `onlyKnownPackages`. A new feature package must be added to `ArchitectureRules.ALLOWED` **and** to the paradigm diagram in the architecture spine; `SpineDiagramTest` requires them to match exactly.
- `BuildPinningTest` and `SpineDiagramTest` read `_bmad-output/planning-artifacts/architecture/architecture-price-checker-2026-11-17/ARCHITECTURE-SPINE.md`. Changing a version in `build.gradle.kts`, `settings.gradle.kts` or the wrapper without updating the spine Stack table fails `check`, and so does editing the spine diagram without updating `ArchitectureRules`.
- The tests read files relative to `backend/` (Gradle's test working directory). Running them from an IDE with another working directory breaks them; the gate is the Gradle command above.
- `-Xlint:all -Werror` turns every compiler warning into an error.
- The root `.gitignore` ignores `*.jar`. `backend/.gitignore` re-includes `gradle/wrapper/gradle-wrapper.jar`; keep it.
- The retailer text scan is a heuristic over string literals in `src/main/java`. It does not see text blocks or resource files, and it may flag harmless literals such as `".yaml"` (both gaps are deferred in story 1.1). Never weaken the scan to get past a false positive; raise it as a finding.
- No real retailer domain anywhere in code or tests. Use example domains such as `www.example-shop.ro`.
