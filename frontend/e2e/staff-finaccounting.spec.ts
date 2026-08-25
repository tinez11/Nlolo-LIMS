import { expect, test } from '@playwright/test';

/**
 * Finaccounting e2e coverage against the real backend. Entirely read-only,
 * by platform design -- every journal entry is derived from a domain event
 * by this module's own listeners, and there is no write endpoint anywhere on
 * this surface, so there is nothing here to create as a test fixture.
 *
 * Every endpoint (`/gl-postings*`, `/chart-of-accounts`) is gated on
 * FINANCE_OFFICER/ADMIN with no REALM_STAFF-broad read path at all -- the
 * same posture as Reinsurance, stricter than Distribution -- so this whole
 * file runs under the real `staff.finance` identity (auth-finance.setup.ts).
 *
 * Real journal entries already exist in this tenant from every policy this
 * session's OTHER suites have issued: `billing.PremiumInvoiceGenerated`
 * posts a real DR/CR pair (Premium Receivable / Unearned Premium) at
 * issuance, so a fresh policy issued here is both the test fixture and the
 * assertion target -- no fabricated data, just the platform's own real
 * side effect.
 */

test.describe('staff finaccounting', () => {
  test.use({ storageState: 'e2e/.auth/staff-finance.json' });

  test('lists real journal entries this session already produced', async ({ page }) => {
    await page.goto('/staff/gl-postings');
    await expect(page.getByRole('heading', { name: 'GL postings' })).toBeVisible();
    await expect(page.getByText('billing.PremiumInvoiceGenerated').first()).toBeVisible({
      timeout: 15_000,
    });
  });

  test('opens a row preview showing two balanced postings, then navigates to full detail', async ({
    page,
  }) => {
    await page.goto('/staff/gl-postings');
    await page
      .getByRole('row')
      .filter({ hasText: 'billing.PremiumInvoiceGenerated' })
      .first()
      .getByRole('button')
      .click();

    await expect(page.getByText('DR', { exact: true })).toBeVisible();
    await expect(page.getByText('CR', { exact: true })).toBeVisible();

    await page.getByRole('link', { name: 'Full detail' }).click();
    await expect(page.getByRole('heading', { name: 'billing.PremiumInvoiceGenerated' })).toBeVisible();
    await expect(page.getByText('Always exactly two legs')).toBeVisible();
  });

  test('a policyNumber filter narrows the list to a real freshly-issued policy, and is shareable through the URL', async ({
    page,
  }) => {
    await page.goto('/staff/policies/new');
    await page.getByLabel('Policyholder party id').fill('d9937444-3873-4336-9cb7-addb486f3e1b');
    await page.getByLabel('Product').selectOption({ label: 'Demo Term Life (DEMO-TERM-01)' });
    await expect(page.getByText('Resolving product version…')).not.toBeVisible();
    await page.getByLabel('Sum assured').fill('2000000.00');
    await page.getByLabel('Premium', { exact: true }).fill('800.00');
    await page.getByLabel('Reason for manual issue').fill('E2E finaccounting fixture');
    await page.getByRole('button', { name: 'Issue policy' }).click();
    await expect(page).toHaveURL(/\/staff\/policies\/POL-[A-Z0-9]+$/, { timeout: 15_000 });
    const policyNumber = page.url().split('/').pop() as string;

    await page.goto('/staff/gl-postings');
    await page.getByLabel('Policy number').fill(policyNumber);
    await page.getByRole('button', { name: 'Apply' }).click();

    await expect(page).toHaveURL(new RegExp(`policyNumber=${policyNumber}`));
    // Posting is a real side effect of issuance -- polled defensively rather
    // than assumed synchronous, same discipline as every other cross-module
    // event effect verified this session.
    await expect(async () => {
      await page.reload();
      await expect(page.getByText(policyNumber).first()).toBeVisible();
    }).toPass({ timeout: 20_000 });

    // Every row in a policyNumber-filtered result must actually carry it.
    const rows = page.getByRole('row').filter({ hasText: 'billing.' });
    await expect(rows.first()).toBeVisible();
    const count = await rows.count();
    for (let i = 0; i < count; i++) {
      await expect(rows.nth(i)).toContainText(policyNumber);
    }
  });

  test('chart of accounts lists the real seeded placeholder accounts', async ({ page }) => {
    await page.goto('/staff/chart-of-accounts');
    await expect(page.getByRole('heading', { name: 'Chart of accounts' })).toBeVisible();
    // 1200 Premium Receivable / 2200 Unearned Premium is the real accrual
    // pair every premium invoice posts against.
    await expect(page.getByText('1200')).toBeVisible();
  });
});

// A separate, non-`test.use`-overridden describe block: this file's own
// `test.use({ storageState: 'staff-finance.json' })` above applies only to
// its own describe block, so this one still gets the project's default
// `staff.underwriter` identity, which carries neither FINANCE_OFFICER nor
// ADMIN.
test.describe('staff finaccounting -- role gating', () => {
  test('a staff.underwriter session cannot see or reach GL postings or the chart of accounts', async ({
    page,
  }) => {
    await page.goto('/staff/policies');
    await expect(page.getByRole('link', { name: 'GL postings' })).not.toBeVisible();
    await expect(page.getByRole('link', { name: 'Chart of accounts' })).not.toBeVisible();

    await page.goto('/staff/gl-postings');
    await expect(page.getByText('You do not have access to this')).toBeVisible();

    await page.goto('/staff/chart-of-accounts');
    await expect(page.getByText('You do not have access to this')).toBeVisible();
  });
});
