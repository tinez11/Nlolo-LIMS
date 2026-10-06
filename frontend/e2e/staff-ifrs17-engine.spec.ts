import { expect, test, type Page } from '@playwright/test';
import { approverPage } from './approverSession';
import { xlsx } from './xlsx';

/**
 * IFRS 17 I5a through the real stack, month-end steps 6 and 7: finance starts closing a month and makes the engine's
 * extract; the engine's results come back in the template and are uploaded; a finance approver who did not upload them
 * approves with the appointed actuary's sign-off and report, the run is posted through 9160, the ledger agrees with the
 * engine group by group, and the month locks.
 *
 * The month is an untouched one long before dev's postings (they start in 2026), chosen at random so each run has its
 * own, and locked at the end: a closing month with postings would otherwise stop dev's real months from locking.
 */

async function untouchedPeriod(page: Page): Promise<string> {
  await page.goto('/staff/periods');
  await expect(page.getByRole('list', { name: 'Accounting periods' })).toBeVisible({ timeout: 15_000 });
  for (let attempt = 0; attempt < 10; attempt++) {
    const n = Math.floor(Math.random() * 240);
    const period = `${2000 + Math.floor(n / 12)}-${`${(n % 12) + 1}`.padStart(2, '0')}`;
    await page.getByLabel('Another period (YYYY-MM)').fill(period);
    await page.getByRole('button', { name: 'Show', exact: true }).click();
    const row = page.getByRole('listitem', { name: `Period ${period}` });
    await expect(row).toBeVisible();
    if (await row.getByRole('button', { name: 'Start closing' }).isVisible()) return period;
  }
  throw new Error('No untouched period found in ten tries');
}

const lastDay = (period: string) => {
  const [y, m] = period.split('-').map(Number);
  return new Date(Date.UTC(y, m, 0)).toISOString().slice(0, 10);
};

test.describe('IFRS 17 engine period cycle', () => {
  test.use({ storageState: 'e2e/.auth/staff-finance.json' });

  test('extract, results uploaded, approved with the sign-off, posted and agreed, period locked', async ({ page, browser }) => {
    const period = await untouchedPeriod(page);
    const row = page.getByRole('listitem', { name: `Period ${period}` });
    await row.getByRole('button', { name: 'Start closing' }).click();
    await row.getByRole('button', { name: 'Start closing' }).click();
    await expect(row).toContainText('Closing since', { timeout: 15_000 });

    // Step 6: the extract, and the groups it names.
    await page.goto(`/staff/ifrs17-engine?period=${period}`);
    await page.getByRole('button', { name: 'Create extract' }).click();
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
    await page.goto('/staff/periods');
    await page.getByLabel('Another period (YYYY-MM)').fill(period);
    await page.getByRole('button', { name: 'Show', exact: true }).click();
    await row.getByRole('button', { name: 'Lock period' }).click();
    await row.getByRole('button', { name: 'Lock period' }).click();
    await expect(row).toContainText('Locked by', { timeout: 15_000 });
  });
});
