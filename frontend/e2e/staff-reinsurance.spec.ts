import { expect, type Page, test } from '@playwright/test';
import { caseAwaitingManualIssue, selectUnderwritingCase } from './underwriting';
import { dmy, todayIso } from './dates';
import { expectNavItemsHidden, expectRouteDenied } from './guards';

/**
 * Reinsurance e2e coverage against the real backend.
 *
 * Every `/treaties*`, `/policies/{n}/cessions` and `/claims/{id}/recoveries*`
 * endpoint is gated on FINANCE_OFFICER/ADMIN, not just broad staff -- unlike
 * distribution's reads, there is no REALM_STAFF-broad path into this domain
 * at all, so every test here runs under the real `staff.finance` identity
 * (auth-finance.setup.ts), reused from the distribution suite.
 *
 * A treaty's `effectiveFrom` is TODAY, and that is load-bearing.
 * `ReinsuranceApiImpl.selectApplicableTreaty` keeps every ACTIVE treaty that
 * `isActiveOn(issueDate)` (so `effectiveFrom <= today`, inclusive) and takes the
 * `max` by `effectiveFrom`, tie-broken by `createdAt`. Today's date is therefore
 * the LATEST value that still qualifies, which makes this test's own treaty the
 * winner, with `createdAt` settling any same-day tie in its favour.
 *
 * This file used to fix `effectiveFrom` at 2020-01-01, reasoning that it was
 * "safely before any real policy issue date". That reasoned about the wrong
 * comparison: what decides the winner is not the policy's issue date but OTHER
 * TREATIES' `effectiveFrom`, and every treaty this file created shared the same
 * one. The moment a treaty existed with any later date, it outranked all of them
 * permanently. That is exactly what happened -- someone authored a SURPLUS treaty
 * through the console dated a week earlier than today, with a retention limit of
 * 57,888,888, so the applicable treaty for every cession test became one that
 * correctly cedes nothing on a 2,000,000 policy. The suite reported no cession and
 * the product was behaving properly.
 *
 * A future-dated treaty cannot break this, because `isActiveOn` excludes it from
 * the candidates for a policy issued today.
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
  // Today: the latest effectiveFrom that still applies to a policy issued today.
  // See the file header for why a fixed past date was the wrong choice.
  await page.getByLabel('Effective from').fill(dmy(todayIso()));
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
    await page.getByLabel('Effective from').fill(dmy('2020-01-01'));
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
    await page.getByLabel('Reason for manual issue').fill('E2E reinsurance fixture');
    await page.getByRole('button', { name: 'Issue policy' }).click();
    await expect(page).toHaveURL(/\/staff\/policies\/POL-[A-Z0-9]+$/, { timeout: 15_000 });

    // Cession is a same-transaction, event-driven side effect of PolicyIssued
    // -- normally visible immediately, polled defensively rather than assumed.
    //
    // Poll for the AMOUNT, not for the absence of the empty-state text. The old
    // form -- reload, then assert "No reinsurance cession on this policy." is not
    // visible -- passed on the very first attempt every time, because immediately
    // after a reload the panel is still loading and that text is legitimately
    // absent. It was a poll that could not fail, and it hid a genuinely missing
    // cession behind a green step for as long as the real assertion below held.
    //
    // 50% of a 2,000,000.00 TZS sum assured, exactly what CessionCalculator's
    // QUOTA_SHARE branch computes.
    await expect(async () => {
      await page.reload();
      await expect(page.getByText('TZS 1,000,000.00')).toBeVisible({ timeout: 5_000 });
    }).toPass({ timeout: 30_000 });
    await expect(page.getByText(new RegExp(`treaty ${treatyId.slice(0, 8)}`))).toBeVisible();

    await financeContext.close();
  });

  // The anchoring and the timeout budget both live in e2e/guards.ts -- four tests
  // had written this shape independently and all four got it wrong the same way.
  test('a staff.underwriter session cannot see or reach Treaties at all', async ({ page }) => {
    await page.goto('/staff/policies');
    await expectNavItemsHidden(page, 'Treaties');
    await expectRouteDenied(page, '/staff/treaties');
  });
});
