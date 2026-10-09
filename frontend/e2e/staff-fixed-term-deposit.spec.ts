import { expect, test } from '@playwright/test';
import { asAdmin } from './admin';
import { dmy } from './dates';
import { caseAwaitingManualIssue, selectUnderwritingCase } from './underwriting';

/**
 * A fixed-term deposit through the real stack: the user's grid published as they wrote it, a deposit
 * issued as one payment of itself, its single premium collected through the mock rail, the rate it
 * earns shown FOR THE TERM, and the maturity choice recorded.
 *
 * Maturity itself is three months away, so it is DepositLifecycleIntegrationTest's: reinvested at the
 * rate then in force, paid to the number the money came from, or kept for a payee.
 */
test.describe('a fixed-term deposit', () => {
  test.use({ storageState: 'e2e/.auth/staff-admin.json' });
  // A product published, an underwriting case decided by two people, a manual issue, then a
  // collection through the rail -- none of it the behaviour under test, all of it the cost of
  // reaching it.
  test.setTimeout(300_000);

  test('is published with its grid, issued, funded, and told to reinvest at maturity', async ({ page, browser }) => {
    const code = `FTD-E2E-${Date.now()}`;
    const name = `E2E Fixed Deposit ${code}`;

    // ---- The product: an endowment valued as a fixed-term deposit, on the user's grid.
    await page.goto('/staff/products/new');
    await page.getByLabel('Product code').fill(code);
    await page.getByLabel('Product name').fill(name);
    await page.getByLabel('Category').selectOption('ENDOWMENT');
    // A deposit is a savings contract: publishing refuses it outside the DEP portfolio (2026-10-09).
    await page.getByLabel('IFRS 17 portfolio').selectOption('DEP');
    await page.getByLabel('Default currency').fill('TZS');
    await page.getByRole('button', { name: 'Create product' }).click();
    await expect(page.getByText('DRAFT')).toBeVisible();

    const ratingSection = page.locator('p', { hasText: 'Rating table -- must cover' }).locator('..');
    await ratingSection.getByLabel('Rating factor 1 band').fill('18-80');
    await ratingSection.getByLabel('Rating factor 1 from age').fill('18');
    await ratingSection.getByLabel('Rating factor 1 to age').fill('80');
    await ratingSection.getByLabel('Rating factor 2 band').fill('1-999999999');
    await page.getByLabel('Effective date').fill(dmy('2026-01-01'));
    await page.getByLabel('TIRA filing reference').fill('TIRA/E2E/FTD');
    await page.getByLabel('TIRA approval date').fill(dmy('2026-01-15'));
    await page.getByLabel('Free-look days').fill('15');

    await page.getByLabel('Value basis').selectOption('DEPOSIT');
    const terms = ['3', '6', '12'];
    for (const [j, months] of terms.entries()) {
      await page.getByRole('button', { name: 'Add a term' }).click();
      await page.getByLabel(`Term ${j + 1} in months`).fill(months);
    }
    const bands = [
      ['500000', '3', '4', '5'],
      // Above a band's top is the next band (the user's answer), so each starts a cent above it.
      ['5000000.01', '4', '5', '6'],
      ['10000000.01', '5', '6', '7'],
      ['20000000.01', '6', '7', '8'],
    ];
    for (const [i, band] of bands.entries()) {
      await page.getByRole('button', { name: 'Add a band' }).click();
      await page.getByLabel(`Band ${i + 1} starts at`).fill(band[0]!);
      for (let j = 1; j <= 3; j++) await page.getByLabel(`Band ${i + 1} rate for term ${j}`).fill(band[j]!);
    }
    await page.getByRole('button', { name: 'Publish version' }).click();
    await page.getByRole('button', { name: 'Publish and make active' }).click();
    await expect(page).toHaveURL(/\/staff\/products\/[0-9a-f-]{36}$/, { timeout: 15_000 });

    // ---- The deposit: one payment of 1,000,000 for three months.
    const policyNumber = await asAdmin(browser, async (admin) => {
      const caseId = await caseAwaitingManualIssue(admin, '1000000.00', `${name} (${code})`);
      await admin.goto('/staff/policies/new');
      await selectUnderwritingCase(admin, caseId);
      await expect(admin.getByText('Resolving product version…')).not.toBeVisible();
      await admin.getByLabel('Sum assured').fill('1000000.00');
      await admin.getByLabel('Premium', { exact: true }).fill('1000000.00');
      await admin.getByLabel('Premium frequency').selectOption('SINGLE');
      // MIGRATION so the policy is in force on arrival -- see e2e/policies.ts.
      await admin.getByLabel('Why is this being issued by hand?').selectOption('MIGRATION');
      await admin.getByLabel('Reason for manual issue').fill('E2E fixture: fixed-term deposit');
      await admin.getByLabel('Commencement date').fill(dmy(new Date().toISOString().slice(0, 10)));
      await admin.getByLabel('Policy term (months)').fill('3');
      await admin.getByRole('button', { name: 'Issue policy' }).click();
      await expect(admin).toHaveURL(/\/staff\/policies\/POL-[A-Z0-9]+$/, { timeout: 60_000 });
      return admin.url().split('/').pop() as string;
    });

    // ---- The single premium, collected through the mock rail from a number.
    await page.goto(`/staff/policies/${policyNumber}`);
    await page.getByRole('tab', { name: 'Billing' }).click();
    await page.getByRole('button', { name: 'Request payment' }).first().click({ timeout: 20_000 });
    const pay = page.locator('form').filter({ has: page.getByLabel('Payer reference') });
    await pay.getByLabel('Payer reference').fill('+255700000777');
    await pay.getByRole('button', { name: 'Request payment' }).click();
    // 60s, as issuance gets: the request answers only after the whole AFTER_COMMIT chain -- payment,
    // the mock rail's confirmation, billing, and the deposit's credit -- has run on its thread.
    await expect(page.getByLabel('Payer reference')).not.toBeVisible({ timeout: 60_000 });

    // Credited once payment confirms it, and the first term opens -- a poll, not a timing guess.
    await expect(async () => {
      await page.reload();
      await page.getByRole('tab', { name: 'Account' }).click();
      await expect(page.getByText('3 months, 3% for the term')).toBeVisible();
    }).toPass({ timeout: 90_000 });
    // Paid back to the number it came from, unless told otherwise; nothing in or out meanwhile.
    await expect(page.getByText('No instruction: it will be paid out to +255700000777.')).toBeVisible();
    await expect(page.getByRole('button', { name: 'Request top-up' })).toHaveCount(0);
    await expect(page.getByRole('button', { name: 'Request withdrawal' })).toHaveCount(0);

    // ---- The client's choice: reinvest the deposit and its interest for six months.
    const atMaturity = page.getByRole('form', { name: 'At maturity' });
    await atMaturity.getByLabel('What should happen').selectOption('REINVEST');
    await atMaturity.getByLabel('New term').selectOption('6');
    await atMaturity.getByRole('button', { name: 'Record instruction' }).click();
    await expect(page.getByText(/^Reinvest for 6 months \(recorded by/)).toBeVisible({ timeout: 20_000 });
  });
});
