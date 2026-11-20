import { test as setup } from '@playwright/test';
import { signInAsAgent } from './agentSession';

/**
 * Signs in through the REAL Authentik, into the agent workspace, as `agent.senior` -- seeded by
 * `backend/scripts/seed-dev-data.sh`, which onboards a real AgentProfile for this user (top of a 2-agent hierarchy,
 * `agent.junior` beneath it) and writes the resulting `partyId` onto the Authentik login as its `party_id` attribute.
 * Without that script having been run against the target stack, this user has no `party_id` claim and
 * `GET /agents/me` 403s outright -- same "no fabricated JWTs" rule as auth.setup.ts.
 */
setup('authenticate as agent.senior', async ({ page }) => {
  await signInAsAgent(page);
});
