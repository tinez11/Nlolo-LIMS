import { test as base, expect, type Page } from '@playwright/test';
import { signIn } from './signIn';

/**
 * The agent's session, for the agents specs.
 *
 * Until ticket 09 of .scratch/authentik-cutover this re-signed the agent in whenever the saved state was older than 20
 * minutes: the suite runs every staff spec first, for over half an hour, and Keycloak ended an SSO session after 30
 * minutes untouched, so the agents specs met its login page in every full run. Authentik's session lasts the sign-in
 * stage's ten hours whether used or not, so the saved state now outlives any run and the refresh is gone.
 *
 * The agents specs still import `test` from here rather than '@playwright/test', so the agent's session stays defined
 * in one place.
 */
export const AGENT_STATE = 'e2e/.auth/agent.json';
const AGENT_USER = 'agent.senior';

/** The real sign-in, for auth-agent.setup.ts. Agents land on the Dashboard since 2026-10-14, not "My profile". */
export async function signInAsAgent(page: Page): Promise<void> {
  await signIn(page, { console: '/agents', username: AGENT_USER, state: AGENT_STATE });
}

export const test = base.extend({ storageState: AGENT_STATE });

export { expect };
