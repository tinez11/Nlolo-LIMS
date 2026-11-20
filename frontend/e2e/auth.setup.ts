import { test as setup } from '@playwright/test';
import { signIn } from './signIn';

/**
 * Signs in through the REAL Authentik (one sign-in for every workspace since 2026-11-06; Authentik since 2026-11-19),
 * into the staff workspace. No mocked provider, no fabricated JWT.
 *
 * What storageState captures is Authentik's session cookie, NOT app tokens, because the SPA holds tokens in memory
 * only. That makes every subsequent test a live exercise of the silent sign-in path rather than a replay of a saved
 * session. The sign-in itself, and what to check when it fails, is in ./signIn.
 */
const STAFF_USER = 'staff.underwriter';

setup(`authenticate as ${STAFF_USER}`, async ({ page }) => {
  await signIn(page, {
    console: '/staff',
    username: STAFF_USER,
    state: 'e2e/.auth/staff.json',
  });
});
