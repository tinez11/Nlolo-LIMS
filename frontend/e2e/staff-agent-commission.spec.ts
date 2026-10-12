import { expect, test } from '@playwright/test';
import { giveAgentEarningPolicy, onboardRealAgent } from './commission';

/**
 * Staff read any agent's commission on the agent page, through the same view agents get, and a
 * finance officer still has the payout control over the month that carries a statement.
 *
 * The agent is onboarded here rather than taken from the register: the seeded agents either have no
 * commission at all (`LIC-SENIOR-001`) or belong to credit-life lenders whose licence numbers are
 * minted per run, so neither is a stable target. The 409-on-OPEN payout path itself is driven by
 * staff-distribution.spec.ts.
 */
test.describe('staff agent commission', () => {
  test('shows an earning agent its totals, lines and the payout control', async ({ browser }) => {
    test.slow();
    const context = await browser.newContext({ storageState: 'e2e/.auth/staff-finance.json' });
    const page = await context.newPage();

    const agentId = await onboardRealAgent(page);
    const policyNumber = await giveAgentEarningPolicy(browser, agentId, 'E2E staff commission fixture');

    await page.goto(`/staff/agents/${agentId}`);
    await expect(page.getByRole('heading', { name: 'Totals' })).toBeVisible();
    await expect(page.getByRole('heading', { name: 'Statements' })).toBeVisible();
    await expect(page.getByText('Earned this year')).toBeVisible();

    const row = page.getByRole('row').filter({ has: page.getByRole('link', { name: policyNumber }) });
    await expect(async () => {
      await page.reload();
      await expect(row).toBeVisible();
    }).toPass({ timeout: 20_000 });
    await expect(row.getByRole('cell', { name: 'First year', exact: true })).toBeVisible();
    // Staff reach the policy through the staff console, not the agents one.
    await expect(row.getByRole('link', { name: policyNumber })).toHaveAttribute(
      'href',
      `/staff/policies/${policyNumber}`,
    );

    // Finance can still act on the month it is looking at.
    await expect(page.getByPlaceholder('Payee mobile-money reference')).toBeVisible();
    await expect(page.getByRole('button', { name: 'Request payout' })).toBeVisible();

    await context.close();
  });
});
