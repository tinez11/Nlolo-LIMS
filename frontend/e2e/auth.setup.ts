import { expect, test as setup } from '@playwright/test';

/**
 * Signs in through the REAL Keycloak realm (`lifeplatform`, one for everyone since
 * 2026-11-06), into the staff workspace. No mocked provider, no fabricated JWT.
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
 * public client is missing from the lifeplatform realm, its redirect URI does not cover
 * :5173, or the realm import has not been re-run since that client was added.
 */
const STAFF_USER = 'staff.underwriter';
const STAFF_PASSWORD = 'devpassword';

setup(`authenticate as ${STAFF_USER}`, async ({ page }) => {
  await page.route(/fonts\.(googleapis|gstatic)\.com/, (route) => route.abort()); // Keycloak's fonts can stall the sign-in page; it signs in fine without them
  await page.goto('/staff');

  // RequireAuth fires signinRedirect on mount, so we should land on Keycloak.
  await page.waitForURL(/\/realms\/lifeplatform\/protocol\/openid-connect\/auth/, {
    timeout: 30_000,
    // The form, not every resource: the login theme's Google Fonts can take 30s+ from here, so "load" may never come.
    waitUntil: 'domcontentloaded',
  });

  await page.locator('#username').fill(STAFF_USER);
  await page.locator('#password').fill(STAFF_PASSWORD);
  await page.locator('#kc-login').click();

  // Back inside the app, with the ?code= stripped by onSigninCallback.
  await page.waitForURL(/localhost:5173\/staff/, { timeout: 30_000 });
  // Every staff role lands on the Dashboard (2026-10-14); it replaced Today, which replaced the module landings (review C5).
  await expect(page.getByRole('heading', { name: 'Dashboard', exact: true })).toBeVisible();

  await page.context().storageState({ path: 'e2e/.auth/staff.json' });
});
