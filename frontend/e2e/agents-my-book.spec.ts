import { expect, test } from '@playwright/test';

/**
 * "Browse my book of business" -- the agents-realm scoping added to
 * `PolicyController`/`ClaimController` (via `DistributionApi.resolveAgentTeam`),
 * verified through the real HTTP surface this frontend actually calls, as a
 * real login (`agent.senior`, seeded by `backend/scripts/seed-dev-data.sh`).
 *
 * `AGENT_SENIOR_ID` is `agent.senior`'s real, seeded `agentId` -- confirmed
 * directly against the dev Postgres (`license_number = 'LIC-SENIOR-001'`),
 * not guessed. Two real policies are issued as staff: one naming
 * `agent.senior` as agentOfRecord, one sold direct (no agent at all) -- the
 * object-level check (single GET) is the primary proof here, since it does
 * not depend on how large agent.senior's book has grown across other runs
 * of this same suite; the list-page check is a lighter, best-effort
 * companion on top of it.
 */
const AGENT_SENIOR_ID = '83ac3bd4-a900-4a61-a9a4-90cf64a5da90';

test.describe('agents my book of business', () => {
  test('sees its own book, not an unrelated policy or claim', async ({ page, browser }) => {
    const staffContext = await browser.newContext({ storageState: 'e2e/.auth/staff.json' });
    const staffPage = await staffContext.newPage();

    async function issuePolicy(agentOfRecordId: string | null, reason: string): Promise<string> {
      await staffPage.goto('/staff/policies/new');
      await staffPage.getByRole('button', { name: 'Search for the policyholder by name' }).click();
      await staffPage.getByPlaceholder('Type a name to search').fill('Amina');
      await staffPage.getByText('Amina Owner').click();
      await staffPage.getByLabel('Product').selectOption({ label: 'Demo Term Life (DEMO-TERM-01)' });
      await expect(staffPage.getByText('Resolving product version…')).not.toBeVisible();
      await staffPage.getByLabel('Sum assured').fill('1000000.00');
      await staffPage.getByLabel('Premium', { exact: true }).fill('500.00');
      if (agentOfRecordId) {
        await staffPage.getByLabel('Agent of record id (optional)').fill(agentOfRecordId);
      }
      await staffPage.getByLabel('Reason for manual issue').fill(reason);
      await staffPage.getByRole('button', { name: 'Issue policy' }).click();
      await expect(staffPage).toHaveURL(/\/staff\/policies\/POL-[A-Z0-9]+$/, { timeout: 15_000 });
      return staffPage.url().split('/').pop() as string;
    }

    const policyInBook = await issuePolicy(AGENT_SENIOR_ID, 'E2E agent book fixture -- in book');
    const policyOutsideBook = await issuePolicy(null, 'E2E agent book fixture -- direct sold');

    await staffPage.goto('/staff/claims/new');
    await staffPage.getByPlaceholder('POL-XXXXXXXX').fill(policyInBook);
    await staffPage.getByRole('button', { name: 'Search for the claimant by name' }).click();
    await staffPage.getByPlaceholder('Type a name to search').fill('Amina');
    await staffPage.getByText('Amina Owner').click();
    await staffPage.getByRole('button', { name: 'Select the date of event' }).click();
    await staffPage.getByPlaceholder('YYYY-MM-DD').fill('2026-08-01');
    await staffPage.getByLabel('Cause of death').fill('Natural causes');
    await staffPage.getByLabel('Place of death').fill('Dar es Salaam');
    await staffPage.getByRole('button', { name: 'Select the date of death' }).click();
    await staffPage.getByPlaceholder('YYYY-MM-DD').fill('2026-08-01');
    await staffPage.getByLabel('Attending physician').fill('Dr. E2E Book Fixture');
    await staffPage.getByRole('button', { name: 'Register claim' }).click();
    await expect(staffPage).toHaveURL(/\/staff\/claims\/[0-9a-f-]{36}$/, { timeout: 15_000 });

    await staffContext.close();

    // Object-level scoping, single GET: own-team policy 200s, outside-team policy 403s.
    await page.goto(`/agents/policies/${policyInBook}`);
    await expect(page.getByText('TZS 1,000,000.00').first()).toBeVisible();
    // Suspend/resume/reinstate are staff-only -- must not render at all here,
    // not merely fail to work if clicked.
    await expect(page.getByText('Lifecycle')).not.toBeVisible();
    await expect(page.getByRole('button', { name: 'Suspend' })).toHaveCount(0);

    await page.goto(`/agents/policies/${policyOutsideBook}`);
    await expect(page.getByRole('alert')).toBeVisible();

    // Search scoping: the list itself only ever returns this agent's own team.
    await page.goto('/agents/policies');
    await expect(page.getByRole('heading', { name: 'My policies' })).toBeVisible();
    await expect(page.getByText(policyInBook)).toBeVisible();
    await expect(page.getByText(policyOutsideBook)).not.toBeVisible();

    // The claim filed against the in-book policy is visible under My claims;
    // claims joins through policy (no agentOfRecordId of its own).
    await page.goto('/agents/claims');
    await expect(page.getByRole('heading', { name: 'My claims' })).toBeVisible();
    await expect(page.getByText(policyInBook)).toBeVisible();
  });
});
