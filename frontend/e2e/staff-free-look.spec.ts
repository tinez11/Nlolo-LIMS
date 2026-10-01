import { expect, test, type Browser, type Page } from '@playwright/test';
import { asAdmin } from './admin';
import { dmy } from './dates';
import { caseAwaitingManualIssue, MONEY_BACK_PRODUCT, selectUnderwritingCase } from './underwriting';

/**
 * The customer changed their mind inside the free-look window (guide §21.3).
 *
 * Not a surrender, and the spec asserts the difference: the policy ends up CANCELLED_FREE_LOOK —
 * treated as never having been bought — rather than SURRENDERED.
 *
 * **Issued on END-MB-20, not the term product.** Free-look needs the version to carry a window,
 * and only the seeded money-back endowment does: DEMO-TERM-01's active version on a long-lived dev
 * database predates payout terms entirely, and the server answers "product version has no
 * free-look period". A product that has never been republished is the normal case, not an edge one.
 *
 * **No deductions here, and that is not laziness.** A MIGRATION-issued fixture has collected no
 * premium, so `premiumsCollected` is zero and ANY deduction exceeds it — the server is right to
 * refuse that, and the second test below proves it does. Itemised deductions against a real
 * premium are covered where a premium can actually be collected: `FreeLookIntegrationTest` (the
 * 42,000 refund after an 8,000 deduction) and `BenefitPayoutContractTest` over HTTP.
 */

/** A fresh ACTIVE money-back policy. A term is REQUIRED: the product pays at the end of its term,
 *  and the payout engine refuses to expand a schedule it cannot date. */
async function activeMoneyBackPolicy(browser: Browser, reason: string): Promise<string> {
  return asAdmin(browser, async (page) => {
    const caseId = await caseAwaitingManualIssue(page, '2000000.00', MONEY_BACK_PRODUCT);
    await page.goto('/staff/policies/new');
    await selectUnderwritingCase(page, caseId);
    await expect(page.getByText('Resolving product version…')).not.toBeVisible();
    await page.getByLabel('Sum assured').fill('2000000.00');
    await page.getByLabel('Premium', { exact: true }).fill('5000.00');
    await page.getByLabel('Why is this being issued by hand?').selectOption('MIGRATION');
    await page.getByLabel('Reason for manual issue').fill(reason);
    await page.getByLabel('Commencement date').fill(dmy(new Date().toISOString().slice(0, 10)));
    await page.getByLabel('Policy term (months)').fill('240');
    await page.getByRole('button', { name: 'Issue policy' }).click();
    await expect(page).toHaveURL(/\/staff\/policies\/POL-[A-Z0-9]+$/, { timeout: 15_000 });
    return page.url().split('/').pop() as string;
  });
}

async function openFreeLookPanel(page: Page, policyNumber: string): Promise<void> {
  await page.goto(`/staff/policies/${policyNumber}`);
  await expect(page.getByRole('heading', { name: 'Changed their mind' })).toBeVisible();
}

test.describe('free-look cancellation', () => {
  // The fixture builds an underwriting case, a second underwriter's decision and a manual issue
  // before the behaviour under test even starts, and the release runs in its own browser context.
  test.setTimeout(180_000);

  test('is prepared by one person and released by another', async ({ page, browser }) => {
    const policyNumber = await activeMoneyBackPolicy(browser, 'E2E fixture: free-look cancellation');

    // Preparing is open to ANY staff member — it commits nothing. The identity here is
    // staff.underwriter, which is the point: money only moves at approval.
    await openFreeLookPanel(page, policyNumber);
    await page.getByLabel('Refund to').fill('+255700000009');
    await page.getByRole('button', { name: 'Cancel in free-look' }).click();
    await page.getByRole('button', { name: 'Request cancellation' }).click();

    // The server's figures, read back — the console computes no refund of its own.
    await expect(page.getByText('Premiums collected')).toBeVisible({ timeout: 20_000 });
    // Preparing changed nothing about the contract.
    await expect(page.getByText('Active', { exact: true }).first()).toBeVisible();
    // An underwriter is told who releases it, rather than shown a button that would 403.
    await expect(page.getByText(/Awaiting approval by a finance officer/)).toBeVisible();

    await asAdmin(browser, async (adminPage) => {
      await adminPage.goto(`/staff/policies/${policyNumber}`);
      await adminPage.getByRole('button', { name: 'Approve cancellation' }).click();
      await adminPage.getByRole('button', { name: /^Refund TZS/ }).click();

      // Void from inception. The badge is the humanised literal StatusBadge produces.
      await expect(adminPage.getByText('Cancelled free look').first()).toBeVisible({ timeout: 20_000 });
      await expect(adminPage.getByText('Refund requested')).toBeVisible();
    });
  });

  test('a deduction larger than the premiums is refused by the server, not silently accepted', async ({
    page,
    browser,
  }) => {
    const policyNumber = await activeMoneyBackPolicy(browser, 'E2E fixture: free-look over-deduction');

    await openFreeLookPanel(page, policyNumber);
    await page.getByLabel('Refund to').fill('+255700000009');
    await page.getByRole('button', { name: 'Add a deduction' }).click();
    await page.getByLabel('What for').fill('Medical examination');
    // Nothing has been collected on a migrated fixture, so even a modest cost exceeds it. A refund
    // can be nothing; it can never be a bill.
    await page.getByLabel('Amount').fill('8000.00');
    await page.getByRole('button', { name: 'Cancel in free-look' }).click();
    await page.getByRole('button', { name: 'Request cancellation' }).click();

    await expect(page.getByText(/exceed the .* premiums collected/)).toBeVisible({ timeout: 20_000 });
  });
});
