import { expect, type Page, test } from '@playwright/test';

/**
 * `POST /agents/{n}/suspend`/`reactivate` -- `AgentProfile.setLicenseStatus`
 * has existed since M7 with no caller anywhere on the platform until this
 * staff-portal CRUD audit found the gap. Gated the same as onboarding
 * (FINANCE_OFFICER/ADMIN), so this file runs under the real staff.finance
 * identity, the same multi-identity shape staff-distribution.spec.ts
 * already established.
 */

const FUTURE_LICENSE_EXPIRY = `${new Date().getFullYear() + 5}-01-01`;

async function onboardRealAgent(page: Page): Promise<string> {
  await page.goto('/staff/agents/new');
  await page.getByRole('button', { name: 'Search for a VERIFIED party by name' }).click();
  await page.getByPlaceholder('Type a name to search').fill('Amina');
  await page.getByText('Amina Owner').click();
  await page.getByLabel('License number').fill(`E2E-LIC-LIFECYCLE-${Date.now()}`);
  await page.getByLabel('License expiry date').fill(FUTURE_LICENSE_EXPIRY);
  await page.getByRole('button', { name: 'Onboard agent' }).click();
  await expect(page).toHaveURL(/\/staff\/agents\/[0-9a-f-]{36}$/, { timeout: 15_000 });
  return page.url().split('/').pop() as string;
}

test.describe('staff agent lifecycle', () => {
  test.use({ storageState: 'e2e/.auth/staff-finance.json' });

  test('suspends and reactivates a real agent end to end', async ({ page }) => {
    await onboardRealAgent(page);

    await expect(page.getByRole('heading', { level: 2, name: 'Lifecycle' })).toBeVisible();
    await page.getByRole('button', { name: 'Suspend' }).click();
    await expect(page.getByText('Suspended')).toBeVisible({ timeout: 15_000 });
    await expect(page.getByRole('button', { name: 'Reactivate' })).toBeVisible();

    await page.getByRole('button', { name: 'Reactivate' }).click();
    await expect(page.getByText('Active')).toBeVisible({ timeout: 15_000 });
    await expect(page.getByRole('button', { name: 'Suspend' })).toBeVisible();
  });

  // The invalid-transition 409 (suspending an already-SUSPENDED agent, or
  // reactivating an already-ACTIVE one) is deliberately NOT exercised here:
  // the UI's own conditional rendering (mirroring the backend's guard) means
  // there is no button that reaches it -- it is already covered directly by
  // DistributionContractTest's suspendAgentRejectsAnAlreadySuspendedAgentWith409
  // and reactivateAgentRejectsAnAlreadyActiveAgentWith409.

  test('a staff.underwriter session sees no lifecycle actions on an agent', async ({ page, browser }) => {
    const agentId = await onboardRealAgent(page);

    const underwriterContext = await browser.newContext({ storageState: 'e2e/.auth/staff.json' });
    const underwriterPage = await underwriterContext.newPage();
    await underwriterPage.goto(`/staff/agents/${agentId}`);
    await expect(underwriterPage.getByRole('heading', { name: /E2E-LIC-LIFECYCLE-/ })).toBeVisible();
    await expect(underwriterPage.getByRole('button', { name: 'Suspend' })).not.toBeVisible();
    await expect(underwriterPage.getByRole('heading', { level: 2, name: 'Lifecycle' })).not.toBeVisible();
    await underwriterContext.close();
  });

  test('the onboarding party picker excludes a real PENDING party, at the network level', async ({ page, browser }) => {
    // A real, fresh PENDING party -- created through the real agents-realm
    // onboarding flow (a separate real login), not seeded, so this proves the
    // whole loop: a party this platform itself just created as PENDING is
    // genuinely excluded by the server-side kycStatus=VERIFIED filter, not
    // merely assumed to be.
    const agentContext = await browser.newContext({ storageState: 'e2e/.auth/agent.json' });
    const agentPage = await agentContext.newPage();
    const pendingName = `E2E Picker Exclusion Fixture ${Date.now()}`;
    await agentPage.goto('/agents/customers/new');
    await agentPage.getByLabel('Full name').fill(pendingName);
    await agentPage.getByLabel('Date of birth').fill('1990-05-12');
    await agentPage.getByRole('button', { name: 'Register individual' }).click();
    await expect(agentPage.getByText('Registered', { exact: true })).toBeVisible({ timeout: 15_000 });
    await agentContext.close();

    await page.goto('/staff/agents/new');
    await page.getByRole('button', { name: 'Search for a VERIFIED party by name' }).click();
    await page.getByPlaceholder('Type a name to search').fill('E2E Picker Exclusion Fixture');

    await expect(page.getByText(/No matches for/)).toBeVisible({ timeout: 5000 });
    await expect(page.getByText(pendingName)).not.toBeVisible();
  });
});
