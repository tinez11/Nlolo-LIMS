import { expect, test as setup } from '@playwright/test';

/**
 * Signs in as the ADMIN staff user.
 *
 * Needed because product authoring is now `hasRole('REALM_STAFF') and hasRole('ADMIN')`. It
 * used to be plain `REALM_STAFF`, so every product spec ran happily as `staff.underwriter`
 * (this project's default identity) — which was exactly the problem: an underwriter could
 * price a life product.
 *
 * `staff.admin` carries every staff role, so it is deliberately NOT a general-purpose identity
 * for other specs: a test that passes as admin proves nothing about who may do the thing. It is
 * used here only where ADMIN is the point.
 */
const ADMIN_USER = 'staff.admin';
const ADMIN_PASSWORD = 'devpassword';

setup(`authenticate as ${ADMIN_USER}`, async ({ page }) => {
  await page.goto('/staff');

  await page.waitForURL(/\/realms\/staff\/protocol\/openid-connect\/auth/, {
    timeout: 30_000,
  });

  await page.locator('#username').fill(ADMIN_USER);
  await page.locator('#password').fill(ADMIN_PASSWORD);
  await page.locator('#kc-login').click();

  await page.waitForURL(/localhost:5173\/staff/, { timeout: 30_000 });
  await expect(page.getByRole('heading', { name: 'Policies' })).toBeVisible();

  await page.context().storageState({ path: 'e2e/.auth/staff-admin.json' });
});
