import { expect, test } from './agentSession';
import { giveAgentEarningPolicy } from './commission';

/**
 * "My commission" as `agent.senior`, against the real backend.
 *
 * The spec builds its own earning chain (product, plan, policy in force naming this agent), because
 * the seeded `LIC-SENIOR-001` has earned nothing: the dev database's commission lines all belong to
 * credit-life lender agents. Issuing a policy alone is not enough either -- with no commission plan
 * on the product nothing accrues, and the screen correctly shows "Nothing earned yet".
 */
async function ownAgentId(page: import('@playwright/test').Page): Promise<string> {
  await page.goto('/agents/me');
  const agentId = (
    await page.locator('dt').filter({ hasText: 'Agent id' }).locator('xpath=following-sibling::dd[1]').textContent()
  )?.trim();
  expect(agentId, 'the agent console must be able to tell us its own agent id').toMatch(/^[0-9a-f-]{36}$/);
  return agentId as string;
}

test.describe('agents my commission', () => {
  test('shows a line earned this month with its client, product, type and date', async ({ page, browser }) => {
    test.slow();
    const agentId = await ownAgentId(page);
    const policyNumber = await giveAgentEarningPolicy(browser, agentId, 'E2E my-commission fixture');

    await page.goto('/agents/commission');
    await expect(page.getByRole('heading', { name: 'My commission' })).toBeVisible();
    await expect(page.getByText('Earned this month')).toBeVisible();
    await expect(page.getByText('Awaiting payout')).toBeVisible();

    const row = page.getByRole('row').filter({ has: page.getByRole('link', { name: policyNumber }) });
    await expect(async () => {
      await page.reload();
      await expect(row).toBeVisible();
    }).toPass({ timeout: 20_000 });
    await expect(row.getByRole('cell', { name: 'First year', exact: true })).toBeVisible();
    // The client is a real name resolved through party -- not a raw party id, and not a dash.
    await expect(row.getByRole('cell').first()).toHaveText('Amina Owner');
    // And the line is dated, in the console's own date format.
    await expect(row).toContainText(/[A-Z][a-z]{2} \d{1,2}, \d{4}/);
    // 10% of the 800.00 premium, net and signed -- the figure the agent is actually owed.
    await expect(row).toContainText('TZS 80.00');

    // An agent never sees a payout control, on any statement.
    await expect(page.getByRole('button', { name: 'Request payout' })).toHaveCount(0);

    await row.getByRole('link', { name: policyNumber }).click();
    await expect(page).toHaveURL(new RegExp(`/agents/policies/${policyNumber}$`));
  });
});
