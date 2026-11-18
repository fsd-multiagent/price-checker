import { defineConfig, devices } from '@playwright/test';

// End-to-end tests of UJ-1 and UJ-2 (story 2.14, T-4). Starts the e2e backend (stubbed retailer
// HTTP, no network) and the Vite dev server, then drives Chromium against them.
const backendPort = process.env.E2E_BACKEND_PORT ?? '18080';
const webPort = process.env.E2E_WEB_PORT ?? '5174';
const repoRoot = new URL('../..', import.meta.url).pathname;

export default defineConfig({
  testDir: '.',
  testMatch: '*.spec.ts',
  outputDir: 'node_modules/.e2e/results',
  reporter: [['list'], ['html', { outputFolder: 'node_modules/.e2e/report', open: 'never' }]],
  forbidOnly: !!process.env.CI,
  retries: 0,
  workers: 1,
  use: {
    baseURL: `http://127.0.0.1:${webPort}`,
    trace: 'retain-on-failure',
  },
  projects: [{ name: 'chromium', use: { ...devices['Desktop Chrome'] } }],
  webServer: [
    {
      command: 'backend/gradlew -p backend bootTestRun',
      cwd: repoRoot,
      // An e2e-only path, so a normal dev backend on this port is never reused by mistake.
      url: `http://127.0.0.1:${backendPort}/e2e/requests?ean=0`,
      env: { E2E_BACKEND_PORT: backendPort },
      timeout: 300_000,
      reuseExistingServer: !process.env.CI,
      stdout: 'ignore',
      stderr: 'pipe',
    },
    {
      // The web app imports the generated API client, so generate it first (web/CLAUDE.md).
      command: `npm run generate && node_modules/.bin/vite --config e2e/vite.config.ts --host 127.0.0.1 --port ${webPort}`,
      cwd: new URL('..', import.meta.url).pathname,
      url: `http://127.0.0.1:${webPort}/`,
      env: { E2E_BACKEND_PORT: backendPort },
      timeout: 120_000,
      reuseExistingServer: !process.env.CI,
    },
  ],
});
