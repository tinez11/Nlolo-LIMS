import { expect, test as setup } from '@playwright/test';

/**
 * Signs in as the finance approver (IFRS 17 I4): FINANCE_OFFICER and FINANCE_APPROVER, the only role that approves
 * or rejects a manual journal -- and never one it prepared, which is why it is a second, separate identity from
 * staff.finance. Seeded by keycloak/staff-realm.json, and added to a running dev Keycloak by
 * backend/scripts/apply-finance-approver.sh.
 */
const FINANCE_USER = 'staff.finance-approver';
const FINANCE_PASSWORD = 'devpassword';

setup(`authenticate as ${FINANCE_USER}`, async ({ page }) => {
  await page.goto('/staff');

  await page.waitForURL(/\/realms\/staff\/protocol\/openid-connect\/auth/, {
    timeout: 30_000,
  });

  await page.locator('#username').fill(FINANCE_USER);
  await page.locator('#password').fill(FINANCE_PASSWORD);
  await page.locator('#kc-login').click();

  await page.waitForURL(/localhost:5173\/staff/, { timeout: 30_000 });
  await expect(page.getByRole('heading', { name: 'Arrears', exact: true })).toBeVisible();

  await page.context().storageState({ path: 'e2e/.auth/staff-finance-approver.json' });
});
