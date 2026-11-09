import { expect, test } from '@playwright/test';
import { asAdmin } from './admin';
import { expectNavItemsHidden, expectRouteDenied } from './guards';

/**
 * Rebuild first: the read model starts empty on a freshly migrated database, and the admin's rebuild button is
 * how it is filled from the event journal. The finance officer below never sees the button.
 */
test.describe('staff reports -- rebuild', () => {
  test('an admin rebuilds the reporting data from the event journal', async ({ browser }) => {
    test.setTimeout(240_000);
    await asAdmin(browser, async (page) => {
      await page.goto('/staff/reports');
      await page.getByRole('button', { name: 'Rebuild reporting data' }).click();
      const status = page.getByRole('status');
      await expect(status).toContainText(/RUNNING|DONE/, { timeout: 15_000 });
      await expect(async () => {
        await page.getByRole('button', { name: 'Check status' }).click();
        await expect(status).toContainText('DONE —', { timeout: 2_000 });
      }).toPass({ timeout: 200_000, intervals: [2_000] });
      await expect(status).toContainText(' 0 failed');
    });
  });
});

/**
 * Reports (reporting module, batch 1). Assertions are on structure -- headings, the section table, a
 * download -- not on figures: the dev tenant's numbers change with every other spec's run.
 */
test.describe('staff reports', () => {
  test.use({ storageState: 'e2e/.auth/staff-finance.json' });

  /**
   * The rebuild goes by the permission, not the role (2026-11-09, the user: "I gave the permission to finance officer").
   * The finance approver reads reports without it; the finance officer on dev has been given it.
   */
  test('someone without the rebuild permission is not offered it', async ({ browser }) => {
    const context = await browser.newContext({ storageState: 'e2e/.auth/staff-finance-approver.json' });
    const page = await context.newPage();
    try {
      await page.goto('/staff/reports');
      await expect(page.getByRole('link', { name: 'Policies in force', exact: true })).toBeVisible({ timeout: 15_000 });
      await expect(page.getByRole('button', { name: 'Rebuild reporting data' })).toHaveCount(0);
    } finally {
      await context.close();
    }
  });

  /** 2026-11-09, batch 2: a claims variance report, from the Claims area, compared with the period before. */
  test('claims incurred compares with the period before and opens to its claims', async ({ page }) => {
    await page.goto('/staff/reports');
    // The index lists every area open (2026-11-09); the collapsed list lives behind "All reports" on a report.
    await expect(page.getByRole('heading', { name: 'Claims', exact: true })).toBeVisible({ timeout: 15_000 });
    await page.getByRole('main').getByRole('link', { name: 'Claims incurred variance', exact: true }).click();
    await expect(page.getByRole('heading', { level: 1, name: 'Claims incurred variance' })).toBeVisible();
    await page.getByLabel('First period').fill('2026-01');
    await page.getByLabel('Last period').fill('2026-12');
    await page.getByRole('button', { name: 'Run report' }).click();

    await expect(page.getByLabel('Headline figures')).toContainText('Claims incurred', { timeout: 30_000 });
    await expect(page.getByLabel('Headline figures')).toContainText('Number of claims');
    await expect(page.getByRole('heading', { name: 'Claims incurred by product' })).toBeVisible();
  });

  test('the products report lists each product and the policies behind it', async ({ page }) => {
    await page.goto('/staff/reports');
    // From the catalogue in the page, not the menu: the menu also has a "Products" (the configuration screen),
    // which is exactly why the report is not called that.
    await page.getByRole('main').getByRole('link', { name: 'Policies by product', exact: true }).click();
    await expect(page.getByRole('heading', { name: 'Policies by product', exact: true })).toBeVisible();
    await page.getByRole('button', { name: 'Run report' }).click();

    // Two sections: the per-product summary, then one row per policy.
    // 30s, not 15s: the run is ~3s alone, but it follows the rebuild, and on a test-polluted book (500+
    // products, each named through product::api) it went past 15s once while the replay settled.
    await expect(page.getByRole('heading', { name: /^Products on \d{4}-\d{2}-\d{2}$/ })).toBeVisible({ timeout: 30_000 });
    await expect(page.getByRole('heading', { name: /^Policies on \d{4}-\d{2}-\d{2}$/ })).toBeVisible();
    const register = page.getByRole('table', { name: /^Policies on / });
    await expect(register.getByRole('columnheader', { name: 'Client' })).toBeVisible();
    await expect(register.getByRole('columnheader', { name: 'Status' })).toBeVisible();
    // A book longer than the console draws says so rather than silently stopping; a fresh stack's book is shorter
    // and draws whole. Scoped to the register's own section: on a test-polluted book the product summary runs past
    // the limit too.
    const registerSection = page.locator('section').filter({ has: page.getByRole('heading', { name: /^Policies on / }) });
    await expect(register.getByRole('row').nth(1)).toBeVisible();
    if ((await register.getByRole('row').count()) > 200) {
      await expect(registerSection.getByText(/^Showing the first 200 of [\d,]+ rows\./)).toBeVisible();
      await expect(register.getByRole('row')).toHaveCount(201);
    } else {
      await expect(registerSection.getByText(/^Showing the first 200/)).toHaveCount(0);
    }

    // Narrowed to one product, both sections are that product's alone -- a filter no report could apply from
    // the console before, though every report's query took it.
    await page.getByLabel('Product', { exact: true }).selectOption({ label: 'Demo Term Life' });
    await page.getByRole('button', { name: 'Run report' }).click();
    await expect(page).toHaveURL(/productId=[0-9a-f-]{36}/);
    const summary = page.getByRole('table', { name: /^Products on / });
    await expect(summary.getByRole('row')).toHaveCount(3); // header, the one product, the total
    await expect(summary.getByRole('row').nth(1)).toContainText('Demo Term Life');
    await expect(register.getByRole('row').nth(1)).toContainText('Demo Term Life');
  });

  /**
   * Accounting reports (batch 2a). These read the general ledger live rather than the rebuilt projection, so
   * unlike every test above they do not depend on the rebuild having run. Assertions are on structure and on
   * the ledger's own check -- not on figures, which move with every other spec's postings.
   */
  test('a finance officer runs a trial balance and sees whether the ledger balances', async ({ page }) => {
    await page.goto('/staff/reports');
    await expect(page.getByRole('heading', { name: 'Accounting' })).toBeVisible({ timeout: 15_000 });

    await page.getByRole('main').getByRole('link', { name: 'Trial balance', exact: true }).click();
    await expect(page.getByRole('heading', { name: 'Trial balance', exact: true })).toBeVisible();

    // A month, not a date: the control is type="month" because the ledger has no posting date.
    await page.getByLabel('Period', { exact: true }).fill('2026-10');
    await page.getByRole('button', { name: 'Run report' }).click();

    await expect(page.getByRole('heading', { name: /^Balances through \d{4}-\d{2}$/ })).toBeVisible({ timeout: 30_000 });
    await expect(page.getByRole('heading', { name: 'Ledger check' })).toBeVisible();
    // The ledger's own answer, either way -- the report states it rather than leaving two columns to be compared.
    await expect(page.getByText(/the ledger balances|do NOT balance credits/)).toBeVisible();
  });

  test('an income statement runs across a span of periods and names the result', async ({ page }) => {
    await page.goto('/staff/reports');
    await page.getByRole('main').getByRole('link', { name: 'Income statement', exact: true }).click();
    // The page title; the statement's own table carries the same name one level down (2026-11-09).
    await expect(page.getByRole('heading', { level: 1, name: 'Income statement', exact: true })).toBeVisible();

    await page.getByLabel('First period').fill('2026-01');
    await page.getByLabel('Last period').fill('2026-12');
    await page.getByRole('button', { name: 'Run report' }).click();

    await expect(page.getByRole('heading', { name: 'Result', exact: true })).toBeVisible({ timeout: 30_000 });
    await expect(page.getByRole('cell', { name: /Profit for the period|Loss for the period/ }).first()).toBeVisible();
    // A balance-sheet class never appears on a profit and loss.
    await expect(page.getByRole('heading', { name: 'Assets', exact: true })).toHaveCount(0);

    // 2026-11-09, the user: the computation "until it reaches the figure summed up". A line opens to its accounts
    // and states the server's own sum beneath them.
    await expect(page.getByLabel('Headline figures')).toContainText('Profit for the period');
    await page.getByRole('button', { name: 'Show what makes up Insurance revenue' }).click();
    await expect(page.getByText(/^(✓|Does not add up:) .* = /).first()).toBeVisible({ timeout: 15_000 });
    await expect(page.getByText(/^Does not add up/)).toHaveCount(0);
  });

  test('a general ledger asks for an account instead of failing without one', async ({ page }) => {
    await page.goto('/staff/reports');
    await page.getByRole('main').getByRole('link', { name: 'General ledger', exact: true }).click();

    // The select says what it wants: "Every account" would be a lie on a report about a single one.
    await expect(page.getByLabel('Account', { exact: true })).toContainText('Choose an account');

    await page.getByRole('button', { name: 'Run report' }).click();
    // Asked, beside the form -- not submitted and answered with a region-replacing failure carrying a
    // "Try again" that would fail identically and a trace id for a field nobody had filled in.
    await expect(page.getByRole('alert')).toContainText('Choose an account.', { timeout: 15_000 });
    await expect(page.getByRole('button', { name: 'Try again' })).toHaveCount(0);

    // The chart is offered rather than typed, and only accounts that take postings are in it.
    await page.getByLabel('Account', { exact: true }).selectOption({ index: 1 });
    await page.getByRole('button', { name: 'Run report' }).click();
    await expect(page.getByRole('heading', { name: /^Account \d{4}, \d{4}-\d{2} to \d{4}-\d{2}$/ }))
      .toBeVisible({ timeout: 30_000 });
    await expect(page.getByRole('cell', { name: 'Opening balance' })).toBeVisible();
  });

  test('runs a report from the catalogue and downloads it', async ({ page }) => {
    await page.goto('/staff/reports');
    await expect(page.getByRole('heading', { name: 'Reports', exact: true })).toBeVisible();
    // The areas (2026-11-09): what the old catalogue called Management is Policies and Clients.
    await expect(page.getByRole('heading', { name: 'Policies', exact: true })).toBeVisible();

    await page.getByRole('main').getByRole('link', { name: 'Policies in force', exact: true }).click();
    // exact: the section heading "Policies in force on <date>" would otherwise match too.
    await expect(page.getByRole('heading', { name: 'Policies in force', exact: true })).toBeVisible();
    await page.getByLabel('As of').fill('2026-10-01');
    await page.getByRole('button', { name: 'Run report' }).click();

    await expect(page).toHaveURL(/asOf=2026-10-01/);
    await expect(page.getByRole('heading', { name: 'Policies in force on 2026-10-01' })).toBeVisible({ timeout: 15_000 });

    const download = page.waitForEvent('download');
    await page.getByRole('button', { name: 'Excel' }).click();
    expect((await download).suggestedFilename()).toMatch(/^policies-in-force-\d{4}-\d{2}-\d{2}\.xlsx$/);
  });

  /**
   * 2026-11-09, batch 3: capital adequacy on placeholder factors says so above its figures, and its required capital
   * opens to the risk charges and states that they add up. No figures asserted: the dev book moves with every spec.
   */
  test('capital adequacy says its factors are placeholders and its required capital adds up', async ({ page }) => {
    await page.goto('/staff/reports');
    await page.getByRole('main').getByRole('link', { name: 'Capital adequacy (RBC)', exact: true }).click();
    await expect(page.getByRole('heading', { level: 1, name: 'Capital adequacy (RBC)' })).toBeVisible();
    await page.getByLabel('Period', { exact: true }).fill('2026-10');
    await page.getByRole('button', { name: 'Run report' }).click();

    await expect(page.getByLabel('Headline figures')).toContainText('Capital adequacy ratio', { timeout: 30_000 });
    await expect(page.getByLabel('Before you read these figures')).toContainText("Placeholder risk charges, not TIRA's");
    await expect(page.getByRole('heading', { name: 'Against the thresholds' })).toBeVisible();
    await page.getByRole('button', { name: 'Show what makes up Required capital' }).click();
    await expect(page.getByRole('cell', { name: 'Insurance risk' })).toBeVisible({ timeout: 15_000 });
    await expect(page.getByText(/^✓ .* = /).first()).toBeVisible();
    await expect(page.getByText(/^Does not add up/)).toHaveCount(0);

    await page.getByRole('link', { name: 'Factors' }).click();
    await expect(page.getByRole('heading', { level: 1, name: 'Solvency factors' })).toBeVisible();
    await expect(page.getByRole('table', { name: 'Thresholds' })).toContainText('Minimum capital requirement (MCR)');
  });

  /** 2026-11-09, batch 4: the book's movement since a month earlier, and the new free-look report. */
  test('policies in force shows how the book moved, and free-look sits with the policy reports', async ({ page }) => {
    await page.goto('/staff/reports/policies-in-force');
    await expect(page.getByRole('heading', { name: /^How the book moved, / })).toBeVisible({ timeout: 30_000 });
    await expect(page.getByLabel('Headline figures')).toContainText('Policies in force');
    await expect(page.getByLabel('Compare with')).toContainText('A month earlier');

    await page.goto('/staff/reports');
    await page.getByRole('main').getByRole('link', { name: 'Free-look and not taken up', exact: true }).click();
    await expect(page.getByRole('heading', { level: 1, name: 'Free-look and not taken up' })).toBeVisible();
    await page.getByLabel('From').fill('2026-01-01');
    await page.getByLabel('To').fill('2026-12-31');
    await page.getByRole('button', { name: 'Run report' }).click();
    await expect(page.getByRole('heading', { name: /^Free-look and not taken up, 2026-01-01 to 2026-12-31$/ }))
      .toBeVisible({ timeout: 30_000 });
  });

  /** 2026-11-09, batch 5: commissions earned to paid, reinsurance by treaty, and claims net of reinsurance. */
  test('commissions, reinsurance and claims net of reinsurance run from their areas', async ({ page }) => {
    await page.goto('/staff/reports');
    await page.getByRole('main').getByRole('link', { name: 'Commissions', exact: true }).click();
    await page.getByLabel('First period').fill('2026-01');
    await page.getByLabel('Last period').fill('2026-12');
    await page.getByRole('button', { name: 'Run report' }).click();
    await expect(page.getByRole('heading', { name: /^Earned to paid, / })).toBeVisible({ timeout: 30_000 });
    await expect(page.getByLabel('Headline figures')).toContainText('Commission booked');

    await page.goto('/staff/reports');
    await page.getByRole('main').getByRole('link', { name: 'Reinsurance', exact: true }).click();
    await page.getByLabel('First period').fill('2026-01');
    await page.getByLabel('Last period').fill('2026-12');
    await page.getByRole('button', { name: 'Run report' }).click();
    await expect(page.getByRole('heading', { name: 'Treaties today' })).toBeVisible({ timeout: 30_000 });

    await page.goto('/staff/reports');
    await page.getByRole('main').getByRole('link', { name: 'Claims net of reinsurance', exact: true }).click();
    await page.getByLabel('First period').fill('2026-01');
    await page.getByLabel('Last period').fill('2026-12');
    await page.getByRole('button', { name: 'Run report' }).click();
    await expect(page.getByLabel('Headline figures')).toContainText('Kept (net of reinsurance)', { timeout: 30_000 });
  });

  test('a cohort report asks for months and a clients report for both a date and a period', async ({ page }) => {
    await page.goto('/staff/reports/persistency');
    await expect(page.getByLabel('First activation month')).toBeVisible({ timeout: 15_000 });
    await expect(page.getByLabel('As of')).toHaveCount(0);

    await page.goto('/staff/reports/clients');
    await expect(page.getByLabel('As of')).toBeVisible({ timeout: 15_000 });
    await expect(page.getByLabel('From')).toBeVisible();
    await expect(page.getByRole('heading', { name: /^Clients on / })).toBeVisible({ timeout: 15_000 });
  });

  test('a reversed period is refused before it reaches the server', async ({ page }) => {
    await page.goto('/staff/reports/new-business');
    await page.getByLabel('From').fill('2026-12-01');
    await page.getByLabel('To').fill('2026-01-01');
    await page.getByRole('button', { name: 'Run report' }).click();
    await expect(page.getByText('From must not be after To.')).toBeVisible();
  });
});

test.describe('staff reports -- role gating', () => {
  test('a staff.underwriter session cannot see or reach reports', async ({ page }) => {
    await page.goto('/staff/policies');
    await expectNavItemsHidden(page, 'Reports');
    await expectRouteDenied(page, '/staff/reports');
  });
});
