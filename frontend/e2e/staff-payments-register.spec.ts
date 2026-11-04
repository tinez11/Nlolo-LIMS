import { expect, test } from '@playwright/test';
import { expectNavItemsHidden, expectRouteDenied } from './guards';

/**
 * The payments register (2026-10-27) through the real stack, as two registers since money movement 6d: Deposits for
 * money in, All payouts (under Payouts) for money out, each opening on what needs attention. Rows and filters are
 * pinned by PaymentsPage.test and PaymentContractTest against seeded ledgers; this proves the pages, their filters on
 * the URL, and who is offered them, with real logins.
 */
test.describe('payments registers', () => {
  test.use({ storageState: 'e2e/.auth/staff-finance.json' });

  test('finance opens Deposits on what needs attention, and can widen to every deposit', async ({ page }) => {
    await page.goto('/staff/dashboard');
    await page.getByRole('link', { name: /^Deposits/ }).click();
    await expect(page.getByRole('heading', { name: 'Deposits', level: 1 })).toBeVisible();
    await expect(page.getByRole('button', { name: 'Needs attention' })).toHaveAttribute('aria-pressed', 'true');

    await page.getByRole('button', { name: 'All', exact: true }).click();
    await expect(page).toHaveURL(/state=all/);
    // Every deposit the dev stack ever took: there are always some, and every one is money in.
    const table = page.getByRole('table', { name: 'Deposits' });
    await expect(table.getByRole('columnheader', { name: 'From' })).toBeVisible({ timeout: 30_000 });
    await expect(table.getByRole('row').nth(1)).toBeVisible({ timeout: 30_000 });
  });

  test('finance reaches every payout from Payouts, and back to the instalments', async ({ page }) => {
    await page.goto('/staff/dashboard');
    await page.getByRole('link', { name: /^Payouts/ }).click();
    await expect(page.getByRole('heading', { name: 'Payouts', level: 1 })).toBeVisible();

    await page.getByRole('link', { name: 'All payouts' }).click();
    await expect(page).toHaveURL(/\/staff\/payouts\/all$/);
    await expect(page.getByRole('heading', { name: 'All payouts', level: 1 })).toBeVisible();
    await page.getByRole('button', { name: 'All', exact: true }).click();
    const table = page.getByRole('table', { name: 'All payouts' });
    await expect(table.getByRole('columnheader', { name: 'To' }).or(page.getByText('No payouts match'))).toBeVisible({
      timeout: 30_000,
    });

    await page.getByRole('link', { name: 'Instalments' }).click();
    await expect(page).toHaveURL(/\/staff\/payouts$/);
  });
});

test.describe('payments registers, for someone who reads no money', () => {
  test('an underwriter is offered neither register', async ({ page }) => {
    await page.goto('/staff/policies');
    await expectNavItemsHidden(page, 'Deposits');
    await expectRouteDenied(page, '/staff/payments');
    await expectRouteDenied(page, '/staff/payouts/all');
  });
});
