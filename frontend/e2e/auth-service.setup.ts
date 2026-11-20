import { test as setup } from '@playwright/test';
import { signIn } from './signIn';

/**
 * Signs in as the CUSTOMER_SERVICE_REP-only staff user (2026-10-26). Since staff permissions, customer service owns
 * work no other seeded login holds alone -- servicing policies, KYC, preparing a free-look cancellation that finance
 * then releases -- so the specs proving that split need this identity. See auth.setup.ts for the shared rationale.
 *
 * Customer service has no dashboard, so it lands on the KYC queue rather than the Dashboard heading the other setups
 * wait for; the badge's one role is what says the session is theirs.
 */
const SERVICE_USER = 'staff.service';

setup(`authenticate as ${SERVICE_USER}`, async ({ page }) => {
  await signIn(page, {
    console: '/staff',
    username: SERVICE_USER,
    landing: (p) => p.getByText('Customer service', { exact: true }),
    state: 'e2e/.auth/staff-service.json',
  });
});
