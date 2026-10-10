import { expect, type Browser, type BrowserContext, type Page } from '@playwright/test';

/**
 * A page signed in as the finance approver, for the specs where a second person approves.
 *
 * The saved session (`auth-finance-approver.setup.ts`) is made when the run starts, and the staff realm ends an SSO
 * session after 30 minutes idle (`ssoSessionIdleTimeout` 1800). The finance session survives a long run because spec
 * after spec uses it; the approver's is used only by the few specs that need a second person, late in the run -- so
 * in a run slower than half an hour it had expired, and those specs failed on Keycloak's login page instead of on
 * anything they test. This opens the saved session and signs in again when Keycloak asks.
 */
export async function approverPage(browser: Browser): Promise<{ page: Page; context: BrowserContext }> {
  const context = await browser.newContext({ storageState: 'e2e/.auth/staff-finance-approver.json' });
  const page = await context.newPage();
  await page.goto('/staff');
  // Not the URL: /staff loads first and the console only then redirects to Keycloak, so an early URL check sees
  // the console and skips the sign-in. Wait for what actually renders -- the login form or the finance landing page
  // (the heading auth-finance-approver.setup.ts checks) -- and sign in when it is the form.
  const login = page.locator('#username');
  const landing = page.getByRole('heading', { name: 'Today', exact: true });
  await expect(login.or(landing)).toBeVisible({ timeout: 30_000 });
  if (await login.isVisible()) {
    await login.fill('staff.finance-approver');
    await page.locator('#password').fill('devpassword');
    await page.locator('#kc-login').click();
  }
  await expect(landing).toBeVisible({ timeout: 30_000 });
  return { page, context };
}
