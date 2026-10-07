import { expect, test } from '@playwright/test';
import { approverPage } from './approverSession';
import { lockPeriod, startClosing, untouchedPeriod } from './periods';

/**
 * IFRS 17 I6 through the real stack, the year-end close: in a year of its own, finance posts December's payroll as a
 * manual journal (approved by a second person), starts closing December and records that it has no expense allocation;
 * the year-end page shows the loss and the journal that closes it -- 8110 to 3310, on to retained earnings 3210 -- and
 * finance prepares the close; the finance approver posts it; December locks.
 */

test.describe('IFRS 17 year-end close', () => {
  test.use({ storageState: 'e2e/.auth/staff-finance.json' });

  test('a year with payroll closed to retained earnings by two people, and December locked', async ({ page, browser }) => {
    const december = await untouchedPeriod(page, '12');
    const year = december.slice(0, 4);

    // December's payroll, a manual journal through two people.
    await page.goto('/staff/manual-journals/new');
    await page.getByLabel('Start from a template').selectOption({ label: 'O-01 — Payroll' });
    await page.getByLabel('Title').fill(`E2E payroll ${december}`);
    await page.getByLabel('Period').fill(december);
    await page.getByLabel('Reason', { exact: true }).fill('Payroll summary from HR (e2e year-end)');
    await page.getByLabel('Reason code').selectOption('OTHER');
    await page.getByLabel('Line 1 amount').fill('1000.00');
    await page.getByLabel('Line 2 amount').fill('100.00');
    await page.getByLabel('Line 3 amount').fill('100.00');
    await page.getByLabel('Line 4 amount').fill('800.00');
    await page.getByRole('button', { name: 'Save draft' }).click();
    await page.getByLabel('Document file').setInputFiles({
      name: 'payroll.pdf',
      mimeType: 'application/pdf',
      buffer: Buffer.from('%PDF-1.4\n% e2e year-end payroll\n'),
    });
    await expect(page.getByRole('region', { name: 'Documents' }).locator('li')).toHaveCount(1, { timeout: 15_000 });
    await page.getByRole('button', { name: 'Submit for approval' }).click();
    await expect(page.getByText('A finance approver other than you will approve or reject it.')).toBeVisible();
    const journalUrl = page.url();
    const first = await approverPage(browser);
    await first.page.goto(journalUrl);
    await first.page.getByRole('button', { name: 'Approve and post' }).click();
    await expect(first.page.getByText(/Posted · period/)).toBeVisible({ timeout: 15_000 });
    await first.context.close();

    // December closing, with no expense allocation (step 5) -- also two people.
    await page.goto('/staff/periods');
    await page.getByLabel('Another period (YYYY-MM)').fill(december);
    await page.getByRole('button', { name: 'Show', exact: true }).click();
    await startClosing(page, december);
    await page.goto(`/staff/ifrs17-engine?period=${december}`);
    await page.getByLabel('No allocation this month').check();
    await page.getByLabel('Reason there is none').fill('E2E: nothing attributable this month');
    await page.getByRole('button', { name: 'Record no allocation' }).click();
    await page.getByRole('table', { name: 'Expense allocations' }).getByRole('link').first().click({ timeout: 15_000 });
    const allocationUrl = page.url();
    const second = await approverPage(browser);
    await second.page.goto(allocationUrl);
    await second.page.getByRole('button', { name: 'Approve and post' }).click();
    await expect(second.page.getByText(/^Posted · total/)).toBeVisible({ timeout: 15_000 });
    await second.context.close();

    // The year: a loss of the payroll, closed through 3310 to retained earnings.
    await page.goto(`/staff/year-end?year=${year}`);
    await expect(page.getByText('Loss 1,000.00').first()).toBeVisible({ timeout: 15_000 });
    const journal = page.getByRole('table', { name: 'Closing journal' });
    await expect(journal).toContainText('8110');
    await expect(journal).toContainText('3310');
    await expect(journal).toContainText('3210');
    await page.getByRole('button', { name: 'Prepare close' }).click();
    await page.getByRole('link', { name: `Close of ${year} · Awaiting approval` }).click({ timeout: 15_000 });
    await expect(page.getByText('A finance approver other than you will approve or reject it.')).toBeVisible({ timeout: 15_000 });
    const closeUrl = page.url();

    const third = await approverPage(browser);
    await third.page.goto(closeUrl);
    await third.page.getByRole('button', { name: 'Approve and post' }).click();
    await expect(third.page.getByText(/^Posted · Loss 1,000.00/)).toBeVisible({ timeout: 15_000 });
    await third.context.close();

    await lockPeriod(page, december);
  });
});
