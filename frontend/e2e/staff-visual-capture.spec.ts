import { expect, test } from '@playwright/test';

/**
 * Screenshots for a HUMAN to compare before and after the redesign. Never asserted.
 *
 * Not `toHaveScreenshot`: the suite writes to the shared seeded database, so policy numbers,
 * counts and dates move between runs, and a pixel diff would either flake or be tuned until
 * it proves nothing. Opt-in so it never slows the ordinary run.
 */
const ROUTES = ['policies', 'claims', 'underwriting', 'clients/individuals', 'arrears'];
const VIEWPORTS = [
  { name: 'desktop', width: 1440, height: 900 },
  { name: 'phone', width: 390, height: 844 },
];

test.use({ storageState: 'e2e/.auth/staff-admin.json' });

test('capture review screenshots', async ({ page }) => {
  test.skip(!process.env.CAPTURE, 'set CAPTURE=1 to write review screenshots');
  test.slow();
  for (const viewport of VIEWPORTS) {
    await page.setViewportSize(viewport);
    for (const route of ROUTES) {
      await page.goto(`/staff/${route}`);
      await expect(page.locator('h1')).toBeVisible({ timeout: 30_000 });
      // Not `networkidle`, which this app never reaches (HMR socket, silent renewal).
      await expect(page.locator('.animate-pulse, .animate-spin')).toHaveCount(0, {
        timeout: 30_000,
      });
      await page.screenshot({
        path: `test-results/visual/${viewport.name}-${route.replace(/\//g, '_')}.png`,
        fullPage: true,
      });
    }
  }
});

test('capture the tabbed policy record', async ({ page }) => {
  test.skip(!process.env.CAPTURE, 'set CAPTURE=1 to write review screenshots');
  test.slow();
  await page.goto('/staff/policies');
  await page.getByRole('table', { name: 'Policies' }).getByRole('button').first().click();
  await page.getByRole('dialog').getByRole('link', { name: /full detail/i }).click();
  await expect(page.getByRole('tablist')).toBeVisible({ timeout: 30_000 });
  for (const viewport of VIEWPORTS) {
    await page.setViewportSize(viewport);
    await page.screenshot({ path: `test-results/visual/${viewport.name}-policy-record.png` });
  }
});
