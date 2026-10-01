import { statSync } from 'node:fs';
import { expect, test as base, type Page } from '@playwright/test';

/**
 * The agent's Keycloak session, kept alive across a long run.
 *
 * Why this exists: the suite runs on ONE worker, so every agents spec runs after every staff spec
 * -- over half an hour of them now. The setup project signs the agent in at the very start, and
 * Keycloak's SSO session idles out after 30 minutes untouched (no realm overrides the default). So
 * by the time the agents project ran, its saved cookie led to the login page, and all seven agents
 * tests failed on a navigation to `openid-connect/auth` -- in every full run, while each passed
 * alone. That is a property of the run's length, not of the agent console, and it must not be
 * allowed to look like one.
 *
 * The agents specs import `test` from here instead of '@playwright/test'. It re-signs the agent in
 * whenever the saved state is older than REFRESH_AFTER_MS, which is inside the idle window, so a
 * stale cookie is never handed to a test. Still the real realm and the real form -- no fabricated
 * token, same rule as auth.setup.ts.
 */
export const AGENT_STATE = 'e2e/.auth/agent.json';
const AGENT_USER = 'agent.senior';
const AGENT_PASSWORD = 'devpassword';
/** Inside Keycloak's 30-minute idle timeout, with margin for a slow spec. */
const REFRESH_AFTER_MS = 20 * 60 * 1000;

/** The real sign-in, shared by auth-agent.setup.ts and the refresh below. */
export async function signInAsAgent(page: Page): Promise<void> {
  await page.goto('/agents');
  await page.waitForURL(/\/realms\/agents\/protocol\/openid-connect\/auth/, { timeout: 30_000 });
  await page.locator('#username').fill(AGENT_USER);
  await page.locator('#password').fill(AGENT_PASSWORD);
  await page.locator('#kc-login').click();
  await page.waitForURL(/localhost:5173\/agents/, { timeout: 30_000 });
  await expect(page.getByRole('heading', { name: 'My profile' })).toBeVisible();
  await page.context().storageState({ path: AGENT_STATE });
}

function isStale(path: string): boolean {
  try {
    return Date.now() - statSync(path).mtimeMs > REFRESH_AFTER_MS;
  } catch {
    return true; // never written
  }
}

export const test = base.extend({
  // `provide`, not Playwright's usual `use`: the console's lint reads `use(...)` as a React hook.
  storageState: async ({ browser }, provide) => {
    if (isStale(AGENT_STATE)) {
      const context = await browser.newContext({ baseURL: 'http://localhost:5173' });
      try {
        await signInAsAgent(await context.newPage());
      } finally {
        await context.close();
      }
    }
    await provide(AGENT_STATE);
  },
});

export { expect };
