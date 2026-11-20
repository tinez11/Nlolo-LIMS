import { test as setup } from '@playwright/test';
import { signIn } from './signIn';

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

setup(`authenticate as ${ADMIN_USER}`, async ({ page }) => {
  await signIn(page, {
    console: '/staff',
    username: ADMIN_USER,
    state: 'e2e/.auth/staff-admin.json',
  });
});
