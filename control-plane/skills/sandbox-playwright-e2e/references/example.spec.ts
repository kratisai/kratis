import { test, expect } from '@playwright/test';

// Example: navigate, assert the DOM, capture console/page errors, write a screenshot.
// Run against the app from the sandbox-run-application skill (default http://127.0.0.1:3000).
test('home page renders without page errors', async ({ page }) => {
  const errors: string[] = [];
  page.on('pageerror', (e) => errors.push(e.message));
  page.on('console', (m) => {
    if (m.type() === 'error') errors.push(m.text());
  });

  const response = await page.goto('/');
  expect(response?.ok()).toBeTruthy();
  await expect(page.locator('body')).toBeVisible();
  expect(errors).toEqual([]);

  await page.screenshot({ path: 'test-results/home.png', fullPage: true });
});

test('stubs an unavailable backend', async ({ page }) => {
  await page.route('**/api/data', (route) =>
    route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ items: ['a', 'b'] }),
    }),
  );

  await page.goto('/');
  const data = await page.evaluate(async () => (await fetch('/api/data')).json());
  expect(data.items).toEqual(['a', 'b']);
});
