import { expect, test } from '@playwright/test';
import { asAdmin } from './admin';
import { issueRealPolicy } from './policies';

/**
 * Manual receipting through the real stack (2026-10-27): a premium paid by cheque is recorded by one finance officer
 * against the policy's invoice, refused to the recorder, and confirmed by a second finance person -- after which the
 * invoice reads Paid. The ledger's side (1120, not 1140) is pinned by PremiumPostingEndToEndTest.
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

    // From the invoice, where the money is owed: Request payment, then how it is being paid.
    await page.goto(`/staff/policies/${policyNumber}?tab=billing`);
    await page.getByRole('button', { name: 'Request payment' }).first().click({ timeout: 30_000 });
    await page.getByLabel('How is it being paid?').selectOption('CHEQUE');
    await page.getByLabel('Cheque number').fill(cheque);
    await expect(page.getByLabel(/^Amount/)).toHaveValue('800.00');
    await page.getByLabel('Paid by (optional)').fill('Amina Owner');
    await page.getByRole('button', { name: 'Record payment received' }).click();
    // Still on the policy: it says what happens next and offers the receipt.
    await expect(page.getByText(/It pays this invoice once a second finance person confirms it/)).toBeVisible({
      timeout: 15_000,
    });
    await expect(page).toHaveURL(new RegExp(`/staff/policies/${policyNumber}`));
    await page.getByRole('link', { name: 'Open the receipt' }).click();

    // Recorded, and waiting: the recorder is told someone else confirms it.
    await expect(page).toHaveURL(/\/staff\/receipts\/[0-9a-f-]{36}$/, { timeout: 15_000 });
    await expect(page.getByRole('heading', { name: 'Cheque receipt' })).toBeVisible();
    // Its way back is the policy it pays, where it was recorded -- not the payments register.
    await expect(
      page.getByRole('navigation', { name: 'Breadcrumb' }).getByRole('link', { name: policyNumber }),
    ).toBeVisible();
    await expect(page.getByText('You recorded this receipt, so someone else must confirm it.')).toBeVisible();
    await expect(page.getByRole('button', { name: 'Confirm', exact: true })).toBeDisabled();
    const receiptUrl = page.url();

    // A second finance person confirms it.
    const second = await browser.newContext({ storageState: 'e2e/.auth/staff-finance-approver.json' });
    const approver = await second.newPage();
    await approver.goto(receiptUrl);
    await approver.getByRole('button', { name: 'Confirm', exact: true }).click({ timeout: 30_000 });
    await approver.getByRole('button', { name: 'Confirm receipt' }).click();
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
