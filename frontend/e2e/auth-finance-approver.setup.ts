import { test as setup } from '@playwright/test';
import { signIn } from './signIn';

/**
 * Signs in as the finance approver (IFRS 17 I4): FINANCE_OFFICER and FINANCE_APPROVER, the only role that approves
 * or rejects a manual journal -- and never one it prepared, which is why it is a second, separate identity from
 * staff.finance. Seeded by deploy/authentik/blueprints/dev/dev-users.yaml.
 */
const FINANCE_USER = 'staff.finance-approver';

setup(`authenticate as ${FINANCE_USER}`, async ({ page }) => {
  await signIn(page, {
    console: '/staff',
    username: FINANCE_USER,
    state: 'e2e/.auth/staff-finance-approver.json',
  });
});
