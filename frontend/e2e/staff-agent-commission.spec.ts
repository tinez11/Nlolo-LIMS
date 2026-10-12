import { expect, test } from '@playwright/test';

/**
 * Staff read any agent's commission on the agent page, through the same view agents get, and a
 * finance officer still has the payout control.
 *
 * The 409-on-OPEN payout path is driven end to end by staff-distribution.spec.ts against an agent
 * it onboards itself; this spec is about the view being present and acting on the seeded agent.
 */
test.describe('staff agent commission', () => {
  test('shows the commission view for a seeded agent, with the payout control for finance', async ({ browser }) => {
    const context = await browser.newContext({ storageState: 'e2e/.auth/staff-finance.json' });
    const page = await context.newPage();

    await page.goto('/staff/agents');
    await page.getByLabel('Search by licence number').fill('LIC-SENIOR');
    await page.getByRole('button', { name: /LIC-SENIOR/ }).first().click();
    await expect(page).toHaveURL(/\/staff\/agents\/[0-9a-f-]{36}$/);

    await expect(page.getByRole('heading', { name: 'Totals' })).toBeVisible();
    await expect(page.getByRole('heading', { name: 'Statements' })).toBeVisible();
    await expect(page.getByText('Earned this year')).toBeVisible();

    // The seeded agent has earned in the current month (96 FIRST_YEAR lines sit in 2026-10), so
    // the default month carries a statement and therefore the payout affordance.
    await expect(page.getByPlaceholder('Payee mobile-money reference')).toBeVisible();
    await expect(page.getByRole('button', { name: 'Request payout' })).toBeVisible();

    await context.close();
  });
});
