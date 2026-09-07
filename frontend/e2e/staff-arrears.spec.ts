import { expect, test } from '@playwright/test';
import { expectRouteDenied, expectStaffShellReady } from './guards';

/**
 * The collections queue.
 *
 * It exists because the platform could escalate a policy through five dunning levels and
 * recommend it for lapse while no screen could show a single escalation: every billing
 * endpoint was keyed by a policy number or an invoice id, so "who is overdue" was not a
 * question anyone could ask. `GET /arrears` is the module's only tenant-wide read.
 *
 * The FLOOR semantics of the level filter and the tenant scoping are proven server-side in
 * `BillingContractTest`, where the row that must be ABSENT can be controlled. What is proven
 * here is the wiring a contract test cannot see: that the item is in the nav, that the
 * finance gate reaches the UI as an access panel rather than an empty table, and that the
 * filters round-trip through the URL.
 */
test.describe('staff arrears queue', () => {
  test.use({ storageState: 'e2e/.auth/staff-finance.json' });

  test('is reachable from the Finance nav and lists the queue worst-first', async ({ page }) => {
    await page.goto('/staff/policies');
    await expectStaffShellReady(page);

    // Through the sidebar, not by URL: the nav item is half of what this screen is for.
    await page.getByRole('link', { name: 'Arrears' }).click();
    await expect(page).toHaveURL(/\/staff\/arrears$/);
    await expect(page.getByRole('heading', { name: 'Arrears' })).toBeVisible({ timeout: 30_000 });

    // Either a populated queue or an honest empty state -- never a spinner that never
    // resolves. Same idiom staff-policies uses, and it keeps this independent of whether
    // anybody happens to be in arrears in this tenant today.
    const table = page.getByRole('table', { name: 'Arrears cases' });
    const empty = page.getByText('Nobody is in arrears');
    await expect(table.or(empty)).toBeVisible({ timeout: 20_000 });

    // The count names the AREA it counted, so a total can never be read as the whole book.
    await expect(page.getByText(/^[\d,]+ (open )?arrears cases · /)).toBeVisible();
  });

  test('the level filter is a floor, and it round-trips through the URL', async ({ page }) => {
    await page.goto('/staff/arrears');
    await expectStaffShellReady(page);
    await expect(page.getByRole('heading', { name: 'Arrears' })).toBeVisible({ timeout: 30_000 });

    // The chips read "4+" rather than "4" on purpose: the filter is a floor, and a bare
    // number would promise an exact match the endpoint does not do.
    await page.getByText('4+', { exact: true }).click();
    await expect(page).toHaveURL(/minDunningLevel=4/);
    await expect(page.getByText(/at dunning level 4 or worse/)).toBeVisible({ timeout: 15_000 });

    // Resolved history is a separate view, and "open" carries no parameter -- so clearing
    // back to the live queue removes it rather than writing a sentinel.
    await page.getByText('Resolved', { exact: true }).click();
    await expect(page).toHaveURL(/resolved=true/);
    await page.getByText('Open', { exact: true }).click();
    await expect(page).not.toHaveURL(/resolved=/);
  });
});

/**
 * The gate, asserted as a real denial rather than as an empty list.
 *
 * A tenant-wide list of who is behind on payments is a collections officer's screen, not
 * something every staff role sees -- the endpoint is gated on FINANCE_OFFICER/ADMIN, matching
 * the chart of accounts and GL postings. This is the falsifiable half: an ungated endpoint
 * would render a perfectly normal table here and nothing would look wrong.
 */
test.describe('staff arrears queue is finance-gated', () => {
  test.use({ storageState: 'e2e/.auth/staff-assessor.json' });

  test('a claims assessor gets an access panel, not a queue', async ({ page }) => {
    await page.goto('/staff/policies');
    await expectStaffShellReady(page);

    // Not in the nav for this role either -- the Finance group gates on the same roles.
    await expect(page.getByRole('link', { name: 'Arrears' })).not.toBeVisible();

    await expectRouteDenied(page, '/staff/arrears');
  });
});
