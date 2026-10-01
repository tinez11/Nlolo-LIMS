import { expect, test, type Locator, type Page } from '@playwright/test';

/**
 * Opens the first seeded policy's full detail page, or returns false if the tenant
 * genuinely has no policies.
 *
 * Waits for the list to settle before probing. `isVisible()` is an immediate snapshot,
 * not a wait, so testing it directly races the initial fetch and reports "no policies"
 * for a list that had merely not arrived -- silently SKIPPING the gate instead of
 * failing it. Same reasoning as `staff-policies.spec.ts`'s own helper.
 */
async function openFirstPolicyDetail(page: Page): Promise<boolean> {
  await page.goto('/staff/policies');
  const table = page.getByRole('table', { name: 'Policies' });
  const empty = page.getByText('No policies yet');
  await expect(table.or(empty)).toBeVisible();
  if (await empty.isVisible()) return false;

  const row = table.getByRole('button').first();
  await expect(row).toBeVisible();
  await row.click();
  await page.getByRole('dialog').getByRole('link', { name: /full detail/i }).click();
  // Loans is a tab on the record now. Clicked rather than deep-linked, because this helper
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
 * `POST /policies/{n}/loans` and `POST /loans/{loanId}/repayments` shipped with the
 * `policyloan` module and no screen ever called either one -- a policy loan could be
 * listed but never created or repaid from this console. These tests cover the surface
 * that closes that.
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

  test('the repayment action appears only on a loan that can actually take one', async ({
    page,
  }) => {
    const opened = await openFirstPolicyDetail(page);
    test.skip(!opened, 'no seeded policy to open');
    if (!opened) return;

    const panel = loansPanel(page);
    const noLoans = panel.getByText('No loans');
    // With no loans -- the state every current tenant is in, because origination is
    // blocked upstream -- there must be no repayment control at all. This is a real
    // assertion, not a placeholder: an unconditional "Record repayment" button on an
    // empty panel, or on a loan resting at DISBURSEMENT_REQUESTED, would 409 on click,
    // and only the status guard in `LoansPanel` prevents that.
    if (await noLoans.isVisible()) {
      await expect(panel.getByRole('button', { name: 'Record repayment' })).toHaveCount(0);
      return;
    }

    // If a loan does exist (a future seeder, or a hand-created one), the control is
    // offered exactly for the two statuses PolicyLoanApiImpl.recordRepayment accepts.
    const repayable = panel.getByText(/^(DISBURSED|REPAYING)$/i);
    const repayButtons = panel.getByRole('button', { name: 'Record repayment' });
    await expect(repayButtons).toHaveCount(await repayable.count());
  });

  test('opening the repayment form asks for an amount and a reference', async ({ page }) => {
    const opened = await openFirstPolicyDetail(page);
    test.skip(!opened, 'no seeded policy to open');
    if (!opened) return;

    const panel = loansPanel(page);
    const repay = panel.getByRole('button', { name: 'Record repayment' }).first();
    test.skip((await repay.count()) === 0, 'no repayable loan in this tenant');

    await repay.click();
    await expect(panel.getByText('Repayment amount (TZS)')).toBeVisible();
    await expect(panel.getByText('Payment reference')).toBeVisible();

    // Submitting empty must surface the client-side validation rather than firing a
    // request the backend would reject on shape.
    await panel.getByRole('button', { name: 'Record repayment' }).last().click();
    await expect(panel.getByText('A repayment amount is required')).toBeVisible();
  });
});
