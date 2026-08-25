import { expect, test as setup } from '@playwright/test';

/**
 * Signs in through the REAL Keycloak `agents` realm, as `agent.senior` --
 * seeded by `backend/scripts/seed-dev-data.sh`, which onboards a real
 * AgentProfile for this user (top of a 2-agent hierarchy, `agent.junior`
 * beneath it) and writes the resulting `partyId` back onto the Keycloak user
 * as its `party_id` attribute. Without that script having been run against
 * the target stack, this user has no `party_id` claim and `GET /agents/me`
 * 403s outright -- same "no fabricated JWTs" rule as auth.setup.ts.
 */
const AGENT_USER = 'agent.senior';
const AGENT_PASSWORD = 'devpassword';

setup(`authenticate as ${AGENT_USER}`, async ({ page }) => {
  await page.goto('/agents');

  await page.waitForURL(/\/realms\/agents\/protocol\/openid-connect\/auth/, {
    timeout: 30_000,
  });

  await page.locator('#username').fill(AGENT_USER);
  await page.locator('#password').fill(AGENT_PASSWORD);
  await page.locator('#kc-login').click();

  await page.waitForURL(/localhost:5173\/agents/, { timeout: 30_000 });
  await expect(page.getByRole('heading', { name: 'My profile' })).toBeVisible();

  await page.context().storageState({ path: 'e2e/.auth/agent.json' });
});
