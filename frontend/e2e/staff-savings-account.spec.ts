import { expect, test, type Browser } from '@playwright/test';
import { asAdmin } from './admin';
import { dmy } from './dates';
import { caseAwaitingManualIssue, SAVINGS_PRODUCT, selectUnderwritingCase } from './underwriting';

/**
 * A savings account, through the real stack (product step 3).
 *
 * The fixture is a SAVE-PLAN-01 policy commenced on the 1st of the month three months ago, so the
 * month-end drain -- every ten seconds under the `local` profile, hourly elsewhere -- has completed
 * months to post the moment money arrives. Nothing here sets a balance directly: every figure on the
 * screen arrived through a top-up collected by the payment rail, exactly as a customer's would.
 */

function firstOfMonthThreeMonthsAgo(): string {
  const date = new Date();
  date.setDate(1);
  date.setMonth(date.getMonth() - 3);
  const month = String(date.getMonth() + 1).padStart(2, '0');
  return `${date.getFullYear()}-${month}-01`;
}

/** An in-force savings policy, issued as admin, as every other fixture in this suite is. */
async function savingsPolicy(browser: Browser): Promise<string> {
  return asAdmin(browser, async (page) => {
    const caseId = await caseAwaitingManualIssue(page, '2000000.00', SAVINGS_PRODUCT);
    await page.goto('/staff/policies/new');
    await selectUnderwritingCase(page, caseId);
    await expect(page.getByText('Resolving product version…')).not.toBeVisible();
    await page.getByLabel('Sum assured').fill('2000000.00');
    await page.getByLabel('Premium', { exact: true }).fill('50000.00');
    // MIGRATION so the policy is in force on arrival -- see e2e/policies.ts.
    await page.getByLabel('Why is this being issued by hand?').selectOption('MIGRATION');
    await page.getByLabel('Reason for manual issue').fill('E2E fixture: savings account');
    await page.getByLabel('Commencement date').fill(dmy(firstOfMonthThreeMonthsAgo()));
    await page.getByLabel('Policy term (months)').fill('180');
    await page.getByRole('button', { name: 'Issue policy' }).click();
    // 60s: issuance answers only after the whole AFTER_COMMIT chain -- see e2e/policies.ts.
    await expect(page).toHaveURL(/\/staff\/policies\/POL-[A-Z0-9]+$/, { timeout: 60_000 });
    return page.url().split('/').pop() as string;
  });
}

test.describe('a savings account', () => {
  test.use({ storageState: 'e2e/.auth/staff-finance.json' });
  // An underwriting case, a second underwriter's decision and a manual issue, then a collection
  // through the rail and a month-end drain, then a second person in their own context. None of
  // that is the behaviour under test; it is the cost of reaching it.
  test.setTimeout(240_000);

  test('is funded by a top-up, withdrawn from with two people, and files a statement', async ({ page, browser }) => {
    const policyNumber = await savingsPolicy(browser);

    await page.goto(`/staff/policies/${policyNumber}`);
    await page.getByRole('tab', { name: 'Account' }).click();
    await expect(page.getByText('Nothing on the ledger yet')).toBeVisible({ timeout: 20_000 });

    // Money in: a top-up, collected by the mock rail and credited on confirmation.
    await page.getByRole('button', { name: 'Request top-up' }).click();
    await page.getByLabel('Amount').fill('100000.00');
    await page.getByLabel('Collect from').fill('+255700000002');
    await page.getByRole('button', { name: 'Request top-up', exact: true }).last().click();
    await expect(page.getByText(/collection has been requested from the provider/)).toBeVisible({ timeout: 20_000 });

    // Credited once payment confirms it -- a poll, not an assumption about timing. NO policy fee is
    // expected for the three past months: the month-end drain (every ten seconds here) has already
    // passed over them while the account held nothing, and a month on an account never paid into
    // posts nothing by design. Month-end interest and fees are proven in MonthEndIntegrationTest;
    // what this proves is the money path through the real rail.
    const ledger = page.getByRole('list', { name: 'Ledger' });
    await expect(async () => {
      await page.reload();
      await page.getByRole('tab', { name: 'Account' }).click();
      await expect(ledger.getByText('Top-up', { exact: true })).toBeVisible();
      await expect(ledger.getByText('Allocation charge', { exact: true })).toBeVisible();
    }).toPass({ timeout: 60_000 });
    // 100,000 in, 5% held back in policy year 1.
    await expect(page.getByText('TZS 95,000.00').first()).toBeVisible();
    // No balance on the ledger ever goes below zero -- the database refuses one.
    for (const text of await ledger.getByText(/^balance /).allTextContents()) {
      expect(text).not.toMatch(/-/);
    }

    // Money out: requested by this finance officer, approved by someone else.
    await page.getByRole('button', { name: 'Request withdrawal' }).click();
    await page.getByLabel('Amount').fill('10000.00');
    await page.getByLabel('Pay to').fill('+255700000009');
    await page.getByRole('button', { name: 'Continue' }).click();
    await page.getByRole('button', { name: 'Request withdrawal', exact: true }).last().click();

    // The requester cannot approve: the gate says so in the server's words and the button is off.
    await expect(
      page.getByText('A withdrawal must be approved by someone other than the person who requested it'),
    ).toBeVisible({ timeout: 20_000 });
    await expect(page.getByRole('button', { name: 'Approve', exact: true })).toBeDisabled();

    const policyUrl = page.url();
    await asAdmin(browser, async (adminPage) => {
      await adminPage.goto(policyUrl);
      await adminPage.getByRole('tab', { name: 'Account' }).click();
      await adminPage.getByRole('button', { name: 'Approve', exact: true }).click();
      await adminPage.getByRole('button', { name: /^Pay TZS/ }).click();
      // REQUESTED from the rail, not paid -- and the page must not claim otherwise.
      await expect(adminPage.getByText('The payment has been requested from the provider.')).toBeVisible({
        timeout: 20_000,
      });
    });

    // A statement for this year, filed as a PDF and listed.
    const year = new Date().getFullYear();
    await page.reload();
    await page.getByRole('tab', { name: 'Account' }).click();
    const fromField = page.getByLabel('From');
    await fromField.fill(dmy(`${year}-01-01`));
    await page.getByLabel('To').fill(dmy(`${year}-12-31`));
    await page.getByRole('button', { name: 'Show statement' }).click();
    await expect(page.getByText(/^Closing balance/)).toBeVisible({ timeout: 20_000 });
    await page.getByRole('button', { name: 'Generate PDF' }).click();
    await expect(page.getByText('Filed as a PDF. It is listed below.')).toBeVisible({ timeout: 30_000 });
    const popup = page.waitForEvent('popup');
    await page.getByRole('button', { name: 'Download' }).first().click();
    await popup;
  });
});

test.describe('an underwriter on a savings account', () => {
  // The default identity is staff.underwriter: may read the account, may not put money on it.
  test.setTimeout(180_000);

  test('sees the account but is not offered a transfer in or an adjustment', async ({ page, browser }) => {
    const policyNumber = await savingsPolicy(browser);
    await page.goto(`/staff/policies/${policyNumber}`);
    await page.getByRole('tab', { name: 'Account' }).click();
    await expect(page.getByRole('button', { name: 'Request withdrawal' })).toBeVisible({ timeout: 20_000 });
    await expect(page.getByRole('button', { name: 'Record transfer in' })).toHaveCount(0);
    await expect(page.getByRole('button', { name: 'Propose adjustment' })).toHaveCount(0);
  });
});
