import { expect, test } from '@playwright/test';

/**
 * The client Premiums tab (2026-11-12): finance opens a client and sees what they owe across every policy, can show
 * every invoice raised, and downloads it all as one file. An underwriter, who cannot see collections, has no such tab.
 *
 * Proves the round trip through the real backend, not the dev data's totals: the first client in the register is used,
 * whatever they hold -- an empty Premiums tab is an answer too.
 */
test.describe('staff client premiums', () => {
  test('finance reads a client’s premiums, shows every invoice raised, and downloads them', async ({ browser }) => {
    const context = await browser.newContext({ storageState: 'e2e/.auth/staff-finance.json' });
    const page = await context.newPage();
    await page.goto('/staff/clients/individuals');
    const table = page.getByRole('table', { name: 'Individual clients' });
    await expect(table).toBeVisible({ timeout: 30_000 });
    await table.getByRole('button').first().click();

    await page.getByRole('tab', { name: 'Premiums' }).click();
    await expect(page.getByText('Overdue now')).toBeVisible({ timeout: 30_000 });
    await expect(page.getByText('Next due', { exact: true })).toBeVisible();
    await expect(page.getByText('Last receipt')).toBeVisible();

    await page.getByRole('group', { name: 'Show invoices due' }).getByRole('button', { name: 'All raised' }).click();
    await expect(page).toHaveURL(/premiums=ALL/);
    await expect(page.getByText('Due on every invoice raised')).toBeVisible({ timeout: 30_000 });

    const download = page.waitForEvent('download');
    await page.getByRole('button', { name: 'Download Excel' }).click();
    expect((await download).suggestedFilename()).toMatch(/^premiums-\d{4}-\d{2}-\d{2}\.xlsx$/);
    await context.close();
  });

  test('an underwriter has no Premiums tab', async ({ page }) => {
    await page.goto('/staff/clients/individuals');
    const table = page.getByRole('table', { name: 'Individual clients' });
    await expect(table).toBeVisible({ timeout: 30_000 });
    await table.getByRole('button').first().click();

    await expect(page.getByRole('tab', { name: 'Policies' })).toBeVisible({ timeout: 30_000 });
    await expect(page.getByRole('tab', { name: 'Premiums' })).toHaveCount(0);
  });
});
