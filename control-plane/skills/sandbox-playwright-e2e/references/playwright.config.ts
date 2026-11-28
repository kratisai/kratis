import { defineConfig, devices } from '@playwright/test';

// Sandbox defaults: headless Chromium, artifacts on failure, no live browser.
// Point baseURL at the app started with the sandbox-run-application skill.
export default defineConfig({
  testDir: './tests',
  outputDir: '.kratis/test-results',
  timeout: 30_000,
  retries: 0,
  reporter: [['list'], ['html', { outputFolder: '.kratis/test-results/html-report', open: 'never' }]],
  use: {
    baseURL: process.env.BASE_URL || 'http://127.0.0.1:3000',
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
    video: 'retain-on-failure',
    launchOptions: { args: ['--no-sandbox', '--disable-dev-shm-usage', '--disable-gpu'] },
  },
  projects: [{ name: 'chromium', use: { ...devices['Desktop Chrome'] } }],
});
