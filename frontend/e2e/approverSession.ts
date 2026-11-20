import { expect, type Browser, type BrowserContext, type Page } from '@playwright/test';
import { fillSignIn } from './signIn';

/**
 * A page signed in as the finance approver, for the specs where a second person approves.
 *
 * It opens the saved session (`auth-finance-approver.setup.ts`) and signs in again if Authentik asks. Under Keycloak
 * that was routine: its SSO session ended after 30 minutes idle, and the approver's, used only late in a run, had
 * expired by then. Authentik's lasts ten hours, so the sign-in here is now only a fallback.
 */
export async function approverPage(browser: Browser): Promise<{ page: Page; context: BrowserContext }> {
  const context = await browser.newContext({ storageState: 'e2e/.auth/staff-finance-approver.json' });
  const page = await context.newPage();
  await page.goto('/staff');
  // Not the URL: /staff loads first and the console only then redirects to Authentik, so an early URL check sees the
  // console and skips the sign-in. Wait for what actually renders -- the sign-in form or the Dashboard -- and sign in
  // when it is the form.
  const login = page.getByLabel('Username');
  const landing = page.getByRole('heading', { name: 'Dashboard', exact: true });
  await expect(login.or(landing)).toBeVisible({ timeout: 30_000 });
  if (await login.isVisible()) await fillSignIn(page, 'staff.finance-approver');
  await expect(landing).toBeVisible({ timeout: 30_000 });
  return { page, context };
}
