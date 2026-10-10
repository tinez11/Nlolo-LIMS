import { expect, test as setup } from '@playwright/test';

/**
 * Signs in as the CLAIMS_ASSESSOR-only staff user, for tests that need a real
 * separation-of-duties boundary against a CLAIMS_MANAGER identity -- see
 * auth.setup.ts for the shared rationale (real Keycloak, no fabricated JWT,
 * storageState captures the SSO cookie not app tokens).
 *
 * `staff.underwriter` (the default `staff` project identity) carries only the
 * UNDERWRITER role -- no claims role at all -- so claims adjudication e2e
 * coverage needs its own two single-role identities, matching how the backend
 * itself defines them (`backend/keycloak/staff-realm.json`: one role per user).
 */
const ASSESSOR_USER = 'staff.assessor';
const ASSESSOR_PASSWORD = 'devpassword';

setup(`authenticate as ${ASSESSOR_USER}`, async ({ page }) => {
  await page.goto('/staff');

  await page.waitForURL(/\/realms\/staff\/protocol\/openid-connect\/auth/, {
    timeout: 30_000,
  });

  await page.locator('#username').fill(ASSESSOR_USER);
  await page.locator('#password').fill(ASSESSOR_PASSWORD);
  await page.locator('#kc-login').click();

  await page.waitForURL(/localhost:5173\/staff/, { timeout: 30_000 });
  // Every staff role lands on Today since 2026-10-09 (review C5).
  await expect(page.getByRole('heading', { name: 'Today', exact: true })).toBeVisible();

  await page.context().storageState({ path: 'e2e/.auth/staff-assessor.json' });
});
