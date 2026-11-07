import { expect, test, type Locator, type Page } from '@playwright/test';
import { issuePolicyAgainst } from './manualIssue';

/**
 * Opens a fresh endowment's record on its Loans tab.
 *
 * It used to open the FIRST policy in the list, whatever kind it was. Since the audit of 2026-10-07 the Loans
 * tab shows only where a loan is possible -- a policy with a cash value to lend against -- so this issues one: the
 * seeded Demo Endowment, by hand, in force, with no cash value yet. Always true; kept a boolean so the tests read
 * as before.
 */
async function openFirstPolicyDetail(page: Page): Promise<boolean> {
  await issuePolicyAgainst(page, 'Demo Endowment (DEMO-END-01)', 'E2E policy-loans fixture', { termMonths: 240 });
  // Loans is a tab on the record. Clicked rather than deep-linked, because this helper
  // exists to walk the way a person actually gets here.
  await page.getByRole('tab', { name: 'Loans' }).click();
  await expect(page.getByRole('heading', { name: 'Loans' })).toBeVisible();
  return true;
}

function loansPanel(page: Page): Locator {
  // The panel is the region following its own heading; scoping to it keeps
  // "Record repayment" from colliding with anything the invoices panel renders.
  return page.locator('section', { has: page.getByRole('heading', { name: 'Loans' }) });
}

/**
 * The loans panel's write surface, against the real backend.
 *
 * `POST /policies/{n}/loans` originates; a repayment (money movement step 1, 2026-11-04) is
 * a deposit For: Loan on the receipt page or `POST /loans/{loanId}/payment-request`, and
 * neither counts until the money is confirmed. These tests cover that surface.
 *
 * **What they deliberately do NOT do is originate a loan.** A loan is capped at the
 * policy's available loan value. Product step 1 made cash value real -- it is restated
 * from the product's table as premiums are paid -- but only for a savings product past
 * its first two or three full years, and every policy the dev seed produces is term
 * business that will never have any. So origination against real seeded data still
 * returns 409, and asserting the DISABLED action and its stated reason is still the
 * honest test. Seeding a cash value to drive the happy path would be testing a fixture
 * rather than the platform; the savings path is covered by `ValueActions.test.tsx` and
 * by the backend's own end-to-end tests.
 */
test.describe('staff policy loans', () => {
  // Issuing the endowment is underwriting's and its Loans tab is accounts' (accounts.view, loans.manage) since staff
  // permissions (2026-10-26); the admin holds both.
  test.use({ storageState: 'e2e/.auth/staff-admin.json' });
  test('the loans panel renders real loan data or an honest empty state', async ({ page }) => {
    const opened = await openFirstPolicyDetail(page);
    test.skip(!opened, 'no seeded policy to open');
    if (!opened) return;

    const panel = loansPanel(page);
    // One or the other, never a spinner that never resolves.
    const empty = panel.getByText('No loans');
    const anyRow = panel.getByText(/of .* principal/);
    await expect(empty.or(anyRow).first()).toBeVisible();
  });

  test('origination is disabled with its reason, not a button that can only 409', async ({
    page,
  }) => {
    const opened = await openFirstPolicyDetail(page);
    test.skip(!opened, 'no seeded policy to open');
    if (!opened) return;

    const panel = loansPanel(page);
    const takeLoan = panel.getByRole('button', { name: 'Take a loan' });
    await expect(takeLoan).toBeVisible();

    // Cash value is 0.00 on every seeded (term) policy, so the action must be inert AND
    // must say why. The reason is asserted as RENDERED TEXT, not a `title`: a tooltip is
    // unreachable by keyboard and silent to a screen reader, so a disabled button whose
    // only explanation lived there explained nothing to the people most in need of it.
    await expect(takeLoan).toBeDisabled();
    await expect(panel.getByText(/cash value, which is 0\.00/)).toBeVisible();
  });

  test('a repayment is never typed in, and its actions appear only on a loan that can take one', async ({
    page,
  }) => {
    const opened = await openFirstPolicyDetail(page);
    test.skip(!opened, 'no seeded policy to open');
    if (!opened) return;

    const panel = loansPanel(page);
    // Money movement step 1 (2026-11-04): a repayment is a deposit For: Loan confirmed by a second person, or a
    // mobile-money request. The old typed-in "Record repayment" is gone everywhere.
    await expect(panel.getByRole('button', { name: 'Record repayment' })).toHaveCount(0);

    // Offered exactly for the two statuses the server accepts a repayment on -- none on a fresh policy, since
    // origination is blocked by cash value; one per repayable loan wherever a loan does exist.
    const repayable = await panel.getByText(/^(DISBURSED|REPAYING)$/i).count();
    // One Repay per repayable loan, leading to the one Deposit page (2026-11-07).
    await expect(panel.getByRole('link', { name: 'Repay' })).toHaveCount(repayable);
  });

  test('the deposit page takes a loan repayment, and says so when the policy has no loan to repay', async ({ page }) => {
    const opened = await openFirstPolicyDetail(page);
    test.skip(!opened, 'no seeded policy to open');
    if (!opened) return;
    const policyNumber = decodeURIComponent(new URL(page.url()).pathname.split('/').pop() ?? '');

    // Where the Loans tab's "Repay" link leads, opened for a policy with no loan: the honest empty answer,
    // rather than a loan list to pick from that does not exist.
    await page.goto(`/staff/receipts/new?for=LOAN&policy=${encodeURIComponent(policyNumber)}&from=policy`);
    await expect(page.getByRole('heading', { name: 'Deposit', exact: true })).toBeVisible();
    // Exact: the action bell's "Jobs waiting for you" also contains the word.
    const paysFor = page.getByLabel('For', { exact: true });
    await expect(paysFor).toHaveValue('LOAN');
    await expect(page.getByText(`No loan on ${policyNumber} has anything to repay.`)).toBeVisible();

    // Back to a premium, the loan choice and its "nothing to repay" go: a premium deposit names no invoice either --
    // it goes onto the policy's balance (money movement step 5). One form for both.
    await paysFor.selectOption('PREMIUM');
    await expect(page.getByText(`No loan on ${policyNumber} has anything to repay.`)).toHaveCount(0);
    await expect(page.getByLabel('Invoice it pays')).toHaveCount(0);
  });
});
