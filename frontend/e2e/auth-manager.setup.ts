import { expect, test as setup } from '@playwright/test';

/**
 * Signs in as the CLAIMS_MANAGER-only staff user -- see auth-assessor.setup.ts
 * for why a second, single-role identity is needed alongside the default
 * `staff` project's `staff.underwriter`.
 */
const MANAGER_USER = 'staff.manager';
const MANAGER_PASSWORD = 'devpassword';

setup(`authenticate as ${MANAGER_USER}`, async ({ page }) => {
  await page.goto('/staff');

  await page.waitForURL(/\/realms\/staff\/protocol\/openid-connect\/auth/, {
    timeout: 30_000,
  });

  await page.locator('#username').fill(MANAGER_USER);
  await page.locator('#password').fill(MANAGER_PASSWORD);
  await page.locator('#kc-login').click();

  await page.waitForURL(/localhost:5173\/staff/, { timeout: 30_000 });
  // Every staff role lands on Today since 2026-10-09 (review C5).
  await expect(page.getByRole('heading', { name: 'Today', exact: true })).toBeVisible();

  await page.context().storageState({ path: 'e2e/.auth/staff-manager.json' });
});
