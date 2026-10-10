import { expect, test as setup } from '@playwright/test';

/**
 * Signs in as `staff.senior` (UNDERWRITER + SENIOR_UNDERWRITER): the SECOND underwriter every
 * decision now needs.
 *
 * Separation of duties: whoever opened a case or recorded an assessment on it may not decide
 * it. The default `staff` identity (`staff.underwriter`) opens and assesses in most specs, so
 * the decision has to come from somebody else -- the same shape claims adjudication already has
 * with its assessor and manager identities. See auth-assessor.setup.ts for the shared rationale
 * (real Keycloak, storageState captures the SSO cookie, not app tokens).
 */
const SENIOR_USER = 'staff.senior';
const SENIOR_PASSWORD = 'devpassword';

setup(`authenticate as ${SENIOR_USER}`, async ({ page }) => {
  await page.goto('/staff');

  await page.waitForURL(/\/realms\/staff\/protocol\/openid-connect\/auth/, {
    timeout: 30_000,
  });

  await page.locator('#username').fill(SENIOR_USER);
  await page.locator('#password').fill(SENIOR_PASSWORD);
  await page.locator('#kc-login').click();

  await page.waitForURL(/localhost:5173\/staff/, { timeout: 30_000 });
  // Every staff role lands on Today since 2026-10-09 (review C5).
  await expect(page.getByRole('heading', { name: 'Today', exact: true })).toBeVisible();

  await page.context().storageState({ path: 'e2e/.auth/staff-senior.json' });
});
