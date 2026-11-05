import { expect, test } from '@playwright/test';
import { asAdmin } from './admin';
import { issueRealPolicy } from './policies';

/**
 * Manual receipting through the real stack (2026-10-27): a premium paid by cheque is recorded by one finance officer
 * as a deposit on the policy (money movement step 5), refused to the recorder, and confirmed by a second finance
 * person -- after which the invoice reads Paid. The ledger's side (1120, not 1140) is pinned by PremiumPostingEndToEndTest.
 */
test.describe('receipts', () => {
  test.use({ storageState: 'e2e/.auth/staff-finance.json' });
  test.setTimeout(240_000);

  test('a cheque is recorded by one finance officer and pays the invoice once another confirms it', async ({
    page,
    browser,
  }) => {
    const policyNumber = await asAdmin(browser, (admin) => issueRealPolicy(admin, 'E2E fixture: a premium paid by cheque'));
    const cheque = `CHQ-${Date.now()}`;

    // Money movement step 5: money received some other way is a deposit, recorded on its own page from the policy's
    // Balance panel -- the invoice list only asks a phone for payment now.
    await page.goto(`/staff/policies/${policyNumber}?tab=billing`);
    await expect(page.getByRole('heading', { name: 'Balance' })).toBeVisible({ timeout: 30_000 });
    await page.getByRole('link', { name: 'Record deposit' }).click();
    await expect(page).toHaveURL(/\/staff\/receipts\/new\?/);
    await expect(page.getByLabel('Policy number')).toHaveValue(policyNumber);
    await page.getByLabel('How the money came in').selectOption('CHEQUE');
    await page.getByLabel('Cheque number').fill(cheque);
    await page.getByLabel(/^Amount/).fill('800.00');
    await page.getByLabel('Paid by (optional)').fill('Amina Owner');
    await page.getByRole('button', { name: 'Record deposit' }).click();

    // Recorded, and waiting: the recorder is told someone else confirms it.
    await expect(page).toHaveURL(/\/staff\/receipts\/[0-9a-f-]{36}(\?|$)/, { timeout: 15_000 });
    await expect(page.getByRole('heading', { name: 'Cheque deposit' })).toBeVisible();
    // Its way back is the policy it pays, where it was recorded -- not the payments register.
    await expect(
      page.getByRole('navigation', { name: 'Breadcrumb' }).getByRole('link', { name: policyNumber }),
    ).toBeVisible();
    await expect(page.getByText('You recorded this deposit, so someone else must confirm it.')).toBeVisible();
    await expect(page.getByRole('button', { name: 'Confirm', exact: true })).toBeDisabled();
    const receiptUrl = page.url();

    // Back on the policy it shows as waiting on the Balance panel -- a deposit naming only the policy has no invoice
    // to wait on, and before this it showed nowhere but the approver's inbox.
    await page.goto(`/staff/policies/${policyNumber}?tab=billing`);
    const waiting = page.getByLabel('Deposits waiting to be confirmed');
    await expect(waiting.getByText('A deposit is waiting to be confirmed')).toBeVisible({ timeout: 30_000 });
    await expect(waiting).toContainText(cheque);
    await expect(waiting).toContainText('goes onto the balance');

    // A second finance person confirms it.
    const second = await browser.newContext({ storageState: 'e2e/.auth/staff-finance-approver.json' });
    const approver = await second.newPage();
    await approver.goto(receiptUrl);
    await approver.getByRole('button', { name: 'Confirm', exact: true }).click({ timeout: 30_000 });
    await approver.getByRole('button', { name: 'Confirm deposit' }).click();
    await expect(approver.getByRole('heading', { name: 'Confirmed' })).toBeVisible({ timeout: 15_000 });
    await second.close();

    // The invoice the cheque paid now reads Paid.
    await expect(async () => {
      await page.goto(`/staff/policies/${policyNumber}?tab=billing`);
      await expect(page.getByRole('heading', { name: 'Invoices' })).toBeVisible();
      await expect(page.getByText('Paid', { exact: true }).first()).toBeVisible({ timeout: 5_000 });
    }).toPass({ timeout: 30_000 });
  });
});
