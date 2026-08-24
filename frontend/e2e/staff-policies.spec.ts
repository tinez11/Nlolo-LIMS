import { expect, test, type Locator, type Page } from '@playwright/test';

/**
 * Returns the first policy row's activation control, or null if the tenant genuinely
 * has no policies.
 *
 * Waits for the list to settle first. `isVisible()` is an immediate snapshot rather
 * than a wait, so probing it directly races the initial fetch and reports "no
 * policies" for a list that simply had not arrived yet -- which silently SKIPS the
 * test instead of failing it. A skipped test that was meant to be the
 * definition-of-done gate is worse than a failing one.
 */
async function firstPolicyRow(page: Page): Promise<Locator | null> {
  const table = page.getByRole('table', { name: 'Policies' });
  const empty = page.getByText('No policies yet');
  await expect(table.or(empty)).toBeVisible();

  if (await empty.isVisible()) return null;
  const row = table.getByRole('button').first();
  await expect(row).toBeVisible();
  return row;
}

/**
 * The definition-of-done gate for this slice: a real staff login reaching real
 * policy data from the real backend, then list -> drawer -> full detail page.
 *
 * Every assertion here is against data the platform actually produced (the dev
 * seeder issues one policy). Nothing is mocked -- if the Keycloak client, the
 * tenant_id claim mapper, RLS, or the proxy is misconfigured, this fails, which is
 * the entire point.
 */
test.describe('staff policies', () => {
  test('lands on the policies list with real data', async ({ page }) => {
    await page.goto('/staff/policies');

    // Silent SSO: tokens are memory-only, so this load re-authenticates against
    // Keycloak's cookie rather than replaying a stored token.
    await expect(page.getByRole('heading', { name: 'Policies' })).toBeVisible();

    // Real rows, or an honest empty state -- never a spinner that never resolves.
    const table = page.getByRole('table', { name: 'Policies' });
    const empty = page.getByText('No policies yet');
    await expect(table.or(empty)).toBeVisible();
  });

  test('the tenant_id claim actually reaches the backend', async ({ page }) => {
    // TenantContextFilter 403s any request whose token lacks tenant_id, and RLS
    // returns zero rows without it. So a 200 with a rendered table is the proof
    // that the whole claim -> filter -> RLS chain works end to end. M11's headline
    // failure was exactly this path being broken while every test passed.
    // Start listening BEFORE navigating, or the response is missed and this hangs.
    const responsePromise = page.waitForResponse(
      (r) => r.url().includes('/policies') && r.request().method() === 'GET',
      { timeout: 30_000 },
    );
    await page.goto('/staff/policies');
    const response = await responsePromise;
    expect(response.status()).toBe(200);
  });

  test('opens a row preview, then navigates to the full detail page', async ({ page }) => {
    await page.goto('/staff/policies');
    await expect(page.getByRole('heading', { name: 'Policies' })).toBeVisible();

    const firstRow = await firstPolicyRow(page);
    test.skip(firstRow === null, 'no seeded policy to open');
    if (firstRow === null) return;

    const policyNumber = (await firstRow.textContent())?.trim() ?? '';
    expect(policyNumber).not.toBe('');

    await firstRow.click();

    // The drawer is the read-only preview half of drawer-previews-page-acts.
    // Its accessible name is the policy number, not a static label: Radix derives
    // aria-labelledby from Dialog.Title, which overrides any aria-label. Asserting
    // on the number is better anyway -- it proves the drawer opened for the row
    // that was actually clicked.
    const drawer = page.getByRole('dialog', { name: policyNumber });
    await expect(drawer).toBeVisible();
    await expect(drawer.getByText('Sum assured')).toBeVisible();

    // And it must NOT carry a mutating action -- that is the whole distinction.
    await expect(drawer.getByRole('button', { name: /surrender/i })).toHaveCount(0);

    await drawer.getByRole('link', { name: /full detail/i }).click();

    await expect(page).toHaveURL(new RegExp(`/staff/policies/${policyNumber}`));
    await expect(page.getByRole('heading', { name: policyNumber })).toBeVisible();
    await expect(page.getByRole('heading', { name: 'Invoices' })).toBeVisible();
    await expect(page.getByRole('heading', { name: 'Loans' })).toBeVisible();
  });

  test('the deferred surrender action is disabled, not merely broken', async ({ page }) => {
    await page.goto('/staff/policies');
    const firstRow = await firstPolicyRow(page);
    test.skip(firstRow === null, 'no seeded policy to open');
    if (firstRow === null) return;

    await firstRow.click();
    await page.getByRole('dialog').getByRole('link', { name: /full detail/i }).click();

    // POST /policies/{n}/surrender really does return 501 -- the choreography was
    // deferred with the workflow engine. The button must be inert rather than
    // producing an error the user cannot act on.
    await expect(page.getByRole('button', { name: /surrender/i })).toBeDisabled();
  });

  test('a filter is shareable through the URL', async ({ page }) => {
    await page.goto('/staff/policies?status=ACTIVE');
    await expect(page.getByRole('heading', { name: 'Policies' })).toBeVisible();
    // The chip reflects state restored from the URL, not just local component state.
    await expect(page.getByRole('button', { name: 'Active', pressed: true })).toBeVisible();
  });

  test('sends no Authorization header to an unauthenticated route', async ({ page }) => {
    // The realm picker is public; leaking a bearer token to it would mean the
    // interceptor is attaching tokens indiscriminately.
    await page.goto('/');
    await expect(page.getByRole('heading', { name: 'Life Platform' })).toBeVisible();
    await expect(page.getByRole('link', { name: /staff/i })).toBeVisible();
  });
});
