import { expect, test } from '@playwright/test';

/**
 * The dashboard against the real stack: the figures arrive from the real endpoint, and a card opens the list it
 * counted. Runs as the default staff user (auth.setup.ts), who holds underwriting and finance.
 */
test.describe('staff dashboard', () => {
  test('lands on the dashboard and a count opens its own filtered list', async ({ page }) => {
    await page.goto('/staff');
    await expect(page.getByRole('heading', { name: 'Dashboard', exact: true })).toBeVisible();

    const openCases = page.getByRole('link', { name: /Open cases\. Open the list/ });
    await expect(openCases).toBeVisible();
    await openCases.click();
    await expect(page).toHaveURL(/\/staff\/underwriting\?status=OPEN/);
  });

  test('the old Today address still arrives at the dashboard', async ({ page }) => {
    await page.goto('/staff/today');
    await expect(page).toHaveURL(/\/staff\/dashboard/);
    await expect(page.getByRole('heading', { name: 'Dashboard', exact: true })).toBeVisible();
  });
});
