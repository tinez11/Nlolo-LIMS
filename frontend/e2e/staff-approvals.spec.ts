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
    await expect(page.getByRole('region', { name: 'Savings withdrawals' })).toBeVisible();
    await expect(page.getByRole('region', { name: 'Payouts to approve' })).toBeVisible();
    // Which kinds show follows the person's permissions, not their role (2026-10-26): dev's staff.finance was given
    // ledger.approve on 2026-11-08, so manual journals are theirs here. ApprovalsTest pins the permission rule itself.
  });

  /** 2026-11-09, the user: "many info there" -- a row is what it is and whose money; the rest opens in a drawer. */
  test('a request opens every fact in a drawer that leads to the page that acts', async ({ page }) => {
    await page.goto('/staff/approvals?show=all');
    await expect(page.getByText(/\d+ waiting for you ·/)).toBeVisible({ timeout: 30_000 });
    const details = page.getByRole('button', { name: /^Details of / }).first();
    test.skip((await details.count()) === 0, 'nothing waits for staff.finance on this stack');
    await details.click();
    const drawer = page.getByRole('dialog');
    await expect(drawer.getByText('Waiting', { exact: true })).toBeVisible();
    await expect(drawer.getByText('Asked by', { exact: true })).toBeVisible();
    await drawer.getByRole('button', { name: 'Close' }).first().click();
    await expect(drawer).toHaveCount(0);
  });
});

test.describe('approvals inbox, for someone who signs none of it', () => {
  test('an underwriter is offered no approvals inbox', async ({ page }) => {
    await page.goto('/staff/policies');
    await expectNavItemsHidden(page, 'Approvals');
    await expectRouteDenied(page, '/staff/approvals');
  });
});
