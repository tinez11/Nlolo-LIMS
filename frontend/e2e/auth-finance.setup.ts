import { test as setup } from '@playwright/test';
import { signIn } from './signIn';

/**
 * Signs in as the FINANCE_OFFICER-only staff user -- onboarding an agent,
 * authoring a commission plan, and requesting a payout are all gated on
 * FINANCE_OFFICER/ADMIN, which `staff.underwriter` (this project's default
 * identity) does not carry. See auth-assessor.setup.ts for the shared
 * rationale on why this console needs more than one real seeded identity.
 */
const FINANCE_USER = 'staff.finance';

setup(`authenticate as ${FINANCE_USER}`, async ({ page }) => {
  await signIn(page, {
    console: '/staff',
    username: FINANCE_USER,
    state: 'e2e/.auth/staff-finance.json',
  });
});
