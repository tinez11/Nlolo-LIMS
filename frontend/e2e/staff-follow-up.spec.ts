import { expect, test } from '@playwright/test';
import { expectRouteDenied, expectStaffShellReady } from './guards';

/**
 * Premiums to follow up (2026-11-11, analytics plan 1).
 *
 * What is left off the list -- a premium the balance or an agent's cash covers, an offer never taken up, another
 * team's policy -- is proven server-side (BillingApiIntegrationTest, FollowUpQueueIntegrationTest) where the absent row
 * can be controlled. What is proven here is the wiring: the nav item, the Overdue tab as the opening view, a call
 * recorded on a real row showing under "What was done", the Excel file arriving, and the finance gate.
 *
 * No reminder is sent here: it would text a real customer.
 */
test.describe('premiums to follow up', () => {
  test.use({ storageState: 'e2e/.auth/staff-finance.json' });

  test('opens from Collections on the overdue, and a recorded call shows under what was done', async ({ page }) => {
    await page.goto('/staff/policies');
    await expectStaffShellReady(page);

    await page.getByRole('link', { name: 'Premiums to follow up' }).click();
    await expect(page).toHaveURL(/\/staff\/follow-up$/);
    await expect(page.getByRole('heading', { name: 'Premiums to follow up' })).toBeVisible({ timeout: 30_000 });
    await expect(page.getByRole('button', { name: /^Overdue/ })).toHaveAttribute('aria-pressed', 'true');

    const table = page.getByRole('table', { name: 'Premiums to follow up' });
    const empty = page.getByText('Nobody is behind on a premium');
    await expect(table.or(empty)).toBeVisible({ timeout: 30_000 });
    if (!(await table.isVisible())) return;

    const firstRow = table.locator('tbody tr').first();
    await firstRow.getByRole('button', { name: 'Record a call' }).click();
    await page.getByLabel(/What happened/).selectOption('NO_ANSWER');
    await page.getByLabel(/^Note/).fill('e2e: no answer');
    await page.getByRole('button', { name: 'Save call' }).click();

    await expect(page.getByRole('button', { name: 'Save call' })).not.toBeVisible({ timeout: 20_000 });
    await expect(table.getByText(/Called .*: No answer/).first()).toBeVisible({ timeout: 20_000 });
  });

  test('downloads the list as an Excel file', async ({ page }) => {
    await page.goto('/staff/follow-up');
    await expectStaffShellReady(page);
    await expect(page.getByRole('heading', { name: 'Premiums to follow up' })).toBeVisible({ timeout: 30_000 });

    const download = page.waitForEvent('download', { timeout: 60_000 });
    await page.getByRole('button', { name: 'Download Excel' }).click();
    expect((await download).suggestedFilename()).toMatch(/^premiums-to-follow-up-\d{4}-\d{2}-\d{2}\.xlsx$/);
  });
});

test.describe('premiums to follow up is collections-gated', () => {
  test.use({ storageState: 'e2e/.auth/staff-assessor.json' });

  test('a claims assessor gets an access panel', async ({ page }) => {
    await page.goto('/staff/policies');
    await expectStaffShellReady(page);

    await expect(page.getByRole('link', { name: 'Premiums to follow up' })).not.toBeVisible();
    await expectRouteDenied(page, '/staff/follow-up');
  });
});
