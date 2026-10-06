import { expect, test } from '@playwright/test';
import { approverPage } from './approverSession';

/**
 * IFRS 17 I4 through the real stack: finance prepares a manual journal from the guide's payroll template, attaches
 * its document and submits it; a second person holding the finance-approver role approves it, and only then is it
 * posted -- a journal entry the ledger shows.
 */

test.describe('IFRS 17 manual journals', () => {
  test.use({ storageState: 'e2e/.auth/staff-finance.json' });

  test('prepared by finance from a template, approved and posted by a finance approver', async ({ page, browser }) => {
    await page.goto('/staff/manual-journals/new');
    await page.getByLabel('Start from a template').selectOption({ label: 'O-01 — Payroll' });
    await expect(page.getByLabel('Line 1 account')).toHaveValue('8110');
    const title = `E2E payroll ${Date.now()}`;
    await page.getByLabel('Title').fill(title);
    await page.getByLabel('Reason', { exact: true }).fill('Payroll summary from HR (e2e)');
    await page.getByLabel('Reason code').selectOption('OTHER');
    await page.getByLabel('Line 1 amount').fill('1000.00');
    await page.getByLabel('Line 2 amount').fill('100.00');
    await page.getByLabel('Line 3 amount').fill('100.00');
    await page.getByLabel('Line 4 amount').fill('800.00');
    await page.getByRole('button', { name: 'Save draft' }).click();

    await expect(page.getByRole('heading', { name: title })).toBeVisible({ timeout: 15_000 });
    await page.getByLabel('Document file').setInputFiles({
      name: 'payroll.pdf',
      mimeType: 'application/pdf',
      buffer: Buffer.from('%PDF-1.4\n% e2e payroll summary\n'),
    });
    await expect(page.getByRole('region', { name: 'Documents' }).locator('li')).toHaveCount(1, { timeout: 15_000 });
    await page.getByRole('button', { name: 'Submit for approval' }).click();
    await expect(page.getByText('A finance approver other than you will approve or reject it.')).toBeVisible();
    await expect(page.getByRole('button', { name: 'Approve and post' })).toHaveCount(0);
    const url = page.url();

    const { page: approver, context: approverContext } = await approverPage(browser);
    await approver.goto(url);
    await approver.getByRole('button', { name: 'Approve and post' }).click();
    await expect(approver.getByText(/Posted · period/)).toBeVisible({ timeout: 15_000 });
    await expect(approver.getByRole('link', { name: 'journal entry' })).toBeVisible();
    await approverContext.close();
  });

  test('the guide library shows which entries the system posts instead', async ({ page }) => {
    await page.goto('/staff/journal-templates');
    const guide = page.getByRole('table', { name: "The guide's manual entries" });
    await expect(guide).toContainText('Shares issued and fully paid');
    await expect(guide).toContainText('Posted by the system');
  });
});
