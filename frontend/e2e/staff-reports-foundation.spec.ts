import { expect, test } from '@playwright/test';

/**
 * Reports foundation (2026-11-11, analytics plan 2): a report row opens its record, a period choice fills the dates,
 * every report says how fresh it is, "What the figures mean" explains the figures, and a dashboard card opens what it
 * counted for the same months. Figures themselves are proven server-side; this proves the wiring.
 */
test.describe('reports foundation', () => {
  test.use({ storageState: 'e2e/.auth/staff-finance.json' });

  test('a policy in the register opens its own record', async ({ page }) => {
    await page.goto('/staff/reports/products');
    await expect(page.getByRole('heading', { level: 1, name: 'Policies by product' })).toBeVisible({ timeout: 30_000 });
    await page.getByRole('button', { name: 'Run report' }).click();
    const register = page.getByRole('table', { name: /^Policies on / });
    const first = register.getByRole('row').nth(1).getByRole('link').first();
    await expect(first).toBeVisible({ timeout: 30_000 });
    const number = (await first.textContent())?.trim() ?? '';
    await first.click();
    await expect(page).toHaveURL(new RegExp(`/staff/policies/${number}$`));
  });

  test('a quarter fills the dates, and the report says how fresh it is', async ({ page }) => {
    await page.goto('/staff/reports/premium-collections');
    await expect(page.getByRole('heading', { level: 1, name: 'Premium collections' })).toBeVisible({ timeout: 30_000 });
    const period = page.getByRole('group', { name: 'Period choices' });
    await period.getByRole('button', { name: /^Last quarter/ }).click();
    const from = await page.getByLabel('From').inputValue();
    expect(from).toMatch(/^\d{4}-(01|04|07|10)-01$/);
    await page.getByRole('button', { name: 'Run report' }).click();
    await expect(page).toHaveURL(new RegExp(`from=${from}`));
    await expect(page.getByText(/^Last change taken in: .+ · billing read live$/)).toBeVisible({ timeout: 30_000 });
  });

  test('what the figures mean explains the collection rate', async ({ page }) => {
    await page.goto('/staff/reports');
    await page.getByRole('link', { name: 'What the figures mean' }).click();
    await expect(page.getByRole('heading', { level: 1, name: 'What the figures mean' })).toBeVisible({ timeout: 30_000 });
    const rate = page.getByRole('article', { name: 'Collection rate' });
    await expect(rate).toContainText('Premiums paid ÷ premiums due');
    await expect(page.getByRole('link', { name: 'Premium collections', exact: true })).toBeVisible();
  });

  test('the New policies card opens New business for the same months', async ({ page }) => {
    await page.goto('/staff/dashboard?dashboard=finance');
    const card = page.getByRole('link', { name: /New policies/ });
    await expect(card).toBeVisible({ timeout: 30_000 });
    await card.click();
    await expect(page).toHaveURL(/\/staff\/reports\/new-business\?from=\d{4}-\d{2}-01&to=\d{4}-\d{2}-\d{2}$/);
    await expect(page.getByRole('heading', { level: 1, name: 'New business' })).toBeVisible();
  });
});
