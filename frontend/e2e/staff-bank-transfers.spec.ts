import { expect, test } from '@playwright/test';

/**
 * The EFT work queue: bank transfers waiting on a person.
 *
 * ## What only a browser can prove here
 *
 * That the queue is REACHABLE and gated. Both endpoints have been live since credit-life plan 4
 * and nothing in this console called either of them, so a credit-life claim could be registered,
 * adjudicated, approved, valued against the borrower's outstanding balance and instructed for
 * payment — and then stop, with the lender unpaid and no screen anywhere that admitted the
 * instruction existed. A screen that exists but cannot be navigated to would be the same defect
 * wearing a different hat.
 *
 * ## What is deliberately NOT asserted here
 *
 * The money moving. Reaching a real AWAITING_EXECUTION row through the browser means registering
 * a credit-life claim, assessing it, deciding it, approving the settlement and having the rail
 * choose EFT — five screens and three identities to arrive at a row whose behaviour is already
 * proven against a real database and a real rail by `ClaimSettlementEndToEndTest` and the payment
 * module's own tests. Re-proving it here at real-stack prices would buy a slower copy.
 *
 * So: the queue renders, it is finance-gated, and the confirmation refuses to let money be
 * recorded as moved without the bank's own reference.
 */

test.describe('staff bank transfers', () => {
  test.use({ storageState: 'e2e/.auth/staff-finance.json' });

  test('a finance officer can reach the queue from the sidebar', async ({ page }) => {
    test.slow();
    await page.goto('/staff');
    await page.getByRole('link', { name: 'Bank transfers' }).click();

    await expect(page).toHaveURL(/\/staff\/bank-transfers$/, { timeout: 20_000 });
    await expect(page.getByRole('heading', { name: 'Bank transfers' })).toBeVisible({
      timeout: 30_000,
    });

    /*
     * Either the queue has rows or it is empty, and BOTH are assertable states -- what must not
     * happen is the third thing, a page that renders neither because the request failed. An
     * unreachable backend and an empty queue look identical to somebody who has been told a claim
     * was settled, which is exactly why the empty state says what it says.
     */
    const table = page.getByRole('table', { name: 'Bank transfers awaiting execution' });
    const empty = page.getByText('Nothing is waiting on a transfer');
    await expect(table.or(empty)).toBeVisible({ timeout: 30_000 });

    // Whichever it is, the page must not be showing a failure.
    await expect(page.getByText(/could not load/i)).toHaveCount(0);
  });

  test('a transfer cannot be recorded without the bank reference', async ({ page }) => {
    test.slow();
    await page.goto('/staff/bank-transfers');
    await expect(page.getByRole('heading', { name: 'Bank transfers' })).toBeVisible({
      timeout: 30_000,
    });

    /*
     * WAIT FOR THE QUEUE TO SETTLE BEFORE COUNTING. `count()` does not auto-wait, so counting
     * straight after `goto` asks "is there a row" of a page still fetching, gets zero, and skips
     * the test — which is indistinguishable from an empty queue and is how this spec quietly
     * proved nothing on a tenant that DID have a transfer waiting.
     */
    const table = page.getByRole('table', { name: 'Bank transfers awaiting execution' });
    const empty = page.getByText('Nothing is waiting on a transfer');
    await expect(table.or(empty)).toBeVisible({ timeout: 30_000 });

    const first = page.getByRole('button', { name: 'Record transfer' }).first();
    // Skipped rather than failed when the queue is genuinely empty: this tenant's queue depends on
    // what else has run, and a test that demanded an unpaid claim exist would fail for a reason
    // that is not about this screen. When there IS one, the guard is checked properly.
    test.skip((await first.count()) === 0, 'no transfer is awaiting execution in this tenant');

    await first.click();
    const confirmation = page.getByRole('group', { name: 'Has this transfer been made?' });
    await expect(confirmation).toBeVisible();
    await expect(confirmation).toContainText('Nothing here can undo it');

    // Held, and the reason said out loud. Recording an execution settles the claim, exits the
    // borrower and books the expense -- an execution with nothing to look it up by is a claim the
    // platform believes is paid that nobody can trace to a transfer.
    const confirm = confirmation.getByRole('button', { name: 'Record the transfer' });
    await expect(confirm).toBeDisabled();
    await expect(page.getByText('A bank reference is required before this can be recorded.')).toBeVisible();

    await confirmation.getByLabel('Bank reference', { exact: true }).fill('E2E-NOT-SENT');
    await expect(confirm).toBeEnabled();

    // Cancelled deliberately: this spec proves the GUARD, and clicking through would move money
    // in a shared dev tenant on the strength of a reference nobody sent.
    await confirmation.getByRole('button', { name: 'Cancel' }).click();
    await expect(confirmation).toHaveCount(0);
  });
});

test.describe('staff bank transfers, as somebody who is not finance', () => {
  test('an underwriter is not offered the queue', async ({ page }) => {
    // The sidebar mirrors the backend: FINANCE_OFFICER only. The nav item's absence is the
    // assertion -- a staff member who cannot use it should not be shown a link that 403s.
    test.slow();
    await page.goto('/staff');
    await expect(page.getByRole('heading', { name: 'Policies' })).toBeVisible({ timeout: 30_000 });
    await expect(page.getByRole('link', { name: 'Bank transfers' })).toHaveCount(0);
  });
});
