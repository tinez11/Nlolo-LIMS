import { expect, test as setup } from '@playwright/test';

/**
 * Signs in as the FINANCE_OFFICER-only staff user -- onboarding an agent,
 * authoring a commission plan, and requesting a payout are all gated on
 * FINANCE_OFFICER/ADMIN, which `staff.underwriter` (this project's default
 * identity) does not carry. See auth-assessor.setup.ts for the shared
 * rationale on why this console needs more than one real seeded identity.
 */
const FINANCE_USER = 'staff.finance';
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
  // Every staff role lands on Today since 2026-10-09 (review C5).
  await expect(page.getByRole('heading', { name: 'Today', exact: true })).toBeVisible();

  await page.context().storageState({ path: 'e2e/.auth/staff-finance.json' });
});
