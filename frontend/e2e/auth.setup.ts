import { expect, test as setup } from '@playwright/test';

/**
 * Signs in through the REAL Keycloak `staff` realm. No mocked provider, no
 * fabricated JWT.
 *
 * Adapted from the deleted customer-portal's own auth.setup.ts, which proved this
 * out for the `customers` realm. Two differences now:
 *
 *  - ONE hop, not two. The old portal bounced through NextAuth's own sign-in page
 *    first; this SPA redirects straight to Keycloak from RequireAuth.
 *  - What storageState captures is Keycloak's SSO cookie, NOT app tokens, because
 *    the SPA holds tokens in memory only. That makes every subsequent test a live
 *    exercise of the silent-SSO path rather than a replay of a saved session.
 *
 * If this fails at the Keycloak form, the usual causes are: the `lifeplatform-spa`
 * public client is missing from the staff realm, its redirect URI does not cover
 * :5173, or the realm import has not been re-run since that client was added.
 */
const STAFF_USER = 'staff.underwriter';
const STAFF_PASSWORD = 'devpassword';

setup(`authenticate as ${STAFF_USER}`, async ({ page }) => {
  await page.goto('/staff');

  // RequireAuth fires signinRedirect on mount, so we should land on Keycloak.
  await page.waitForURL(/\/realms\/staff\/protocol\/openid-connect\/auth/, {
    timeout: 30_000,
  });

  await page.locator('#username').fill(STAFF_USER);
  await page.locator('#password').fill(STAFF_PASSWORD);
  await page.locator('#kc-login').click();

  // Back inside the app, with the ?code= stripped by onSigninCallback.
  await page.waitForURL(/localhost:5173\/staff/, { timeout: 30_000 });
  await expect(page.getByRole('heading', { name: 'Policies' })).toBeVisible();

  await page.context().storageState({ path: 'e2e/.auth/staff.json' });
});
