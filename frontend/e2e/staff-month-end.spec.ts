import { expect, test } from '@playwright/test';

/**
 * The month-end close timeline through the real stack (2026-10-27): from Accounting periods, a month's eight steps --
 * read from the ledger's own records -- with the one that comes next marked and a way to the page that does it.
 */
test.describe('month-end close', () => {
  test.use({ storageState: 'e2e/.auth/staff-finance.json' });

  test('a month opens as the guide’s eight steps, the next one marked', async ({ page }) => {
    await page.goto('/staff/periods');
    const row = page.getByRole('listitem').filter({ has: page.getByRole('link', { name: 'Month-end steps' }) }).first();
    const period = (await row.getAttribute('aria-label'))!.replace('Period ', '');
    await row.getByRole('link', { name: 'Month-end steps' }).click();

    await expect(page).toHaveURL(new RegExp(`/staff/month-end\\?period=${period}$`));
    const steps = page.getByRole('list', { name: 'Month-end steps' });
    await expect(steps.getByRole('listitem')).toHaveCount(8, { timeout: 30_000 });
    await expect(steps.getByRole('listitem').first()).toHaveAccessibleName('Step 1: Clear unposted events');
    await expect(steps.getByRole('listitem').last()).toHaveAccessibleName('Step 8: Lock the month');

    // Unless the month is already locked, one step is next, and it leads to where its work is done.
    const next = steps.locator('[aria-current="step"]');
    if ((await next.count()) > 0) {
      await expect(next.getByRole('link', { name: /^Open / })).toBeVisible();
    } else {
      await expect(page.getByRole('heading', { name: /is closed$/ })).toBeVisible();
    }
  });
});
