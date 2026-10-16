// End-to-end tests (plan Phases 8 and 11) with the installed Microsoft Edge only (channel "msedge"; no browser is
// downloaded: PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD=1). Run through scripts/test/Invoke-E2E.ps1, which starts a real
// environment and sets E2E_BASE_URL, E2E_ROLE and E2E_SEED.
import { defineConfig } from '@playwright/test';

export default defineConfig({
  testDir: './tests',
  timeout: 90_000,
  expect: { timeout: 15_000 },
  workers: 1,
  fullyParallel: false,
  retries: 0,
  reporter: [['list'], ['json', { outputFile: 'test-results/results.json' }]],
  use: {
    baseURL: process.env.E2E_BASE_URL,
    channel: 'msedge',
    headless: true,
    viewport: { width: 1366, height: 768 },
    // Windows integrated sign-in only for the test host (spike S6, E2).
    launchOptions: { args: ['--auth-server-allowlist=localhost'] },
    trace: 'retain-on-failure',
  },
});
