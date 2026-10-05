import { expect, test } from '@playwright/test';
import { dmy, todayIso } from './dates';
import { expectNavItemsHidden, expectRouteDenied } from './guards';

/**
 * IFRS 17 I1 through the real stack: the accounting policy register and accounting periods, each a
 * two-person control. Finance proposes and asks; finance's own approve is refused on screen with the
 * reason; the admin approves.
 *
 * Rerun-safe: each run proposes its own election (a unique sign-off names it), and the period it closes
 * is reopened at the end, so the next run finds it OPEN again.
 */

function tomorrowIso(): string {
  const d = new Date(`${todayIso()}T00:00:00`);
  d.setDate(d.getDate() + 1);
  return `${d.getFullYear()}-${`${d.getMonth() + 1}`.padStart(2, '0')}-${`${d.getDate()}`.padStart(2, '0')}`;
}

test.describe('IFRS 17 ledger controls', () => {
  test.use({ storageState: 'e2e/.auth/staff-admin.json' });

  test('an accounting policy change is proposed by finance and approved by a second person', async ({
    page,
    browser,
  }) => {
    const stamp = `${Date.now()}`;
    const rationale = `E2E OCI election ${stamp}`;
    const signOff = `Test memo ${stamp}`;

    const financeContext = await browser.newContext({ storageState: 'e2e/.auth/staff-finance.json' });
    const financePage = await financeContext.newPage();
    await financePage.goto('/staff/accounting-policies');
    await expect(financePage.getByRole('heading', { name: 'Accounting policies' })).toBeVisible();
    // The baseline is in force for every tenant.
    await expect(financePage.getByRole('table', { name: 'OCI option' })).toBeVisible({ timeout: 15_000 });

    const form = financePage.getByRole('form', { name: 'Propose a change' });
    await form.getByLabel('Election').selectOption('OCI_OPTION');
    await form.getByLabel('Scope').fill('*');
    await form.getByLabel('Value').selectOption('ON');
    await form.getByLabel('Effective from').fill(dmy(tomorrowIso()));
    await form.getByLabel('Rationale').fill(rationale);
    await form.getByRole('button', { name: 'Propose change' }).click();

    const financeRow = financePage
      .getByRole('list', { name: 'Proposed elections' })
      .getByRole('listitem')
      .filter({ hasText: rationale });
    await expect(financeRow).toBeVisible({ timeout: 15_000 });
    // The proposer may not decide their own.
    await expect(financeRow.getByRole('button', { name: 'Approve' })).toBeDisabled();
    await expect(financeRow.getByText('A second person approves an accounting policy election')).toBeVisible();
    await financeContext.close();

    await page.goto('/staff/accounting-policies');
    const adminRow = page
      .getByRole('list', { name: 'Proposed elections' })
      .getByRole('listitem')
      .filter({ hasText: rationale });
    await adminRow.getByLabel('Sign-off reference').fill(signOff);
    await adminRow.getByRole('button', { name: 'Approve' }).click();

    // Approved for tomorrow: scheduled, with the register's next version beside its sign-off.
    const scheduled = page.getByRole('table', { name: 'Scheduled elections' }).getByRole('row').filter({ hasText: signOff });
    await expect(scheduled).toBeVisible({ timeout: 15_000 });
    await expect(scheduled).toContainText('ON');
    const version = Number((await scheduled.getByRole('cell').nth(4).innerText()).trim());
    expect(version).toBeGreaterThan(44);   // the baseline is versions 1-44
  });

  test('a period is closed and locked by finance, and reopened only by a second person', async ({ page, browser }) => {
    const period = `${new Date().getFullYear() - 2}-01`;

    const financeContext = await browser.newContext({ storageState: 'e2e/.auth/staff-finance.json' });
    const financePage = await financeContext.newPage();
    await financePage.goto('/staff/periods');
    await expect(financePage.getByRole('heading', { name: 'Accounting periods' })).toBeVisible();
    await financePage.getByLabel('Another period (YYYY-MM)').fill(period);
    await financePage.getByRole('button', { name: 'Show', exact: true }).click();

    const row = financePage.getByRole('listitem', { name: `Period ${period}` });
    await expect(row).toBeVisible({ timeout: 15_000 });
    // Two clicks each: the action, then the confirmation that replaces it.
    await row.getByRole('button', { name: 'Start closing' }).click();
    await row.getByRole('button', { name: 'Start closing' }).click();
    await expect(row.getByText('Closing', { exact: true })).toBeVisible({ timeout: 15_000 });
    await row.getByRole('button', { name: 'Lock period' }).click();
    await row.getByRole('button', { name: 'Lock period' }).click();
    await expect(row.getByText('Locked', { exact: true })).toBeVisible({ timeout: 15_000 });

    await row.getByLabel('Reason to reopen').fill('E2E: a late bank statement');
    await row.getByRole('button', { name: 'Request reopening' }).click();
    await expect(row.getByRole('button', { name: 'Approve reopening' })).toBeDisabled({ timeout: 15_000 });
    await expect(row.getByText('A second person approves reopening a period')).toBeVisible();
    await financeContext.close();

    await page.goto('/staff/periods');
    await page.getByLabel('Another period (YYYY-MM)').fill(period);
    await page.getByRole('button', { name: 'Show', exact: true }).click();
    const adminRow = page.getByRole('listitem', { name: `Period ${period}` });
    await adminRow.getByRole('button', { name: 'Approve reopening' }).click();
    await adminRow.getByRole('button', { name: 'Approve reopening' }).click();
    await expect(adminRow.getByText('Open', { exact: true })).toBeVisible({ timeout: 15_000 });
    await expect(adminRow).toContainText('Reopened by');
  });
});

// The default project identity, staff.underwriter, carries neither FINANCE_OFFICER nor ADMIN.
test.describe('IFRS 17 ledger controls -- role gating', () => {
  test('a staff.underwriter session cannot reach periods or the accounting policies', async ({ page }) => {
    await page.goto('/staff/policies');
    await expectNavItemsHidden(page, 'Accounting periods', 'Accounting policies');
    await expectRouteDenied(page, '/staff/periods');
    await expectRouteDenied(page, '/staff/accounting-policies');
  });
});
