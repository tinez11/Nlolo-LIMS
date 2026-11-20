import { test as setup } from '@playwright/test';
import { signIn } from './signIn';

/**
 * Signs in as `staff.senior` (UNDERWRITER + SENIOR_UNDERWRITER): the SECOND underwriter every
 * decision now needs.
 *
 * Separation of duties: whoever opened a case or recorded an assessment on it may not decide
 * it. The default `staff` identity (`staff.underwriter`) opens and assesses in most specs, so
 * the decision has to come from somebody else -- the same shape claims adjudication already has
 * with its assessor and manager identities. See auth-assessor.setup.ts for the shared rationale
 * (real Authentik, storageState captures the session cookie, not app tokens).
 */
const SENIOR_USER = 'staff.senior';

setup(`authenticate as ${SENIOR_USER}`, async ({ page }) => {
  await signIn(page, {
    console: '/staff',
    username: SENIOR_USER,
    state: 'e2e/.auth/staff-senior.json',
  });
});
