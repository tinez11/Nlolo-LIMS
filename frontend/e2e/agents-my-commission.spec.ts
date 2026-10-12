import { expect, test } from './agentSession';
import { selectAgentOfRecord } from './policies';
import { caseAwaitingManualIssue, selectUnderwritingCase } from './underwriting';

/**
 * "My commission" as `agent.senior`, against the real backend.
 *
 * The spec earns its own fixture: staff issues a policy naming this agent as agent of record, on
 * a MIGRATION basis so it is in force on arrival, which makes a real FIRST_YEAR accrual land in
 * this month's statement whatever the dev database held before. Pinning a seeded policy number
 * instead is the trap `policies.ts` documents at length.
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

    const staffContext = await browser.newContext({ storageState: 'e2e/.auth/staff.json' });
    const staffPage = await staffContext.newPage();
    const caseId = await caseAwaitingManualIssue(staffPage);
    await staffPage.goto('/staff/policies/new');
    await selectUnderwritingCase(staffPage, caseId);
    await expect(staffPage.getByText('Resolving product version…')).not.toBeVisible();
    await staffPage.getByLabel('Sum assured').fill('1000000.00');
    await staffPage.getByLabel('Premium', { exact: true }).fill('500.00');
    await selectAgentOfRecord(staffPage, agentId);
    await staffPage.getByLabel('Why is this being issued by hand?').selectOption('MIGRATION');
    await staffPage.getByLabel('Reason for manual issue').fill('E2E my-commission fixture');
    await staffPage.getByRole('button', { name: 'Issue policy' }).click();
    await expect(staffPage).toHaveURL(/\/staff\/policies\/POL-[A-Z0-9]+$/, { timeout: 60_000 });
    const policyNumber = staffPage.url().split('/').pop() as string;
    await staffContext.close();

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
    await expect(row.getByRole('cell').first()).not.toHaveText(/^([0-9a-f]{8}-|—$)/);
    // And the line is dated, in the console's own date format.
    await expect(row).toContainText(/[A-Z][a-z]{2} \d{1,2}, \d{4}/);

    // An agent never sees a payout control, on any statement.
    await expect(page.getByRole('button', { name: 'Request payout' })).toHaveCount(0);

    await row.getByRole('link', { name: policyNumber }).click();
    await expect(page).toHaveURL(new RegExp(`/agents/policies/${policyNumber}$`));
  });
});
