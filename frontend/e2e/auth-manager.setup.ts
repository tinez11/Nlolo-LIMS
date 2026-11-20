import { test as setup } from '@playwright/test';
import { signIn } from './signIn';

/**
 * Signs in as the CLAIMS_MANAGER-only staff user -- see auth-assessor.setup.ts
 * for why a second, single-role identity is needed alongside the default
 * `staff` project's `staff.underwriter`.
 */
const MANAGER_USER = 'staff.manager';

setup(`authenticate as ${MANAGER_USER}`, async ({ page }) => {
  await signIn(page, {
    console: '/staff',
    username: MANAGER_USER,
    state: 'e2e/.auth/staff-manager.json',
  });
});
