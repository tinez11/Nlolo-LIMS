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

    await expect(page.getByRole('img', { name: /Premiums collected by month/ })).toBeVisible();
    // Either the chart or its empty line, as the underwriting test below allows: with nothing overdue in the dev
    // database the panel correctly says so, and requiring bars made this fail on the data rather than the screen.
    const aging = page.getByRole('img', { name: /Receivables by age/ });
    const nothingOwed = page.getByText('Nothing is owed.');
    await expect(aging.or(nothingOwed)).toBeVisible();
  });
});

test.describe('staff dashboard charts', () => {
  test('the underwriting dashboard draws its decisions', async ({ page }) => {
    await page.goto('/staff/dashboard?dashboard=underwriting');

    // Either the chart or its empty line, depending on whether the dev database has decided anything in the window.
    // Both are correct; what would be wrong is neither.
    const chart = page.getByRole('img', { name: /Underwriting decisions by month/ });
    const empty = page.getByText('No decisions in this period.');
    await expect(chart.or(empty)).toBeVisible();
  });
});
