# Coding standard

- **Followed by:** Marghioala (Developer)
- **Verified by:** Filofteia (Code Reviewer)
- **Status:** seed. Loredan (Architect) completes the parts marked TBD at stage 4.

## General

- **C-1** Implement only what the story's acceptance criteria ask for. Leave out extra features and unrelated refactoring.
- **C-2** Write tests first. Every acceptance criterion has at least one test that fails before the change and passes after it (see `testing.md`).
- **C-3** The API is designed contract-first. Change `api/openapi.yaml` first, then regenerate the server interfaces and the client. Never edit generated code by hand.
- **C-4** Never put secrets, keys or tokens in code, tests, fixtures or logs. Configuration comes from environment variables.
- **C-5** Retailer-specific code lives only in that retailer's adapter. Other code never hard-codes a retailer name, URL or selector. The list of retailers comes from configuration and the source register.
- **C-6** Adapters extract data in this order: embedded structured data (JSON-LD or microdata), then meta tags, then retailer-specific selectors. Each step is a separate, tested function.
- **C-7** A missing or unparseable price is `unavailable`. It is never `0`, never `null` shown as a price, and never a guess.
- **C-8** Each retailer lookup has its own timeout and its own error handling. When one retailer fails, the response marks that retailer as failed and still returns the others.
- **C-9** Logs contain the EAN, the retailer, the outcome and the timing. They never contain an IP address, a user agent string from the client, or other personal data.
- **C-10** Keep diffs small and focused. Don't leave dead code, commented-out code or TODOs without a story reference.
- **C-11** Before editing a folder, read its `CLAUDE.md` if it has one.
- **C-12** Every outbound HTTP call to a retailer goes through the shared fetch client, which enforces the rules in `security-compliance.md`. Adapters never open their own connections.

## Backend (Java 21, Spring Boot)

- **C-B1** Organize packages by feature (`ean`, `lookup`, `retailer.<name>`, `cache`, `health`), not by layer.
- **C-B2** Use constructor injection only. Never use field injection.
- **C-B3** Controllers implement the interfaces generated from OpenAPI and hold no business logic.
- **C-B4** Use JUnit 5 for tests. Formatter and static analysis: **TBD (Architect)**.

## Web (React)

- **C-W1** The API client is generated from `api/openapi.yaml`. Never write fetch calls to the backend by hand.
- **C-W2** Every input has a label. Every error message is announced to screen readers, for example through `aria-live`. Everything can be done with the keyboard.
- **C-W3** Prices are shown in RON with the retailer's name and the time the price was fetched. A retailer with no price shows "unavailable" or "not found", never an empty cell.
- **C-W4** Language (TypeScript or JavaScript), build tool and test runner: **TBD (Architect)**.
