import { expect, test as setup } from '@playwright/test';

/**
 * Signs in as the Management-only staff user (2026-11-12, analytics plan 5): sees every area and the overview, and
 * changes nothing. Created on dev with the Keycloak admin tool; fresh installs get it from lifeplatform-realm.json.
 */
const MANAGEMENT_USER = 'staff.management';
const MANAGEMENT_PASSWORD = 'devpassword';

setup(`authenticate as ${MANAGEMENT_USER}`, async ({ page }) => {
  await page.route(/fonts\.(googleapis|gstatic)\.com/, (route) => route.abort()); // Keycloak's fonts can stall the sign-in page; it signs in fine without them
  await page.goto('/staff');

  await page.waitForURL(/\/realms\/lifeplatform\/protocol\/openid-connect\/auth/, {
    timeout: 30_000,
    waitUntil: 'domcontentloaded',
  });

  await page.locator('#username').fill(MANAGEMENT_USER);
  await page.locator('#password').fill(MANAGEMENT_PASSWORD);
  await page.locator('#kc-login').click();

  await page.waitForURL(/localhost:5173\/staff/, { timeout: 30_000 });
  await expect(page.getByRole('heading', { name: 'Dashboard', exact: true })).toBeVisible();

  await page.context().storageState({ path: 'e2e/.auth/staff-management.json' });
});
