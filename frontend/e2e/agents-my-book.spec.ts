import { expect, test } from '@playwright/test';
import { dmy } from './dates';
import { fillPolicyNumberManually } from './guards';

/**
 * "Browse my book of business" -- the agents-realm scoping added to
 * `PolicyController`/`ClaimController` (via `DistributionApi.resolveAgentTeam`),
 * verified through the real HTTP surface this frontend actually calls, as a
 * real login (`agent.senior`, seeded by `backend/scripts/seed-dev-data.sh`).
 *
 * `agent.senior`'s `agentId` is READ OFF ITS OWN PROFILE rather than hard-coded.
 *
 * It was the literal `83ac3bd4-a900-4a61-a9a4-90cf64a5da90`, "confirmed directly against
 * the dev Postgres, not guessed" -- true when written, and wrong the moment the volumes
 * were reset, because the seeder mints a fresh uuid. The spec then issued its "in book"
 * policy naming an agent that does not exist (manual issue never validates the id, and
 * `policy.policy` carries no FK at all), so the policy belonged to nobody, `agent.senior`
 * was correctly refused it, and the failure surfaced as a missing sum assured -- a real
 * 403 wearing the costume of a display bug.
 *
 * `GET /agents/me` is the resolution the platform itself provides for this, and the
 * profile page renders it. Two real policies are then issued as staff: one naming
 * `agent.senior` as agentOfRecord, one sold direct (no agent at all) -- the object-level
 * check (single GET) is the primary proof here, since it does not depend on how large
 * agent.senior's book has grown across other runs of this same suite; the list-page check
 * is a lighter, best-effort companion on top of it.
 */
async function ownAgentId(page: import('@playwright/test').Page): Promise<string> {
  await page.goto('/agents');
  const agentId = (
    await page
      .locator('dt')
      .filter({ hasText: 'Agent id' })
      .locator('xpath=following-sibling::dd[1]')
      .textContent()
  )?.trim();
  expect(agentId, 'the agent console must be able to tell us its own agent id').toMatch(
    /^[0-9a-f-]{36}$/,
  );
  return agentId as string;
}

test.describe('agents my book of business', () => {
  test('sees its own book, not an unrelated policy or claim', async ({ page, browser }) => {
    // Two real issuances, a real claim registration and two consoles, and now one more
    // navigation to resolve the agent id honestly. It fits in 60s only when nothing
    // retries; it used to finish early because it failed at the first assertion.
    test.slow();
    const AGENT_SENIOR_ID = await ownAgentId(page);
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
    await fillPolicyNumberManually(staffPage, policyInBook);
    await staffPage.getByRole('button', { name: 'Search for the claimant by name' }).click();
    await staffPage.getByPlaceholder('Type a name to search').fill('Amina');
    await staffPage.getByText('Amina Owner').click();
    await staffPage.getByLabel('Date of event').fill(dmy('2026-08-01'));
    await staffPage.getByLabel('Cause of death').fill('Natural causes');
    await staffPage.getByLabel('Place of death').fill('Dar es Salaam');
    await staffPage.getByLabel('Date of death').fill(dmy('2026-08-01'));
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
