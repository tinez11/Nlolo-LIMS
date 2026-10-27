import { expect, test } from '@playwright/test';

/**
 * The charts against the real stack.
 *
 * <p>Asserted as DESCRIBED IMAGES rather than by counting rects: that is what a reader who cannot see them is told,
 * and it is the one part of a chart that is worth pinning. A test that counted bars would break every time the
 * window moved by a month while proving nothing about what the chart says.
 */
test.describe('staff dashboard charts, as finance', () => {
  // The finance dashboard is offered to FINANCE_OFFICER or ADMIN only (dashboards.ts). This ran as the project's
  // default identity, staff.underwriter (UNDERWRITER alone), who correctly gets no Finance tab -- so the page showed
  // the underwriting dashboard and there was never a premiums chart to find.
  test.use({ storageState: 'e2e/.auth/staff-finance.json' });

  test('the finance dashboard draws premiums by month and ages its receivables', async ({ page }) => {
    await page.goto('/staff/dashboard?dashboard=finance');

    // The ledger read follows the person's access (/me/access) and can take a while on a busy run.
    await expect(page.getByRole('img', { name: /Premiums collected by month/ })).toBeVisible({ timeout: 20_000 });
    // The bands when anything is owed, or the plain answer when nothing is: both correct, neither is a failure to read.
    await expect(page.getByRole('list', { name: 'Owed by how overdue' }).or(page.getByText('Nothing is owed.'))).toBeVisible();
  });

  test('a range button re-reads the dashboard for those months', async ({ page }) => {
    await page.goto('/staff/dashboard?dashboard=finance');
    // The ledger read follows the person's access (/me/access) and can take a while on a busy run.
    await expect(page.getByRole('img', { name: /Premiums collected by month/ })).toBeVisible({ timeout: 20_000 });

    await page.getByRole('button', { name: 'Last 12 months' }).click();
    await expect(page).toHaveURL(/from=\d{4}-\d{2}&to=\d{4}-\d{2}/);
    await expect(page.getByRole('button', { name: 'Last 12 months' })).toHaveAttribute('aria-pressed', 'true');
    await expect(page.getByRole('img', { name: /Premiums collected by month, \w{3} \d{4} to \w{3} \d{4}/ })).toBeVisible();
  });
});

test.describe('staff dashboard charts', () => {
  test('the underwriting dashboard draws its decisions', async ({ page }) => {
    await page.goto('/staff/dashboard?dashboard=underwriting');

    // Every month of the range is drawn, at zero where nothing was decided, so the chart is always there.
    await expect(page.getByRole('img', { name: /Underwriting decisions by month/ })).toBeVisible();
  });
});
