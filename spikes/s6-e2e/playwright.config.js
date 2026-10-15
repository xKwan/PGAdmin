// Spike S6 (partial): Playwright with the installed Microsoft Edge only (channel "msedge").
// No browser is downloaded: PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD=1 is set by the runner and by Use-Toolchain.ps1.
import { defineConfig } from '@playwright/test';

export default defineConfig({
  testDir: './tests',
  timeout: 60_000,
  workers: 1,
  fullyParallel: false,
  retries: 0,
  reporter: [['list'], ['json', { outputFile: 'test-results/results.json' }]],
  use: {
    channel: 'msedge',
    headless: true,
  },
});
