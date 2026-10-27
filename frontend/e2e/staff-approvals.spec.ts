import { expect, test } from '@playwright/test';
import { expectNavItemsHidden, expectRouteDenied } from './guards';

/**
 * The approvals inbox (2026-10-27) through the real stack: one read the approvals module composes from every module
 * that holds a signature. The rows themselves are pinned by ApprovalsPage.test and the backend's ApprovalsContractTest
 * against a real withdrawal; this proves the page, its count and who is offered it, with real logins.
 */
test.describe('approvals inbox', () => {
  test.use({ storageState: 'e2e/.auth/staff-finance.json' });

  test('a finance officer finds every signature waiting on them in one place, counted in the sidebar', async ({
    page,
  }) => {
    await page.goto('/staff/dashboard');
    const navItem = page.getByRole('link', { name: /^Approvals/ });
    await expect(navItem).toBeVisible({ timeout: 30_000 });
    await navItem.click();

    await expect(page.getByRole('heading', { name: 'Approvals', level: 1 })).toBeVisible();
    // The count line, not the description above it, which also says "waiting for your".
    await expect(page.getByText(/\d+ waiting for you ·/)).toBeVisible({ timeout: 30_000 });
    // Finance officers sign withdrawals and payouts: those kinds are theirs, whether or not anything waits today.
    await page.getByRole('button', { name: 'Everything you approve' }).click();
    await expect(page.getByRole('region', { name: 'Account withdrawals' })).toBeVisible();
    await expect(page.getByRole('region', { name: 'Payouts to approve' })).toBeVisible();
    // And the books' approvals are the finance approver's, not theirs.
    await expect(page.getByRole('region', { name: 'Manual journals' })).toHaveCount(0);
  });
});

test.describe('approvals inbox, for someone who signs none of it', () => {
  test('an underwriter is offered no approvals inbox', async ({ page }) => {
    await page.goto('/staff/policies');
    await expectNavItemsHidden(page, 'Approvals');
    await expectRouteDenied(page, '/staff/approvals');
  });
});
