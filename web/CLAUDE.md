# web/

React + TypeScript + Vite single-page app (ADR 0013, C-W1 to C-W4). Node 24 LTS (`.nvmrc`).

## Commands (from the repo root)

- Install: `npm --prefix web ci`
- Dev server: `npm --prefix web run dev`
- Build: `npm --prefix web run build` (output in `web/dist/`)
- All checks: `npm --prefix web run check` (ESLint, Prettier check, `tsc -b`, Vitest)
- Format: `npm --prefix web run format`

## Conventions

- Dependencies are pinned to exact versions and `package-lock.json` is committed (S-12). Add one with `npm --prefix web install --save-exact <pkg>@<version>`; `src/scaffold.test.ts` fails on `^` or `~`.
- Styles: plain CSS. Design tokens live only in `src/styles/tokens.css` as `--<group>-<name>` custom properties mirroring DESIGN.md frontmatter; `src/styles/tokens.test.ts` checks them against DESIGN.md. Use `var(--...)`, never raw colours or sizes. Page-level rules are in `src/styles/base.css`.
- No web fonts, `@import`, `@font-face` or third-party URL in CSS or `index.html` (AC-N5.4).
- No UI, state or routing library. The API client is generated into `src/generated/` (not committed, C-W1).
- UI copy comes verbatim from EXPERIENCE.md.
- Unit tests: `src/**/*.test.{ts,tsx}`, Vitest + jsdom + Testing Library + axe-core; setup in `src/test/setup.ts`. Put the AC id in each test name (T-1). jsdom does not apply stylesheets, so CSS checks read the files as text.
- End-to-end tests (Playwright) go in `e2e/` when a story adds them (T-4).
