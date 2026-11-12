import { expect, test } from './agentSession';

/**
 * Agent sales in money (2026-11-12, analytics plan 3), as `agent.senior` -- the top of a 2-agent hierarchy
 * (seeded by `backend/scripts/seed-dev-data.sh`), so the Me / My team switch is there.
 *
 * Proves the round trip through the real backend, not the dev data's totals: the dashboard's sales panel and
 * its switch, the My sales page asked by month, a period opening its policies, and the Excel file.
 */
test.describe('agents my sales', () => {
  test("the dashboard shows the team's sales in money and narrows to the agent's own", async ({ page }) => {
    await page.goto('/agents/dashboard');

    await expect(page.getByRole('heading', { name: 'Your team’s sales' })).toBeVisible({ timeout: 30_000 });
    await expect(page.getByText('Premium a year')).toBeVisible();
    await expect(page.getByText('Cover sold', { exact: true })).toBeVisible();

    await page.getByRole('group', { name: 'Whose sales' }).getByRole('button', { name: 'Me' }).click();
    await expect(page).toHaveURL(/show=me/);
    await expect(page.getByRole('heading', { name: 'Your sales' })).toBeVisible();

    await page.getByRole('link', { name: 'Sales report' }).click();
    await expect(page).toHaveURL(/\/agents\/sales\?show=me$/);
    await expect(page.getByRole('heading', { name: 'My sales', exact: true })).toBeVisible();
  });

  test('My sales shows by month, opens the policies behind a period, and downloads', async ({ page }) => {
    await page.goto('/agents/sales');
    await expect(page.getByRole('heading', { name: 'My sales', exact: true })).toBeVisible();
    await expect(page.getByText('Premium sold a year').first()).toBeVisible({ timeout: 30_000 });

    await page.getByRole('group', { name: 'Show by' }).getByRole('button', { name: 'Month' }).click();
    await expect(page).toHaveURL(/by=MONTH/);
    await expect(page.getByRole('heading', { name: 'Sales by month' })).toBeVisible({ timeout: 30_000 });

    // Each month with a sale is a chip; the newest sale is in some month of the span, so at least one is there
    // unless nothing was ever sold -- then the page says so, which is also a valid answer.
    const periods = page.getByRole('group', { name: 'Period' }).getByRole('button');
    if (await periods.count() > 0) {
      await periods.last().click();
      await expect(page.getByRole('table', { name: /^Policies sold, / })).toBeVisible({ timeout: 30_000 });
    } else {
      await expect(page.getByText('Nothing sold in these dates')).toBeVisible();
    }

    const download = page.waitForEvent('download');
    await page.getByRole('button', { name: 'Download Excel' }).click();
    expect((await download).suggestedFilename()).toMatch(/^my-sales-\d{4}-\d{2}-\d{2}\.xlsx$/);
  });
});
