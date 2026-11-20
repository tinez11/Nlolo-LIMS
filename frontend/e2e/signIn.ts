import { expect, type APIRequestContext, type Locator, type Page } from '@playwright/test';

/**
 * Every sign-in the suite makes, through the REAL Authentik (ticket 09 of .scratch/authentik-cutover): the browser's,
 * on Authentik's own sign-in page, and the API's, with Authentik's password grant. No mocked provider and no fabricated
 * JWT, anywhere -- see playwright.config.ts for why that is a rule.
 *
 * The page is found by what it says, not its markup: "Username", "Password" and "Log in". Authentik draws it in shadow
 * DOM, which Playwright's role and label locators pierce and CSS ids do not.
 *
 * What a saved storageState holds is Authentik's session cookie, not app tokens: the console keeps tokens in memory
 * only, so each page load of a spec signs in again, silently, against that cookie. Authentik's session lasts the
 * sign-in stage's ten hours (the production blueprint), so a long run no longer outlives it as Keycloak's 30-minute idle
 * timeout did.
 */

export const AUTHENTIK = 'http://localhost:9100';
const APP = 'http://localhost:5173';
/** Every dev test user's password (deploy/authentik/blueprints/dev/dev-users.yaml). */
export const DEV_PASSWORD = 'devpassword';

/** Fills Authentik's sign-in page and submits it. The page must already be on its way there. */
export async function fillSignIn(page: Page, username: string, password: string = DEV_PASSWORD): Promise<void> {
  await page.getByLabel('Username').fill(username, { timeout: 30_000 });
  await page.getByLabel('Password').fill(password);
  await page.getByRole('button', { name: 'Log in' }).click();
}

export interface SignInOptions {
  /** The console to open: '/staff', '/agents' or '/customers'. */
  console: string;
  username: string;
  password?: string;
  /** What the console shows once the person is in. The Dashboard heading unless said otherwise. */
  landing?: (page: Page) => Locator;
  /** Where to save the session for the specs that run as this person. */
  state?: string;
}

const dashboard = (page: Page) => page.getByRole('heading', { name: 'Dashboard', exact: true });

/**
 * Opens a console, which sends the browser to Authentik; signs in there; waits to be back in that console with its
 * landing page drawn; and saves the session when asked to.
 *
 * If this fails on Authentik's page, the usual causes are: the dev stack is down (Authentik on :9100), the dev
 * blueprint has not been applied, or the provider's redirect address does not cover :5173.
 */
export async function signIn(page: Page, options: SignInOptions): Promise<void> {
  await page.goto(options.console);
  await page.waitForURL((url) => url.origin === AUTHENTIK, { timeout: 30_000, waitUntil: 'domcontentloaded' });
  await fillSignIn(page, options.username, options.password);
  await page.waitForURL((url) => url.origin === APP && url.pathname.startsWith(options.console), { timeout: 30_000 });
  await expect((options.landing ?? dashboard)(page)).toBeVisible({ timeout: 30_000 });
  if (options.state) await page.context().storageState({ path: options.state });
}

/**
 * A real access token for a dev test user, for the specs that build their fixtures over the API.
 *
 * Authentik's password grant takes an app-password token in place of the login's password. The dev blueprint gives each
 * test user one, keyed `dev-app-password-<username>`, and allows the grant on the console's client in dev only -- the
 * production blueprint lists the code flow and refresh and nothing else. The token carries the same claims a browser
 * sign-in's does: the same client, the same mappings.
 */
export async function apiToken(http: APIRequestContext, username: string): Promise<string> {
  const response = await http.post(`${AUTHENTIK}/application/o/token/`, {
    form: {
      grant_type: 'password',
      client_id: 'lifeplatform-spa',
      username,
      password: `dev-app-password-${username}`,
      scope: 'openid profile lifeplatform',
    },
  });
  expect(response.ok(), `Authentik refused a token for ${username}: ${response.status()} ${await response.text()}`)
    .toBeTruthy();
  const token = ((await response.json()) as { access_token?: string }).access_token;
  if (!token) throw new Error(`No access_token for ${username}`);
  return token;
}
