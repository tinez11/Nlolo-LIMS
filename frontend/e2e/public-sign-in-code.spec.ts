import { expect, test } from '@playwright/test';
import { fillSignIn } from './signIn';
import { codeTextedTo } from './smsGateway';

/**
 * The staff sign-in code, end to end (ticket 05 of .scratch/authentik-cutover): the password on Authentik's page, then
 * Authentik's SMS stage asks the backend to text a code, the backend texts it through the SMS gateway (the local mock
 * here), and the code typed back opens the staff console.
 *
 * Dev runs with STAFF_SIGN_IN_CODE off, so this uses `e2e.sign-in-code`, whose group asks for the code anyway (the dev
 * blueprint). Its staff record has no phone, so the code goes to the phone on its login, +255700000099.
 *
 * The backend allows one code a minute and five an hour for each person, in memory: run this more than five times in
 * an hour and the page says "Too many codes" instead, until the hour passes or the backend restarts.
 */
test('a staff member signs in with the code texted to them', async ({ page }) => {
  const asked = Date.now();
  await page.goto('/staff');
  await fillSignIn(page, 'e2e.sign-in-code');

  const codeField = page.getByLabel('Authentication code');
  await expect(codeField).toBeVisible({ timeout: 30_000 });
  await expect(page.getByText('A one-time use code has been sent to you via SMS text message.')).toBeVisible();

  await codeField.fill(await codeTextedTo('255700000099', asked));
  await page.getByRole('button', { name: 'Continue' }).click();

  await page.waitForURL((url) => url.origin === 'http://localhost:5173' && url.pathname.startsWith('/staff'), {
    timeout: 30_000,
  });
  await expect(page.getByRole('heading', { name: 'Dashboard', exact: true })).toBeVisible({ timeout: 30_000 });
});
