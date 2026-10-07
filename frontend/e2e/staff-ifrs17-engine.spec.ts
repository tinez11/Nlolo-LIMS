import { expect, test } from '@playwright/test';
import { approverPage } from './approverSession';
import { lockPeriod, startClosing, untouchedPeriod } from './periods';
import { xlsx } from './xlsx';

/**
 * IFRS 17 I5a through the real stack, month-end steps 6 and 7: finance starts closing a month, records that it has no
 * expense allocation (step 5, I5b -- approved by a second person) and makes the engine's extract; the engine's results
 * come back in the template and are uploaded; a finance approver who did not upload them approves with the appointed
 * actuary's sign-off and report, the run is posted through 9160, the ledger agrees with the engine group by group, and
 * the month locks. The month is its own (see periods.ts).
 */

const lastDay = (period: string) => {
  const [y, m] = period.split('-').map(Number);
  return new Date(Date.UTC(y, m, 0)).toISOString().slice(0, 10);
};

test.describe('IFRS 17 engine period cycle', () => {
  test.use({ storageState: 'e2e/.auth/staff-finance.json' });

  test('extract, results uploaded, approved with the sign-off, posted and agreed, period locked', async ({ page, browser }) => {
    const period = await untouchedPeriod(page);
    await startClosing(page, period);

    // Step 5: nothing to allocate this month -- which a second person approves too.
    await page.goto(`/staff/ifrs17-engine?period=${period}`);
    await expect(page.getByRole('button', { name: 'Create extract' })).toBeDisabled({ timeout: 15_000 });
    await page.getByText('No allocation this month').click();
    await page.getByLabel('Reason there is none').fill('E2E: nothing attributable this month');
    await page.getByRole('button', { name: 'Record no allocation' }).click();
    await page.getByRole('table', { name: 'Expense allocations' }).getByRole('link').first().click({ timeout: 15_000 });
    await expect(page.getByText('A finance approver other than you will approve or reject it.')).toBeVisible({ timeout: 15_000 });
    const allocationUrl = page.url();
    const { page: allocationApprover, context: allocationContext } = await approverPage(browser);
    await allocationApprover.goto(allocationUrl);
    await allocationApprover.getByRole('button', { name: 'Approve and post' }).click();
    await expect(allocationApprover.getByText(/^Posted · total/)).toBeVisible({ timeout: 15_000 });
    await allocationContext.close();

    // Step 6: the extract, and the groups it names.
    await page.goto(`/staff/ifrs17-engine?period=${period}`);
    await page.getByRole('button', { name: 'Create extract' }).click({ timeout: 15_000 });
    const extracts = page.getByRole('table', { name: 'Extracts' });
    await expect(extracts).toContainText(`${period} #1`, { timeout: 30_000 });
    await extracts.getByText(/ groups · /).click();
    const groups = await page.getByRole('list', { name: `Groups of ${period} #1` }).getByRole('listitem').allTextContents();
    expect(groups.length, 'the extract names the in-force policies’ groups').toBeGreaterThan(0);
    const released = groups.find((g) => g.includes('-GMM-')) ?? groups.find((g) => !g.startsWith('RI-'))!;

    // Step 7: the engine's results, as the actuary fills the template. Nothing was booked in the month, so every
    // closing figure is zero but the CSM the run releases into the one group.
    const reference = `E2E-${period}-${Date.now()}`;
    const results = xlsx([
      {
        name: 'Header',
        rows: [['Period', period], ['Extract', 1], ['Engine reference', reference], ['Engine', 'Prophet 9'],
          ['Measurement date', lastDay(period)]],
      },
      {
        name: 'Journal',
        rows: [['group', 'entry', 'account', 'side', 'amount', 'movement', 'note'],
          [released, 'P-08', '2112', 'DR', '1000.00', 'CSM_REL', 'e2e'],
          [released, 'P-08', '4130', 'CR', '1000.00', 'CSM_REL', 'e2e']],
      },
      {
        name: 'Closing',
        rows: [['group', 'lrc', 'lic', 'csm', 'arc', 'aic', 'ri_csm'],
          ...groups.map((g) => g.startsWith('RI-')
            ? [g, null, null, null, '0', '0', '0']
            : [g, g === released ? '1000.00' : '0', '0', g === released ? '1000.00' : '0', null, null, null])],
      },
    ]);
    await page.getByLabel('Results file').setInputFiles({
      name: 'engine-results.xlsx',
      mimeType: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',
      buffer: results,
    });
    await page.getByRole('table', { name: 'Engine runs' }).getByRole('link', { name: reference }).click({ timeout: 30_000 });
    await expect(page.getByText('A finance approver other than you will approve or reject it.')).toBeVisible({ timeout: 15_000 });
    await expect(page.getByRole('button', { name: 'Approve and post' })).toHaveCount(0);
    const url = page.url();

    const { page: approver, context: approverContext } = await approverPage(browser);
    await approver.goto(url);
    await approver.getByLabel('Sign-off reference').fill('AS-E2E (appointed actuary)');
    await approver.getByLabel("Actuary's report").setInputFiles({
      name: 'actuary-report.pdf',
      mimeType: 'application/pdf',
      buffer: Buffer.from('%PDF-1.4\n% e2e actuarial report\n'),
    });
    await approver.getByRole('button', { name: 'Approve and post' }).click();
    await expect(approver.getByText(/^Posted · period/)).toBeVisible({ timeout: 30_000 });
    const reconciliation = approver.getByRole('table', { name: 'Reconciliation' });
    await expect(reconciliation.getByText('Agreed', { exact: true })).toHaveCount(groups.length * 3);
    await expect(reconciliation).not.toContainText('Differs by');
    await approverContext.close();

    // Agreed everywhere and 9160 back at zero: the month locks.
    await lockPeriod(page, period);
  });
});
