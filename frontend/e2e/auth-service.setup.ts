import { expect, test as setup } from '@playwright/test';

/**
 * Signs in as the CUSTOMER_SERVICE_REP-only staff user (2026-10-26). Since staff permissions, customer service owns
 * work no other seeded login holds alone -- servicing policies, KYC, preparing a free-look cancellation that finance
 * then releases -- so the specs proving that split need this identity. See auth.setup.ts for the shared rationale.
 *
 * Customer service has no dashboard, so it lands on the KYC queue rather than the Dashboard heading the other setups
 * wait for; the badge's one role is what says the session is theirs.
 */
const SERVICE_USER = 'staff.service';
const SERVICE_PASSWORD = 'devpassword';

setup(`authenticate as ${SERVICE_USER}`, async ({ page }) => {
  await page.goto('/staff');

  await page.waitForURL(/\/realms\/staff\/protocol\/openid-connect\/auth/, {
    timeout: 30_000,
  });

  await page.locator('#username').fill(SERVICE_USER);
  await page.locator('#password').fill(SERVICE_PASSWORD);
  await page.locator('#kc-login').click();

  await page.waitForURL(/localhost:5173\/staff/, { timeout: 30_000 });
  await expect(page.getByText('Customer service', { exact: true })).toBeVisible({ timeout: 30_000 });

  await page.context().storageState({ path: 'e2e/.auth/staff-service.json' });
});
