import { expect, type Page } from '@playwright/test';

/**
 * Helpers for role-gating tests -- the shape that asserts an identity CANNOT see or
 * reach something.
 *
 * These exist because the obvious spelling is wrong in two ways at once, and four
 * tests wrote it independently:
 *
 *   await page.goto('/staff/policies');
 *   await expect(page.getByRole('link', { name: 'Treaties' })).not.toBeVisible();
 *   await page.goto('/staff/treaties');
 *   await expect(page.getByText('You do not have access to this')).toBeVisible();
 *
 * 1. The absence assertion is VACUOUS. While the app is signing in it renders no
 *    sidebar at all, so "Treaties is not visible" is satisfied by the nav not
 *    existing yet. The assertion passes without ever testing the gate -- it would
 *    keep passing if the gate were removed entirely.
 * 2. The second `goto` races the silent SSO renew, whose redirect to Keycloak
 *    aborts an in-flight navigation with net::ERR_ABORTED. Where it does not abort,
 *    the guard page has not rendered inside the default 10s budget.
 *
 * Both are fixed by anchoring on something that must be PRESENT before asserting
 * anything absent, and by budgeting for a Keycloak round trip.
 */

/** Every staff identity can see Policies, so it is the anchor that proves the shell rendered. */
const STAFF_ANCHOR = 'Policies';

/**
 * Waits until the staff console has actually finished signing in and rendered its
 * nav. Call this before any `not.toBeVisible()` on a nav item, or the assertion
 * proves nothing.
 */
export async function expectStaffShellReady(page: Page): Promise<void> {
  await expect(page.getByRole('link', { name: STAFF_ANCHOR })).toBeVisible({ timeout: 30_000 });
}

/**
 * Asserts the given nav items are absent, having first proved the nav is on screen.
 * Anchoring is not optional: without it this is the vacuous form described above.
 */
export async function expectNavItemsHidden(page: Page, ...labels: string[]): Promise<void> {
  await expectStaffShellReady(page);
  for (const label of labels) {
    await expect(page.getByRole('link', { name: label })).not.toBeVisible();
  }
}

/**
 * Navigates to a route this identity is not entitled to and asserts the guard page.
 * The 30s budget covers a navigation that lands mid silent-SSO-renew.
 */
export async function expectRouteDenied(page: Page, path: string): Promise<void> {
  await page.goto(path);
  await expect(page.getByText('You do not have access to this')).toBeVisible({ timeout: 30_000 });
}
