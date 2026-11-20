import { test as setup } from '@playwright/test';
import { signIn } from './signIn';

/**
 * Signs in as the CLAIMS_ASSESSOR-only staff user, for tests that need a real
 * separation-of-duties boundary against a CLAIMS_MANAGER identity -- see
 * auth.setup.ts for the shared rationale (real Authentik, no fabricated JWT,
 * storageState captures the session cookie not app tokens).
 *
 * `staff.underwriter` (the default `staff` project identity) carries only the
 * UNDERWRITER role -- no claims role at all -- so claims adjudication e2e
 * coverage needs its own two single-role identities, matching how the backend
 * itself defines them (`deploy/authentik/blueprints/dev/dev-users.yaml`: one job role per user).
 */
const ASSESSOR_USER = 'staff.assessor';

setup(`authenticate as ${ASSESSOR_USER}`, async ({ page }) => {
  await signIn(page, {
    console: '/staff',
    username: ASSESSOR_USER,
    state: 'e2e/.auth/staff-assessor.json',
  });
});
