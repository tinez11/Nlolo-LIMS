import { expect, test } from '@playwright/test';
import { expectNavItemsHidden, expectRouteDenied } from './guards';

/**
 * The payments register (2026-10-27) through the real stack: money in and out as one list, opening on what needs
 * attention. Rows and filters are pinned by PaymentsPage.test and PaymentContractTest against seeded ledgers; this
 * proves the page, its filters on the URL, and who is offered it, with real logins.
 */
test.describe('payments register', () => {
  test.use({ storageState: 'e2e/.auth/staff-finance.json' });

  test('finance opens on the payments that need attention, and can widen to every payment', async ({ page }) => {
    await page.goto('/staff/dashboard');
    await page.getByRole('link', { name: /^Payments/ }).click();
    await expect(page.getByRole('heading', { name: 'Payments', level: 1 })).toBeVisible();
    await expect(page.getByRole('button', { name: 'Needs attention' })).toHaveAttribute('aria-pressed', 'true');

    await page.getByRole('button', { name: 'All', exact: true }).click();
    await expect(page).toHaveURL(/state=all/);
    // Every payment the dev stack ever made: there are always some, and each says which way it went.
    await expect(page.getByRole('table', { name: 'Payments' }).getByText(/^(In|Out) · /).first()).toBeVisible({
      timeout: 30_000,
    });
  });
});

test.describe('payments register, for someone who reads no money', () => {
  test('an underwriter is offered no payments register', async ({ page }) => {
    await page.goto('/staff/policies');
    await expectNavItemsHidden(page, 'Payments');
    await expectRouteDenied(page, '/staff/payments');
  });
});
