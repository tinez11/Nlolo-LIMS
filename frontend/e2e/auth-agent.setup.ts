import { test as setup } from '@playwright/test';
import { signInAsAgent } from './agentSession';

/**
 * Signs in through the REAL Keycloak `agents` realm, as `agent.senior` --
 * seeded by `backend/scripts/seed-dev-data.sh`, which onboards a real
 * AgentProfile for this user (top of a 2-agent hierarchy, `agent.junior`
 * beneath it) and writes the resulting `partyId` back onto the Keycloak user
 * as its `party_id` attribute. Without that script having been run against
 * the target stack, this user has no `party_id` claim and `GET /agents/me`
 * 403s outright -- same "no fabricated JWTs" rule as auth.setup.ts.
 *
 * The sign-in itself lives in ./agentSession, because the agents specs repeat it when this saved
 * state has outlived Keycloak's idle timeout -- see that file for why a long run needs it.
 */
setup('authenticate as agent.senior', async ({ page }) => {
  await signInAsAgent(page);
});
