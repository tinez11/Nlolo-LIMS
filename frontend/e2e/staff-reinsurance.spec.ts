import { expect, type Page, test } from '@playwright/test';

/**
 * Reinsurance e2e coverage against the real backend.
 *
 * Every `/treaties*`, `/policies/{n}/cessions` and `/claims/{id}/recoveries*`
 * endpoint is gated on FINANCE_OFFICER/ADMIN, not just broad staff -- unlike
 * distribution's reads, there is no REALM_STAFF-broad path into this domain
 * at all, so every test here runs under the real `staff.finance` identity
 * (auth-finance.setup.ts), reused from the distribution suite.
 *
 * A treaty's `effectiveFrom` is fixed at 2020-01-01 (safely before any real
 * policy issue date) rather than "today": `ReinsuranceApiImpl
 * .selectApplicableTreaty` picks the ACTIVE treaty with the latest
 * `effectiveFrom`, tie-broken by `createdAt` -- sharing one fixed
 * `effectiveFrom` across every treaty this file creates means the tie-break
 * always favors whichever treaty a given test created most recently, so each
 * test's own policy issuance cedes against ITS OWN treaty, never an earlier
 * test's leftover one.
 */

async function createRealQuotaShareTreaty(
  page: Page,
  cessionPercent: string,
): Promise<{ treatyId: string; reinsurerName: string }> {
  const reinsurerName = `E2E Re ${Date.now()}`;
  await page.goto('/staff/treaties/new');
  await page.getByLabel('Reinsurer name').fill(reinsurerName);
  // QUOTA_SHARE is the default selection.
  await page.getByLabel('Retention limit').fill('0.00');
  await page.getByLabel('Cession percent').fill(cessionPercent);
  await page.getByLabel('Effective from').fill('2020-01-01');
  await page.getByRole('button', { name: 'Create treaty' }).click();
  await expect(page).toHaveURL(/\/staff\/treaties\/[0-9a-f-]{36}$/, { timeout: 15_000 });
  const treatyId = page.url().split('/').pop() as string;
  return { treatyId, reinsurerName };
}

test.describe('staff reinsurance', () => {
  test('creates a QUOTA_SHARE treaty, and it is readable from the list and detail page', async ({
    browser,
  }) => {
    const financeContext = await browser.newContext({ storageState: 'e2e/.auth/staff-finance.json' });
    const page = await financeContext.newPage();

    const { reinsurerName } = await createRealQuotaShareTreaty(page, '25.00');
    await expect(page.getByRole('heading', { name: reinsurerName })).toBeVisible();
    await expect(page.getByText('QUOTA SHARE')).toBeVisible();
    await expect(page.getByText('25.00%')).toBeVisible();

    await page.goto('/staff/treaties');
    await expect(page.getByText(reinsurerName)).toBeVisible();
    await page.getByRole('button', { name: 'Active' }).click();
    await expect(page.getByText(reinsurerName)).toBeVisible();

    await financeContext.close();
  });

  test('rejects a QUOTA_SHARE treaty with no cession percent, before reaching the network', async ({
    browser,
  }) => {
    const financeContext = await browser.newContext({ storageState: 'e2e/.auth/staff-finance.json' });
    const page = await financeContext.newPage();

    let requestFired = false;
    page.on('request', (req) => {
      if (req.method() === 'POST' && req.url().endsWith('/treaties')) requestFired = true;
    });

    await page.goto('/staff/treaties/new');
    await page.getByLabel('Reinsurer name').fill('E2E No Percent Re');
    await page.getByLabel('Retention limit').fill('0.00');
    // Cession percent left blank -- QUOTA_SHARE requires it.
    await page.getByLabel('Effective from').fill('2020-01-01');
    await page.getByRole('button', { name: 'Create treaty' }).click();

    await expect(page.getByText('Must be a decimal percentage like 25.00')).toBeVisible();
    expect(requestFired).toBe(false);

    await financeContext.close();
  });

  test('issuing a policy against an ACTIVE treaty produces a real cession, visible on the policy', async ({
    browser,
  }) => {
    const financeContext = await browser.newContext({ storageState: 'e2e/.auth/staff-finance.json' });
    const page = await financeContext.newPage();

    const { treatyId } = await createRealQuotaShareTreaty(page, '50.00');

    await page.goto('/staff/policies/new');
    await page.getByRole('button', { name: 'Search for the policyholder by name' }).click();
    await page.getByPlaceholder('Type a name to search').fill('Amina');
    await page.getByText('Amina Owner').click();
    await page.getByLabel('Product').selectOption({ label: 'Demo Term Life (DEMO-TERM-01)' });
    await expect(page.getByText('Resolving product version…')).not.toBeVisible();
    await page.getByLabel('Sum assured').fill('2000000.00');
    await page.getByLabel('Premium', { exact: true }).fill('800.00');
    await page.getByLabel('Reason for manual issue').fill('E2E reinsurance fixture');
    await page.getByRole('button', { name: 'Issue policy' }).click();
    await expect(page).toHaveURL(/\/staff\/policies\/POL-[A-Z0-9]+$/, { timeout: 15_000 });

    // Cession is a same-transaction, event-driven side effect of PolicyIssued
    // -- normally visible immediately, polled defensively rather than assumed.
    await expect(async () => {
      await page.reload();
      await expect(page.getByText('No reinsurance cession on this policy.')).not.toBeVisible();
    }).toPass({ timeout: 20_000 });

    // 50% of a 2,000,000.00 TZS sum assured, exactly what CessionCalculator's
    // QUOTA_SHARE branch computes.
    await expect(page.getByText('TZS 1,000,000.00')).toBeVisible();
    await expect(page.getByText(new RegExp(`treaty ${treatyId.slice(0, 8)}`))).toBeVisible();

    await financeContext.close();
  });

  test('a staff.underwriter session cannot see or reach Treaties at all', async ({ page }) => {
    await page.goto('/staff/policies');
    await expect(page.getByRole('link', { name: 'Treaties' })).not.toBeVisible();

    await page.goto('/staff/treaties');
    await expect(page.getByText('You do not have access to this')).toBeVisible();
  });
});
