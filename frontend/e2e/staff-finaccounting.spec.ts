import { expect, type Page, test } from '@playwright/test';

/**
 * An account's own code renders once, in a span with this exact class combo
 * (ChartOfAccountsPage's `AccountRow`) -- nowhere else on the page does that
 * combination appear, even though the bare code text does (the delete
 * confirmation's "Delete <code>..." sentence, and a 409's error message both
 * echo it). Scoping through this selector, not a bare `getByText(code)`,
 * is what keeps every row lookup below a single unambiguous match.
 */
function accountRow(page: Page, code: string) {
  return page
    .locator('span.font-mono.text-xs.text-muted-foreground', { hasText: code })
    .locator('..')
    .locator('..')
    .locator('..');
}

/**
 * Finaccounting e2e coverage against the real backend. Journal entries/GL
 * postings stay read-only, by platform design -- every entry is derived from
 * a domain event by this module's own listeners. The chart of accounts is
 * NOT read-only: create/rename/delete were added on explicit request, so
 * this file also covers that real lifecycle end to end.
 *
 * Every endpoint (`/gl-postings*`, `/chart-of-accounts*`) is gated on
 * FINANCE_OFFICER/ADMIN with no REALM_STAFF-broad read path at all -- the
 * same posture as Reinsurance, stricter than Distribution -- so this whole
 * file runs under the real `staff.finance` identity (auth-finance.setup.ts).
 *
 * Real journal entries already exist in this tenant from every policy this
 * session's OTHER suites have issued: `billing.PremiumInvoiceGenerated`
 * posts a real DR/CR pair (Premium Receivable / Unearned Premium) at
 * issuance, so a fresh policy issued here is both the test fixture and the
 * assertion target -- no fabricated data, just the platform's own real
 * side effect. That same real pair (account 1200) is also what proves the
 * delete-blocked-while-in-use path below: it is the one seeded account this
 * tenant is guaranteed to have posted against.
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

  test('creates an account with a derived type/balance, renames it, then deletes it', async ({
    page,
  }) => {
    // 4 digits, leading block 5 = EXPENSE (normal balance DR) -- the pattern
    // the backend itself enforces, not a value the UI is free to interpret.
    const code = `5${String(Date.now() % 1000).padStart(3, '0')}`;
    const name = `E2E Expense ${Date.now()}`;

    await page.goto('/staff/chart-of-accounts');
    await page.getByRole('button', { name: 'New account' }).click();
    await page.getByLabel('Account code').fill(code);
    await page.getByLabel('Name').fill(name);
    await page.getByRole('button', { name: 'Create account' }).click();

    const row = accountRow(page, code);
    await expect(row.getByText(name)).toBeVisible({ timeout: 15_000 });
    await expect(row.getByText('EXPENSE', { exact: true })).toBeVisible();
    await expect(row.getByText('DR', { exact: true })).toBeVisible();

    const renamedName = `${name} renamed`;
    await row.getByRole('button', { name: 'Rename' }).click();
    const nameInput = row.getByLabel('Name');
    await nameInput.fill(renamedName);
    await row.getByRole('button', { name: 'Rename', exact: true }).click();
    await expect(row.getByText(renamedName)).toBeVisible({ timeout: 15_000 });

    await row.getByRole('button', { name: 'Delete' }).click();
    await row.getByRole('button', { name: 'Delete account' }).click();
    await expect(accountRow(page, code)).toHaveCount(0, { timeout: 15_000 });
  });

  test('rejects a duplicate account code with a real 409', async ({ page }) => {
    const code = `4${String(Date.now() % 1000).padStart(3, '0')}`;
    const name = `E2E Income ${Date.now()}`;

    await page.goto('/staff/chart-of-accounts');
    await page.getByRole('button', { name: 'New account' }).click();
    await page.getByLabel('Account code').fill(code);
    await page.getByLabel('Name').fill(name);
    await page.getByRole('button', { name: 'Create account' }).click();

    const row = accountRow(page, code);
    await expect(row.getByText(name)).toBeVisible({ timeout: 15_000 });

    await page.getByRole('button', { name: 'New account' }).click();
    await page.getByLabel('Account code').fill(code);
    await page.getByLabel('Name').fill('Duplicate attempt');
    await page.getByRole('button', { name: 'Create account' }).click();
    await expect(page.getByRole('alert')).toBeVisible({ timeout: 15_000 });

    // Clean up so a re-run of this spec is not blocked by a leftover account.
    await page.getByRole('button', { name: 'Cancel' }).click();
    await row.getByRole('button', { name: 'Delete' }).click();
    await row.getByRole('button', { name: 'Delete account' }).click();
    await expect(accountRow(page, code)).toHaveCount(0, { timeout: 15_000 });
  });

  test('rejects a malformed account code client-side, before any request is sent', async ({
    page,
  }) => {
    await page.goto('/staff/chart-of-accounts');
    await page.getByRole('button', { name: 'New account' }).click();
    await page.getByLabel('Account code').fill('9000');
    await page.getByLabel('Name').fill('Should never be created');
    await page.getByRole('button', { name: 'Create account' }).click();

    await expect(page.getByText('Must be 4 digits starting with 1-5')).toBeVisible();
    await expect(page.getByText('Should never be created')).not.toBeVisible();
  });

  test('blocks deleting an account a real GL posting already references', async ({ page }) => {
    await page.goto('/staff/chart-of-accounts');
    const row = accountRow(page, '1200');
    await row.getByRole('button', { name: 'Delete' }).click();
    await row.getByRole('button', { name: 'Delete account' }).click();

    await expect(page.getByRole('alert')).toBeVisible({ timeout: 15_000 });
    // Still there -- the delete was rejected, not silently accepted.
    await expect(accountRow(page, '1200')).toBeVisible();
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
