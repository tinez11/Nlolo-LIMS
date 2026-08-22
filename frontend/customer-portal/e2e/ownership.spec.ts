import { test, expect } from '@playwright/test';

/**
 * `customer.other` is a real, correctly-issued Keycloak identity (`tenant_id` and a synthetic
 * `party_id` matching no real `party.party` row) that owns NOTHING. The positive owner-path specs
 * can only prove data flows when a scope filter happens to match the caller's own records; they
 * cannot catch a scope filter that is missing or backwards and would otherwise hand back another
 * customer's policies and claims. This spec is the one that can.
 *
 * Runs with its own real Keycloak login, inline, deliberately ignoring the project-level
 * `storageState: 'e2e/.auth/owner.json'` (this file overrides it to a blank state below) --
 * `customer.other` is a second, independent identity, not a variation on the owner's session.
 */
test.use({ storageState: { cookies: [], origins: [] } });

test("customer.other sees no policies or claims, not another customer's data", async ({ page }) => {
  await page.goto('/');
  await page.getByRole('button', { name: /sign in with keycloak/i }).click();
  await page.locator('#username').fill('customer.other');
  await page.locator('#password').fill('devpassword');
  await page.locator('#kc-login').click();

  await expect(page).toHaveURL('http://localhost:3000/');
  await expect(page.getByText('No policies to show yet.')).toBeVisible();

  await page.goto('/claims');
  await expect(page.getByText('No claims to show yet.')).toBeVisible();
});
