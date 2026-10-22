import { expect, test } from '@playwright/test';
import { fillBasics, publishVersion, step } from './publishForm';

/**
 * Account charges kept in one place (2026-10-22, product V34): a charge is created under Account charges for a range of
 * policy years, a savings product's version ticks it on its Charges step, and the product page names it.
 */
test.describe('account charges', () => {
  test.use({ storageState: 'e2e/.auth/staff-admin.json' });

  test('a charge made for years 1 to 3 is picked by a savings product and shown on it', async ({ page }) => {
    const n = Date.now();
    const chargeName = `E2E premium allocation ${n}`;
    const code = `E2E-SAV-${n}`;
    const name = `E2E Savings ${code}`;

    // ---- The charge: 2% of each premium, in policy years 1 to 3.
    await page.goto('/staff/account-charges');
    await expect(page.getByRole('heading', { name: 'Account charges' })).toBeVisible();
    await page.getByLabel('Name').fill(chargeName);
    await page.getByLabel('When it is taken').selectOption('PREMIUM');
    await page.getByLabel('Type').selectOption('PERCENT');
    await page.getByLabel('Percentage').fill('2');
    await page.getByLabel('To policy year').fill('3');
    await page.getByRole('button', { name: 'Create charge' }).click();
    const listed = page.getByRole('listitem').filter({ hasText: chargeName });
    await expect(listed).toContainText('On each premium or top-up · 2% of each premium · Years 1–3');

    // ---- The product: an endowment kept as a savings account.
    await page.goto('/staff/products/new');
    await page.getByLabel('Product code').fill(code);
    await page.getByLabel('Product name').fill(name);
    await page.getByLabel('Category').selectOption('ENDOWMENT');
    await page.getByLabel('IFRS 17 portfolio').selectOption('SAV');
    await page.getByLabel('Default currency').fill('TZS');
    await page.getByRole('button', { name: 'Create product' }).click();
    await expect(page.getByText('DRAFT')).toBeVisible();

    await expect(page.getByRole('radio', { name: /Savings account/ })).toBeChecked();
    await fillBasics(page, 'TIRA/E2E/SAV');
    await step(page, 'Rates');
    await page.getByLabel('Guaranteed interest rate (% a year)').fill('3');
    await page.getByLabel('Minimum balance after a withdrawal').fill('0');
    await step(page, 'What it pays');
    await page.getByLabel('Free-look days').fill('15');
    await page.getByRole('button', { name: 'Add a payout' }).click();
    await page.getByLabel('Payout 1 kind').selectOption('MATURITY');
    await page.getByLabel('Payout 1 basis').selectOption('ACCOUNT_VALUE');
    await page.getByLabel('Payout 1 amount').fill('100');

    // ---- Its charges, ticked from the list rather than typed.
    await step(page, 'Charges');
    await page.getByRole('checkbox', { name: new RegExp(chargeName) }).check();
    await publishVersion(page);
    await page.getByRole('button', { name: 'Publish and make active' }).click();
    await expect(page).toHaveURL(/\/staff\/products\/[0-9a-f-]{36}$/, { timeout: 15_000 });

    // ---- The version names the charge it takes.
    await expect(page.getByText('Account charges', { exact: true })).toBeVisible();
    await expect(page.getByText(`${chargeName}: On each premium or top-up · 2% of each premium · Years 1–3`)).toBeVisible();
  });
});
