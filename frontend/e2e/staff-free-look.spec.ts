import { expect, test } from '@playwright/test';
import { asAdmin } from './admin';
import { issueRealPolicy } from './policies';

/**
 * The customer changed their mind inside the free-look window (guide §21.3).
 *
 * Not a surrender, and the spec asserts the difference: the policy ends up CANCELLED_FREE_LOOK —
 * treated as never having been bought — rather than SURRENDERED, and what goes back is the
 * premiums less what the insurer actually spent.
 *
 * The fixture is a `DEMO-TERM-01` policy issued today, whose seeded version carries a 15-day
 * window, so it is inside it by construction. Free-look is an INDIVIDUAL buyer's right and
 * TERM_LIFE is one — a group or credit-life scheme is refused outright.
 */
test.describe('free-look cancellation', () => {
  test('is prepared by one person and released by another', async ({ page, browser }) => {
    // Preparing is open to ANY staff member: it commits nothing. The default identity here is
    // staff.underwriter, which is the point — money only moves at approval.
    const policyNumber = await issueRealPolicy(page, 'E2E fixture: free-look cancellation');

    await page.goto(`/staff/policies/${policyNumber}`);
    await expect(page.getByRole('heading', { name: 'Changed their mind' })).toBeVisible();

    await page.getByLabel('Refund to').fill('+255700000009');
    await page.getByRole('button', { name: 'Add a deduction' }).click();
    await page.getByLabel('What for').fill('Medical examination');
    await page.getByLabel('Amount').fill('8000.00');
    await page.getByRole('button', { name: 'Cancel in free-look' }).click();
    await page.getByRole('button', { name: 'Request cancellation' }).click();

    // The server's figures, read back -- the console computes no refund of its own.
    await expect(page.getByText('Less Medical examination')).toBeVisible({ timeout: 20_000 });
    await expect(page.getByText('Requested', { exact: true })).toBeVisible();
    // Preparing changed nothing about the contract.
    await expect(page.getByText('Active', { exact: true }).first()).toBeVisible();
    // An underwriter prepared it and is told who releases it, rather than shown a button that
    // would 403.
    await expect(page.getByText(/Awaiting approval by a finance officer/)).toBeVisible();

    await asAdmin(browser, async (adminPage) => {
      await adminPage.goto(`/staff/policies/${policyNumber}`);
      await adminPage.getByRole('button', { name: 'Approve cancellation' }).click();
      await adminPage.getByRole('button', { name: /^Refund TZS/ }).click();

      // Void from inception, and the refund REQUESTED rather than paid.
      await expect(adminPage.getByText('Refund requested')).toBeVisible({ timeout: 20_000 });
      await expect(
        adminPage.getByText(/Cover is void from inception and the refund has been requested, not paid/),
      ).toBeVisible();
      await expect(adminPage.getByText('Cancelled free look').first()).toBeVisible();
    });
  });

  test('a deduction larger than the premiums is refused by the server, not silently accepted', async ({
    page,
  }) => {
    const policyNumber = await issueRealPolicy(page, 'E2E fixture: free-look over-deduction');

    await page.goto(`/staff/policies/${policyNumber}`);
    await page.getByLabel('Refund to').fill('+255700000009');
    await page.getByRole('button', { name: 'Add a deduction' }).click();
    await page.getByLabel('What for').fill('Implausible cost');
    await page.getByLabel('Amount').fill('99999999.00');
    await page.getByRole('button', { name: 'Cancel in free-look' }).click();
    await page.getByRole('button', { name: 'Request cancellation' }).click();

    // A refund can be nothing; it can never be a BILL. The message is the server's own.
    await expect(page.getByText(/exceed the .* premiums collected/)).toBeVisible({ timeout: 20_000 });
  });
});
