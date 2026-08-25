import { expect, type Page, test } from '@playwright/test';

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
  await page.goto('/staff/policies/new');
  await page.getByLabel('Policyholder party id').fill('d9937444-3873-4336-9cb7-addb486f3e1b');
  await page.getByLabel('Product').selectOption({ label: 'Demo Term Life (DEMO-TERM-01)' });
  await expect(page.getByText('Resolving product version…')).not.toBeVisible();
  await page.getByLabel('Sum assured').fill('2000000.00');
  await page.getByLabel('Premium', { exact: true }).fill('800.00');
  await page.getByLabel('Reason for manual issue').fill('E2E billing fixture');
  await page.getByRole('button', { name: 'Issue policy' }).click();
  await expect(page).toHaveURL(/\/staff\/policies\/POL-[A-Z0-9]+$/, { timeout: 15_000 });
  return page.url().split('/').pop() as string;
}

test.describe('staff billing', () => {
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

    await expect(page.getByText('Must be at least 10 characters')).toBeVisible();
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
