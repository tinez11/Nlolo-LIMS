import { test as setup, expect } from '@playwright/test';

/**
 * Logs in through the REAL Keycloak `customers` realm -- no mocked auth provider. M11's headline
 * lesson was that fabricated test identities hid a completely broken real credential path twice
 * (the M1 database role and the M11 Keycloak mappers), both with a fully green suite. This test
 * exists to make that class of failure impossible to miss again.
 *
 * The flow has two real hops, not one: `/` redirects an unauthenticated visit to NextAuth's OWN
 * sign-in page (`middleware.ts` -> `/api/auth/signin`), which renders one button per configured
 * provider ("Sign in with Keycloak"), not a username/password form. Only after that button submits
 * does the browser land on Keycloak's actual login form (`#username`, `#password`, `#kc-login`).
 */
setup('authenticate as customer.owner', async ({ page }) => {
  await page.goto('/');

  // Hop 1: NextAuth's own sign-in page.
  await page.getByRole('button', { name: /sign in with keycloak/i }).click();

  // Hop 2: Keycloak's real login form, `customers` realm.
  await page.locator('#username').fill('customer.owner');
  await page.locator('#password').fill('devpassword');
  await page.locator('#kc-login').click();

  await expect(page).toHaveURL('http://localhost:3000/');
  await page.context().storageState({ path: 'e2e/.auth/owner.json' });
});
