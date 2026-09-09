import { expect, type Page, test } from '@playwright/test';
import { caseAwaitingManualIssue, selectUnderwritingCase } from './underwriting';
import { expectNavItemsHidden, expectRouteDenied } from './guards';

/**
 * A row of the TABLE view, found by its account code.
 *
 * Two earlier spellings were wrong in instructive ways. Climbing three `..` levels from
 * a class combination broke the moment the flat list became two views with different
 * DOM depths. Filtering rows by `hasText: code` then matched SIX rows for '1200' --
 * itself plus its five children, each of which shows 1200 in its Parent column.
 *
 * The code cell is a `<th scope="row">`, so it is the row's accessible header and no
 * other cell can be confused for it. That is proper table semantics as well as a
 * stable hook.
 */
function accountRow(page: Page, code: string) {
  return page
    .getByRole('row')
    .filter({ has: page.getByRole('rowheader', { name: code, exact: true }) });
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
    // Manual issue names a real, unissued case now. The policyholder and product
    // come from it by prefill, so this no longer picks them by hand. The sum assured
    // still does: the case view @JsonIgnores it, so the console cannot read it.
    const caseId = await caseAwaitingManualIssue(page);
    await page.goto('/staff/policies/new');
    await selectUnderwritingCase(page, caseId);
    await expect(page.getByText('Resolving product version…')).not.toBeVisible();
    await page.getByLabel('Sum assured').fill('2000000.00');
    await page.getByLabel('Premium', { exact: true }).fill('800.00');
    // Required, and MIGRATION so the policy is in force rather than an offer.
    await page.getByLabel('Why is this being issued by hand?').selectOption('MIGRATION');
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
    await page.goto('/staff/chart-of-accounts?view=table');
    await expect(page.getByRole('heading', { name: 'Chart of accounts' })).toBeVisible();
    // 1210 Premium Receivables / 2140 Unearned Premium is the real accrual pair every
    // premium invoice posts against (PostingRule.PREMIUM_RECEIVABLE / UNEARNED_PREMIUM).
    // Both moved in finaccounting/V5: they were 1200 and 2200, which are now the
    // non-posting Receivables and Payables headers.
    await expect(accountRow(page, '1210')).toBeVisible();
    await expect(accountRow(page, '2140')).toBeVisible();
  });

  test('creates an account under a parent, renames it, retires it, then deletes it', async ({
    page,
  }) => {
    // 53xx sits inside 5300 Operating Expenses' block, which finaccounting/V5's prefix
    // rule requires of any child: a child's code must begin with its parent's code
    // minus trailing zeros. Leading block 5 = EXPENSE, normal balance DR -- both
    // derived server-side, never sent by the UI.
    const code = `53${String(Date.now() % 100).padStart(2, '0')}`;
    const name = `E2E Expense ${Date.now()}`;

    await page.goto('/staff/chart-of-accounts?view=table');
    await page.getByRole('button', { name: 'New account' }).click();
    await page.getByLabel('Account code').fill(code);
    await page.getByLabel('Parent account').fill('5300');
    await page.getByLabel('Name', { exact: true }).fill(name);
    await page.getByRole('button', { name: 'Create account' }).click();

    const row = accountRow(page, code);
    await expect(row.getByText(name)).toBeVisible({ timeout: 15_000 });
    // "Expense", not the wire's "EXPENSE": the Type column renders the block in the
    // words a finance officer uses, and falls back to the raw literal only for a
    // type this build has never heard of.
    await expect(row.getByText('Expense', { exact: true })).toBeVisible();

    const renamedName = `${name} renamed`;
    await row.getByRole('button', { name: 'Rename' }).click();
    await row.getByLabel('Name', { exact: true }).fill(renamedName);
    await row.getByRole('button', { name: 'Rename', exact: true }).click();
    await expect(accountRow(page, code).getByText(renamedName)).toBeVisible({ timeout: 15_000 });

    // The BUTTON says "Retire"; the BADGE says "Inactive", because StatusBadge renders
    // humanizeStatus() of the raw backend literal rather than a hand-written label.
    await accountRow(page, code).getByRole('button', { name: 'Retire' }).click();
    await expect(accountRow(page, code).getByText('Inactive')).toBeVisible({ timeout: 15_000 });

    await accountRow(page, code).getByRole('button', { name: 'Delete' }).click();
    await accountRow(page, code).getByRole('button', { name: 'Delete account' }).click();
    await expect(accountRow(page, code)).toHaveCount(0, { timeout: 15_000 });
  });

  test('rejects a duplicate account code with a real 409', async ({ page }) => {
    // 43xx sits inside 4300 Other Income's block, per the prefix rule.
    const code = `43${String(Date.now() % 100).padStart(2, '0')}`;
    const name = `E2E Income ${Date.now()}`;

    await page.goto('/staff/chart-of-accounts?view=table');
    await page.getByRole('button', { name: 'New account' }).click();
    await page.getByLabel('Account code').fill(code);
    await page.getByLabel('Parent account').fill('4300');
    await page.getByLabel('Name', { exact: true }).fill(name);
    await page.getByRole('button', { name: 'Create account' }).click();

    await expect(accountRow(page, code).getByText(name)).toBeVisible({ timeout: 15_000 });

    await page.getByRole('button', { name: 'New account' }).click();
    await page.getByLabel('Account code').fill(code);
    await page.getByLabel('Parent account').fill('4300');
    await page.getByLabel('Name', { exact: true }).fill('Duplicate attempt');
    await page.getByRole('button', { name: 'Create account' }).click();
    await expect(page.getByRole('alert')).toBeVisible({ timeout: 15_000 });

    // Clean up so a re-run of this spec is not blocked by a leftover account.
    await page.getByRole('button', { name: 'Cancel' }).click();
    await accountRow(page, code).getByRole('button', { name: 'Delete' }).click();
    await accountRow(page, code).getByRole('button', { name: 'Delete account' }).click();
    await expect(accountRow(page, code)).toHaveCount(0, { timeout: 15_000 });
  });

  test('rejects a malformed account code client-side, before any request is sent', async ({
    page,
  }) => {
    await page.goto('/staff/chart-of-accounts');
    await page.getByRole('button', { name: 'New account' }).click();
    await page.getByLabel('Account code').fill('9000');
    await page.getByLabel('Name', { exact: true }).fill('Should never be created');
    await page.getByRole('button', { name: 'Create account' }).click();

    await expect(page.getByText('Must be 4 digits starting with 1-5')).toBeVisible();
    await expect(page.getByText('Should never be created')).not.toBeVisible();
  });

  test('blocks deleting an account a real GL posting already references', async ({ page }) => {
    await page.goto('/staff/chart-of-accounts?view=table');
    // 1210 Premium Receivables is the account every premium invoice posts against, and
    // is the one account this tenant is guaranteed to have posted to.
    await accountRow(page, '1210').getByRole('button', { name: 'Delete' }).click();
    await accountRow(page, '1210').getByRole('button', { name: 'Delete account' }).click();

    await expect(page.getByRole('alert')).toBeVisible({ timeout: 15_000 });
    // Still there -- the delete was rejected, not silently accepted.
    await expect(accountRow(page, '1210')).toBeVisible();
  });

  test('blocks deleting a header account, which has children rather than postings', async ({
    page,
  }) => {
    await page.goto('/staff/chart-of-accounts?view=table');
    // 1200 Receivables is a header. Nothing posts to it -- it is refused for the other
    // reason, and the two 409s are deliberately distinct error codes server-side.
    await accountRow(page, '1200').getByRole('button', { name: 'Delete' }).click();
    await accountRow(page, '1200').getByRole('button', { name: 'Delete account' }).click();

    await expect(page.getByRole('alert')).toBeVisible({ timeout: 15_000 });
    await expect(accountRow(page, '1200')).toBeVisible();
  });

  test('the tree expands and collapses a branch, and the view is shareable through the URL', async ({
    page,
  }) => {
    await page.goto('/staff/chart-of-accounts');
    // The five block roots open by default; 1200 Receivables is one level down and
    // starts collapsed, so its children are not on screen yet.
    await expect(page.getByText('Receivables', { exact: true })).toBeVisible();
    await expect(page.getByText('Premium Receivables')).not.toBeVisible();

    await page.getByRole('button', { name: 'Expand Receivables' }).click();
    await expect(page.getByText('Premium Receivables')).toBeVisible();

    await page.getByRole('button', { name: 'Collapse Receivables' }).click();
    await expect(page.getByText('Premium Receivables')).not.toBeVisible();

    // `exact` matters: without it "Table" also matches the "Pos-table- only" filter chip.
    await page.getByRole('button', { name: 'Table', exact: true }).click();
    await expect(page).toHaveURL(/view=table/);
    // Every account is flat in the table, whatever the tree had collapsed.
    await expect(accountRow(page, '1210')).toBeVisible();
  });

  test('searching the tree opens the branch containing a match', async ({ page }) => {
    await page.goto('/staff/chart-of-accounts');
    await expect(page.getByText('Premium Receivables')).not.toBeVisible();

    // 1210 is two levels deep, under 1000 Assets > 1200 Receivables. A search that
    // matched but left the branch shut would be useless.
    await page.getByLabel('Search accounts').fill('Premium Receivables');
    await expect(page.getByText('Premium Receivables')).toBeVisible({ timeout: 15_000 });
  });

  test('the table searches and sorts the whole chart', async ({ page }) => {
    await page.goto('/staff/chart-of-accounts?view=table');
    await page.getByLabel('Search accounts').fill('reinsurance');
    await expect(page.getByRole('row').filter({ hasText: 'Reinsurance Recoverable' })).toBeVisible({
      timeout: 15_000,
    });
    await expect(page.getByRole('row').filter({ hasText: 'Petty Cash' })).toHaveCount(0);

    await page.getByLabel('Search accounts').fill('');
    await expect(page.getByRole('row').filter({ hasText: 'Petty Cash' })).toBeVisible({
      timeout: 15_000,
    });

    // Ascending by default, so one click flips to descending and 5500 leads.
    await page.getByRole('button', { name: 'Code' }).click();
    await expect(page.getByRole('row').nth(1)).toContainText('5500');
  });

  test('a type filter narrows the table to one block', async ({ page }) => {
    await page.goto('/staff/chart-of-accounts?view=table');
    // The chips name the block they narrow to, so they read plural -- and "Equity",
    // which has no plural, is the one that stayed as it was.
    await page.getByRole('button', { name: 'Equity', exact: true }).click();

    await expect(page.getByRole('row').filter({ hasText: 'Share Capital' })).toBeVisible();
    await expect(page.getByRole('row').filter({ hasText: 'Petty Cash' })).toHaveCount(0);
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
    await expectNavItemsHidden(page, 'GL postings', 'Chart of accounts');
    await expectRouteDenied(page, '/staff/gl-postings');
    await expectRouteDenied(page, '/staff/chart-of-accounts');
  });
});
