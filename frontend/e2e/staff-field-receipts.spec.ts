import { expect, test } from '@playwright/test';
import { expectRouteDenied, expectStaffShellReady } from './guards';

/**
 * The reconciliation queue.
 *
 * It exists because `observability/alert-rules.yml` carries a live medium-severity
 * `FieldReceiptReconciliationOverdue` alert -- "one or more agent-captured receipts have
 * exceeded the SLA without a matching PaymentConfirmed" -- against an entity whose only
 * endpoint was capture. The alert named a count, nothing could name a receipt, and the only
 * follow-up available was a hand-written database query.
 *
 * The status filter and the tenant scoping are proven server-side in `BillingContractTest`,
 * where the row that must be ABSENT can be controlled. What is proven here is the wiring: the
 * nav item, the finance gate arriving as an access panel, and the default view being the
 * breach rather than everything.
 */
test.describe('staff field receipts queue', () => {
  test.use({ storageState: 'e2e/.auth/staff-finance.json' });

  test('is reachable from Finance and opens on the overdue receipts', async ({ page }) => {
    await page.goto('/staff/policies');
    await expectStaffShellReady(page);

    await page.getByRole('link', { name: 'Field receipts' }).click();
    await expect(page).toHaveURL(/\/staff\/field-receipts$/);
    await expect(page.getByRole('heading', { name: 'Field receipts' })).toBeVisible({ timeout: 30_000 });

    // Every other queue on this console opens on everything. This one opens on the SLA breach,
    // because that is what the alert pages somebody about -- and the count says so rather than
    // leaving the default filter to be discovered.
    await expect(page.getByText(/overdue receipts · past the reconciliation SLA/)).toBeVisible({
      timeout: 20_000,
    });

    const table = page.getByRole('table', { name: 'Field receipts' });
    const empty = page.getByText('Nothing is overdue for reconciliation');
    await expect(table.or(empty)).toBeVisible({ timeout: 20_000 });
  });

  test('the status filter round-trips, and All widens past the default', async ({ page }) => {
    await page.goto('/staff/field-receipts');
    await expectStaffShellReady(page);
    await expect(page.getByRole('heading', { name: 'Field receipts' })).toBeVisible({ timeout: 30_000 });

    // Addressed by ROLE, not by text: each filter chip carries the same StatusBadge the table
    // cells do, so `getByText('Pending reconciliation')` matches the chip AND every matching
    // row. The chip is a button and a cell is not, which is the only stable way to tell the
    // control from the data it filtered.
    //
    // `ALL` is an explicit sentinel, so "show me everything" is distinguishable from a fresh
    // visit -- which matters here precisely because the default is NOT everything.
    await page.getByRole('button', { name: 'All', exact: true }).click();
    await expect(page).toHaveURL(/status=ALL/);
    await expect(page.getByText(/^[\d,]+ field receipts · /)).toBeVisible({ timeout: 15_000 });

    await page.getByRole('button', { name: 'Pending reconciliation' }).click();
    await expect(page).toHaveURL(/status=PENDING_RECONCILIATION/);
  });
});

/**
 * Finance-gated. An agent may CAPTURE a receipt -- that endpoint is agents-only -- but a
 * tenant-wide view of everyone's unreconciled cash is a different question with a different
 * audience. The falsifiable half: an ungated endpoint would render a perfectly ordinary table
 * here and nothing would look wrong.
 */
test.describe('staff field receipts queue is finance-gated', () => {
  test.use({ storageState: 'e2e/.auth/staff-assessor.json' });

  test('a claims assessor gets an access panel, not unreconciled cash', async ({ page }) => {
    await page.goto('/staff/policies');
    await expectStaffShellReady(page);

    await expect(page.getByRole('link', { name: 'Field receipts' })).not.toBeVisible();

    await expectRouteDenied(page, '/staff/field-receipts');
  });
});
