import { test as setup } from '@playwright/test';
import { signIn } from './signIn';

/**
 * Signs in as the Management-only staff user (2026-11-12, analytics plan 5): sees every area and the overview, and
 * changes nothing. Seeded by deploy/authentik/blueprints/dev/dev-users.yaml.
 */
const MANAGEMENT_USER = 'staff.management';

setup(`authenticate as ${MANAGEMENT_USER}`, async ({ page }) => {
  await signIn(page, {
    console: '/staff',
    username: MANAGEMENT_USER,
    state: 'e2e/.auth/staff-management.json',
  });
});
