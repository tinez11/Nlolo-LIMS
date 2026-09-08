import { expect, type Page, test } from '@playwright/test';
import { caseAwaitingManualIssue, selectUnderwritingCase } from './underwriting';

/**
 * Billing e2e coverage against the real backend: waiving an invoice and
 * requesting payment for one, both added to the existing Invoices panel on
 * PolicyDetailPage rather than a new page -- there is no billing-specific nav
 * item, since these are per-invoice row actions, not a domain with its own
 * list.
 *
 * Both endpoints are REALM_STAFF-broad (no fine-grained role), so the default
 * `staff.underwriter` identity is enough for this whole file, unlike claims
 * adjudication or distribution.
 *
 * `PremiumInvoice.waive()` has no status guard at all server-side -- it is
 * genuinely callable on any invoice, including an already-PAID one -- so
 * there is no real 409/422 to test there. `requestPaymentForInvoice` only
 * validates payerRef/Idempotency-Key presence, both already client-enforced,
 * so there is no realistic server-side rejection path for either action to
 * exercise a reset-on-mount test against (the same category of gap already
 * documented for underwriting's referral action) -- not fabricated here.
 */

async function issueRealPolicyWithInvoices(page: Page): Promise<string> {
  // Manual issue names a real, unissued case now. The policyholder and product come from it
  // by prefill, so this no longer picks them by hand -- the sum assured still does, because
  // the case view @JsonIgnores it and the console genuinely cannot see it.
  const caseId = await caseAwaitingManualIssue(page);
  await page.goto('/staff/policies/new');
  await selectUnderwritingCase(page, caseId);
  await expect(page.getByText('Resolving product version…')).not.toBeVisible();
  await page.getByLabel('Sum assured').fill('2000000.00');
  await page.getByLabel('Premium', { exact: true }).fill('800.00');
  await page.getByLabel('Reason for manual issue').fill('E2E billing fixture');
  await page.getByRole('button', { name: 'Issue policy' }).click();
  await expect(page).toHaveURL(/\/staff\/policies\/POL-[A-Z0-9]+$/, { timeout: 15_000 });
  return page.url().split('/').pop() as string;
}

test.describe('staff billing', () => {
  // Waiving is FINANCE_OFFICER/ADMIN now, tightened from plain staff: writing off a premium
  // is a final ledger movement and an underwriter has no business authorising one.
  test.use({ storageState: 'e2e/.auth/staff-finance.json' });

  test('waives a real invoice on a freshly issued policy', async ({ page }) => {
    await issueRealPolicyWithInvoices(page);

    // Premium invoices are pre-generated 12 months ahead at issuance -- a
    // fresh policy already has at least one real invoice row to act on.
    await expect(page.getByRole('button', { name: 'Waive' }).first()).toBeVisible({
      timeout: 15_000,
    });
    await page.getByRole('button', { name: 'Waive' }).first().click();
    await page.getByLabel('Reason').fill('E2E goodwill waiver, hardship case');
    await page.getByRole('button', { name: 'Waive invoice' }).click();

    // Waiving is one of the platform's genuinely one-way doors -- PremiumInvoice
    // marks WAIVED with no status guard and never overwrites it, even for a
    // payment arriving later -- so it now takes a second, deliberate click. The
    // confirming button deliberately does NOT repeat "Waive invoice": two
    // identical buttons a click apart would make the second one reflex.
    await expect(page.getByText(/cannot be un-waived/)).toBeVisible();
    await page.getByRole('button', { name: 'Write off invoice' }).click();

    await expect(page.getByText('Waived').first()).toBeVisible({ timeout: 15_000 });
  });

  test('rejects a waiver reason under 10 characters, before reaching the network', async ({
    page,
  }) => {
    await issueRealPolicyWithInvoices(page);

    let requestFired = false;
    page.on('request', (req) => {
      if (req.method() === 'POST' && req.url().endsWith('/waiver')) requestFired = true;
    });

    await page.getByRole('button', { name: 'Waive' }).first().click();
    await page.getByLabel('Reason').fill('too short');
    await page.getByRole('button', { name: 'Waive invoice' }).click();

    // Validation runs BEFORE the confirmation, deliberately: confirming an
    // action and then being told the form was invalid would teach people to
    // click through the confirmation without reading it.
    await expect(page.getByText('Must be at least 10 characters')).toBeVisible();
    await expect(page.getByRole('button', { name: 'Write off invoice' })).toHaveCount(0);
    expect(requestFired).toBe(false);
  });

  test('requests payment for a real invoice', async ({ page }) => {
    await issueRealPolicyWithInvoices(page);

    await expect(page.getByRole('button', { name: 'Request payment' }).first()).toBeVisible({
      timeout: 15_000,
    });
    await page.getByRole('button', { name: 'Request payment' }).first().click();

    // 11 other invoice rows still show their own "Request payment" TOGGLE, so
    // the submit button must be scoped to the one form that actually opened.
    const form = page.locator('form').filter({ has: page.getByLabel('Payer reference') });
    await form.getByLabel('Payer reference').fill('255700000000');
    await form.getByRole('button', { name: 'Request payment' }).click();

    // A real 202 -- the form closes on success, same signal used throughout
    // this console (the request itself succeeding, not the async settlement).
    await expect(page.getByLabel('Payer reference')).not.toBeVisible({ timeout: 15_000 });
  });
});
