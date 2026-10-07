import { expect, test } from '@playwright/test';
import { approverPage } from './approverSession';
import { lockPeriod, startClosing, untouchedPeriod } from './periods';

/**
 * IFRS 17 I5b through the real stack, month-end step 5 (P-19): finance types the expense study's three totals for a
 * closing month and sees them split over the groups before preparing them; the month booked no expenses, so the total
 * is above its pool and the finance approver -- who did not prepare it -- must approve above the pool. It posts one
 * journal, Dr 5210 and the rest per group, Cr 8490, and the month locks. The month is its own (see periods.ts).
 */

test.describe('IFRS 17 expense allocation', () => {
  test.use({ storageState: 'e2e/.auth/staff-finance.json' });

  test('three totals split by driver, approved above the pool by a second person, posted, month locked', async ({
    page,
    browser,
  }) => {
    const period = await untouchedPeriod(page);
    await startClosing(page, period);

    await page.goto(`/staff/ifrs17-engine?period=${period}`);
    const section = page.getByRole('region', { name: 'Expense allocation' });
    await section.getByLabel('Maintenance').fill('1000.00');
    await section.getByLabel('Claims handling').fill('200.00');
    await section.getByLabel('Acquisition').fill('300.00');
    await section.getByLabel('Study reference').fill('E2E expense study');
    await expect(section.getByRole('table', { name: 'Allocation preview' })).toBeVisible({ timeout: 15_000 });
    await expect(section.getByText(/above the month's pool/)).toBeVisible();
    await section.getByRole('button', { name: 'Prepare allocation' }).click();

    const link = section.getByRole('table', { name: 'Expense allocations' }).getByRole('link', { name: `${period} · 1,500.00` });
    await link.click({ timeout: 15_000 });
    await expect(page.getByText('A finance approver other than you will approve or reject it.')).toBeVisible({ timeout: 15_000 });
    await expect(page.getByRole('button', { name: 'Approve and post' })).toHaveCount(0);
    const url = page.url();

    const { page: approver, context } = await approverPage(browser);
    await approver.goto(url);
    await expect(approver.getByRole('button', { name: 'Approve and post' })).toBeDisabled({ timeout: 15_000 });
    await approver.getByText('Approve above the pool').click();
    await approver.getByRole('button', { name: 'Approve and post' }).click();
    await expect(approver.getByText(/^Posted · total 1,500.00/)).toBeVisible({ timeout: 15_000 });
    const lines = approver.getByRole('table', { name: 'Allocation lines' });
    await expect(lines).toContainText('5210');
    await expect(lines).toContainText('8490');
    await expect(lines).toContainText('1,500.00');
    await context.close();

    // Nothing else is open in the month and no clearing account holds anything: it locks.
    await lockPeriod(page, period);
  });
});
