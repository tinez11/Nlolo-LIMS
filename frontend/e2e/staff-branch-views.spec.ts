import { expect, test } from '@playwright/test';

/**
 * Branch views (2026-11-23, backend/docs/superpowers/specs/2026-11-23-branch-views-design.md), as `staff.management`:
 * the dashboard cut to one branch, a card opening its report on that branch, every branch one choice away, and a
 * company-wide report saying so. Views, not walls: the branch is chosen in the address here, as a shared link would.
 *
 * The starting rule (home branch unless head office) is the unit tests' (branchChoice.test.ts): there is no way to
 * change a dev login's home branch through the platform yet, and a spec must not write the database behind it.
 */
test.describe('staff branch views', () => {
  test('a branch cuts the overview and travels into the report it opens', async ({ browser }) => {
    test.slow();
    const context = await browser.newContext({ storageState: 'e2e/.auth/staff-management.json' });
    const page = await context.newPage();
    await page.goto('/staff/dashboard?branch=ARU');

    const branch = page.getByLabel('Branch', { exact: true });
    await expect(branch).toHaveValue('ARU', { timeout: 30_000 });
    await expect(page.getByText(/Arusha branch · Compared with the same months a year earlier/)).toBeVisible();
    await expect(page.getByText('Premiums collected')).toBeVisible({ timeout: 60_000 });
    // The follow-up figures are the company's, and the card says so.
    await expect(page.getByText(/overdue · All branches/)).toBeVisible();

    await page.getByRole('link', { name: 'New policies' }).click();
    await expect(page).toHaveURL(/\/staff\/reports\/new-business\?branch=ARU$/);
    const reportBranch = page.getByLabel('Branch', { exact: true });
    await expect(reportBranch).toHaveValue('ARU', { timeout: 30_000 });
    await expect(page.getByText(/· Arusha branch/).first()).toBeVisible({ timeout: 30_000 });
    // The headline figure, not the table's column of the same name.
    const activated = () => page.getByText('Policies activated', { exact: true }).first().locator('xpath=..');
    const arusha = await activated().innerText();

    // Every branch, one choice away: the address keeps "all", and the figures change.
    await reportBranch.selectOption('all');
    await page.getByRole('button', { name: 'Run report' }).click();
    await expect(page).toHaveURL(/branch=all/);
    await expect(page.getByText(/· Arusha branch/)).toHaveCount(0, { timeout: 30_000 });
    await expect(activated()).not.toHaveText(arusha, { timeout: 30_000 });
    await context.close();
  });

  test('a company-wide report offers no branch and says it is company-wide', async ({ browser }) => {
    const context = await browser.newContext({ storageState: 'e2e/.auth/staff-management.json' });
    const page = await context.newPage();
    await page.goto('/staff/reports/balance-sheet?branch=ARU');

    await expect(page.getByText(/Company-wide · Read live from the ledger/)).toBeVisible({ timeout: 60_000 });
    await expect(page.getByLabel('Branch', { exact: true })).toHaveCount(0);
    await context.close();
  });
});
