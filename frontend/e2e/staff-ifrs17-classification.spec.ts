import { expect, test } from '@playwright/test';
import { caseAwaitingManualIssue, selectUnderwritingCase } from './underwriting';

/**
 * IFRS 17 I2 through the real stack: a product names its portfolio, a case carries the sale's channel and branch
 * until the policy is issued, and the issued policy is classified -- its sale facts for every staff member, its group
 * of contracts and measurement model for finance.
 */

test.describe('IFRS 17 classification at sale', () => {
  test.use({ storageState: 'e2e/.auth/staff-admin.json' });

  test('a new product takes its category portfolio until somebody picks another', async ({ page }) => {
    await page.goto('/staff/products/new');
    const portfolio = page.getByLabel('IFRS 17 portfolio');
    await expect(portfolio).toHaveValue('TERM');
    await page.getByLabel('Category').selectOption('ENDOWMENT');
    await expect(portfolio).toHaveValue('END');
    await portfolio.selectOption('PAR');
    await page.getByLabel('Category').selectOption('WHOLE_LIFE');
    await expect(portfolio, 'a portfolio chosen by hand stays').toHaveValue('PAR');
  });
});

test.describe('IFRS 17 classification at sale -- a policy', () => {
  test.use({ storageState: 'e2e/.auth/staff-finance.json' });

  test('the case sale is changed, fixed at issue, and the policy is classified into its group', async ({ page }) => {
    const caseId = await caseAwaitingManualIssue(page);

    await page.goto(`/staff/underwriting/${caseId}`);
    await page.getByRole('button', { name: 'Change sale' }).click();
    const sale = page.getByRole('form', { name: 'Change sale' });
    await sale.getByLabel('Sales channel').selectOption('DIGITAL');
    await sale.getByLabel('Sale branch').selectOption('ZNZ');
    await sale.getByRole('button', { name: 'Save sale' }).click();
    await expect(page.getByRole('form', { name: 'Change sale' })).toHaveCount(0, { timeout: 15_000 });
    await expect(page.getByText('ZNZ', { exact: true })).toBeVisible();

    // Manual issue, as the finaccounting spec does it: the case was declined to be overturned.
    await page.goto('/staff/policies/new');
    await selectUnderwritingCase(page, caseId);
    await expect(page.getByText('Resolving product version…')).not.toBeVisible();
    await page.getByLabel('Sum assured').fill('2000000.00');
    await page.getByLabel('Premium', { exact: true }).fill('800.00');
    await page.getByLabel('Why is this being issued by hand?').selectOption('MIGRATION');
    await page.getByLabel('Reason for manual issue').fill('E2E IFRS 17 classification');
    await page.getByRole('button', { name: 'Issue policy' }).click();
    await expect(page).toHaveURL(/\/staff\/policies\/POL-[A-Z0-9]+$/, { timeout: 30_000 });

    const panel = page.locator('section').filter({ has: page.getByRole('heading', { name: 'Sale & IFRS 17' }) });
    await expect(panel).toContainText('Term life');
    await expect(panel).toContainText('Digital');
    await expect(panel).toContainText('ZNZ');
    // Finaccounting classifies from PolicyIssued, after the issue commits.
    const groupKey = `TERM-GMM-${new Date().getFullYear()}-REM`;
    await expect(async () => {
      await page.reload();
      await expect(page.getByText(groupKey)).toBeVisible();
    }).toPass({ timeout: 30_000 });

    await page.goto(`/staff/underwriting/${caseId}`);
    await expect(page.getByText('Sale fixed when the policy was issued.')).toBeVisible({ timeout: 15_000 });
    await expect(page.getByRole('button', { name: 'Change sale' })).toHaveCount(0);
  });
});
