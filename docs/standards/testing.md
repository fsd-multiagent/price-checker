# Testing standard

- **Followed by:** Eufrosina (QA) and Marghioala (Developer)
- **Verified by:** Filofteia (Code Reviewer) and CI
- **Status:** seed. Eufrosina (QA) refines it during stage 6 or E1, when the test framework is set up.

- **T-1** Every acceptance criterion has at least one test. The test's name or tag includes the acceptance criterion's ID, so that the traceability matrix can be generated.
- **T-2** Adapters are tested against recorded HTML fixtures in `fixtures/<retailer>/`. A fixture holds facts only, as in `security-compliance.md` S-7: strip images and long descriptions before you commit it.
- **T-3** CI never calls a real retailer. Live calls happen only in the scheduled health check.
- **T-4** One end-to-end test covers the main journey: enter an EAN, see the comparison, and handle one retailer failing. It runs against a stubbed backend or stubbed retailers. Use Playwright (configured in TEA).
- **T-5** Tests are deterministic. They use no `sleep` calls and no reliance on wall-clock time or test order. Time and randomness are injected.
- **T-6** Every bug fix starts with a test that reproduces the bug. This includes self-healing fixes: the failing fixture from the `adapter-broken` issue becomes a test before the adapter is changed.
- **T-7** EAN validation is tested on valid EAN-8 and EAN-13 codes, a wrong length, non-digits, and a bad check digit.
- **T-8** The test commands are listed in the root `CLAUDE.md`. Zamolxe (Orchestrator) runs exactly those commands at the "tests pass" gate.
