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
        await expect(status).toContainText('DONE --', { timeout: 2_000 });
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

  test('a finance officer is not offered the rebuild', async ({ page }) => {
    await page.goto('/staff/reports');
    await expect(page.getByRole('link', { name: 'Policies in force' })).toBeVisible({ timeout: 15_000 });
    await expect(page.getByRole('button', { name: 'Rebuild reporting data' })).toHaveCount(0);
  });

  test('runs a report from the catalogue and downloads it', async ({ page }) => {
    await page.goto('/staff/reports');
    await expect(page.getByRole('heading', { name: 'Reports', exact: true })).toBeVisible();
    await expect(page.getByRole('heading', { name: 'Management' })).toBeVisible();

    await page.getByRole('link', { name: 'Policies in force' }).click();
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
