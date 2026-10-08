import { expect, test, type Locator, type Page } from '@playwright/test';
import { issueRealPolicy } from './policies';

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
    // Coverage, not Invoices: the record is tabbed now and this assertion exists to prove
    // the drawer led to the FULL record rather than another summary. Coverage is what the
    // default tab opens on, and it is the thing the drawer deliberately does not carry.
    await expect(page.getByRole('heading', { name: 'Coverage' })).toBeVisible();
    // The registers are one click away rather than one scroll. Asserting a TAB exists is
    // the same proof the old `Loans` heading gave -- that this is the record and not the
    // preview. Billing, because every policy has one; Loans now shows only where a loan is
    // possible (audit 2026-10-07), and the first policy in the list may be any kind.
    await expect(page.getByRole('tab', { name: 'Billing' })).toBeVisible();
  });

  test('a term policy offers no surrender at all, because it has nothing to surrender', async ({
    page,
  }) => {
    // Its own term policy -- an underwriting case, a second underwriter's decision and a manual
    // issue -- before the assertion can run.
    test.setTimeout(180_000);
    // Issued fresh rather than taken from the top of the register. "Every seeded policy is term
    // business" stopped being true when the money-back fixtures arrived: they sort newest-first,
    // carry a cash value, and so CORRECTLY show the Value panel this test asserts is absent.
    const policyNumber = await issueRealPolicy(page, 'E2E fixture: a term policy has nothing to surrender');
    await page.goto(`/staff/policies/${policyNumber}`);

    /*
      This asserted a disabled "Surrender" button while POST /policies/{n}/surrender was a 501.
      Product step 1 made surrender real, and the action moved into the Value panel -- which only
      a savings product gets, because a term policy never has a cash value to cash in. So the
      honest assertion is that the panel is absent here. A disabled button for a product that can
      never qualify would be offering an act that is not merely unavailable but meaningless.
    */
    await expect(page.getByRole('heading', { name: 'Lifecycle' })).toBeVisible();
    await expect(page.getByRole('heading', { name: 'Value' })).toHaveCount(0);
    await expect(page.getByRole('button', { name: /surrender/i })).toHaveCount(0);
  });

  test('a filter is shareable through the URL', async ({ page }) => {
    await page.goto('/staff/policies?status=ACTIVE');
    await expect(page.getByRole('heading', { name: 'Policies' })).toBeVisible();
    // The chip reflects state restored from the URL, not just local component state.
    await expect(page.getByRole('button', { name: 'Active', pressed: true })).toBeVisible();
  });

  test('sends no Authorization header to an unauthenticated route', async ({ page }) => {
    // The realm picker is public; leaking a bearer token to it would mean the
    // interceptor is attaching tokens indiscriminately, so this actually inspects
    // outgoing requests -- checking only page content, as an earlier version of
    // this test did, would pass whether or not the interceptor misbehaved.
    const authorizedRequests: string[] = [];
    page.on('request', (request) => {
      if (request.headers()['authorization']) {
        authorizedRequests.push(`${request.method()} ${request.url()}`);
      }
    });

    await page.goto('/');
    await expect(page.getByRole('heading', { name: 'Life Platform' })).toBeVisible();
    await expect(page.getByRole('link', { name: /staff/i })).toBeVisible();

    expect(authorizedRequests).toEqual([]);
  });
});

test('Ctrl+K goes to a screen', async ({ page }) => {
  await page.goto('/staff/policies');
  await expect(page.getByRole('heading', { name: 'Policies', exact: true })).toBeVisible({
    timeout: 30_000,
  });
  await page.keyboard.press('Control+K');
  await page.getByRole('combobox').fill('claims');
  await page.keyboard.press('Enter');
  await expect(page).toHaveURL(/\/staff\/claims/);
});
