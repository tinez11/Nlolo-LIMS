import { expect, type Page, test } from '@playwright/test';
import { asAdmin } from './admin';
import { caseAwaitingManualIssue, selectUnderwritingCase } from './underwriting';
import { dmy, todayIso } from './dates';
import { expectNavItemsHidden, expectRouteDenied } from './guards';
import { approveTreatyAsSecondPerson, firstFreeDay, treatyForToday } from './treaties';

/**
 * Reinsurance e2e coverage against the real backend.
 *
 * Every `/treaties*`, `/policies/{n}/cessions` and `/claims/{id}/recoveries*`
 * endpoint is gated on FINANCE_OFFICER/ADMIN, not just broad staff -- unlike
 * distribution's reads, there is no REALM_STAFF-broad path into this domain
 * at all, so every test here runs under the real `staff.finance` identity
 * (auth-finance.setup.ts), reused from the distribution suite.
 *
 * Only one treaty is in force on any day (2026-11-08): each policy is ceded to one treaty, and a second in force
 * beside it took no business. So this file no longer creates a treaty effective today on every run -- see
 * e2e/treaties.ts: the cession test uses the treaty that takes today's policies, and the authoring test starts its
 * treaty on the first day nothing else is in force.
 */

async function createRealQuotaShareTreaty(
  page: Page,
  cessionPercent: string,
  commissionPercent: string,
  effectiveFrom: string,
): Promise<{ treatyId: string; reinsurerName: string }> {
  const reinsurerName = `E2E Re ${Date.now()}`;
  await page.goto('/staff/treaties/new');
  await page.getByLabel('Reinsurer name').fill(reinsurerName);
  // QUOTA_SHARE is the default selection.
  await page.getByLabel('Retention limit').fill('0.00');
  await page.getByLabel('Cession percent').fill(cessionPercent);
  // IFRS 17 I3c: every treaty states its commission not contingent on claims; 0 is an answer.
  await page.getByLabel('Reinsurance commission').fill(commissionPercent);
  // The first day no other treaty is in force (2026-11-08: only one is, at a time) -- see e2e/treaties.ts.
  await page.getByLabel('Effective from').fill(dmy(effectiveFrom));
  await page.getByRole('button', { name: 'Create treaty' }).click();
  await expect(page).toHaveURL(/\/staff\/treaties\/[0-9a-f-]{36}$/, { timeout: 15_000 });
  const treatyId = page.url().split('/').pop() as string;
  return { treatyId, reinsurerName };
}

test.describe('staff reinsurance', () => {
  test('creates a QUOTA_SHARE treaty, and it is readable from the list and detail page', async ({
    browser,
    request,
  }) => {
    const financeContext = await browser.newContext({ storageState: 'e2e/.auth/staff-finance.json' });
    const page = await financeContext.newPage();

    // The first day no other treaty is in force: only one is, at a time (2026-11-08).
    const from = await firstFreeDay(request);
    const { treatyId, reinsurerName } = await createRealQuotaShareTreaty(page, '25.00', '12.50', from);
    // Finance proposed it; it waits for a second person, who cannot be finance itself (finding 170).
    await expect(page.getByText('It cedes nothing until a second person approves it', { exact: false })).toBeVisible();
    await approveTreatyAsSecondPerson(request, treatyId);
    await page.reload();
    await expect(page.getByRole('heading', { name: reinsurerName })).toBeVisible();
    await expect(page.getByText('Quota share', { exact: true })).toBeVisible();
    // A quota share is described by what it takes, not by the 0.00 retention it stores.
    await expect(page.getByText('25.00% of each policy')).toBeVisible();
    await expect(page.getByText('12.50%')).toBeVisible();
    // IFRS 17 I3c: the monthly bordereaux. A new treaty has no closed month yet.
    await expect(page.getByRole('heading', { name: 'Bordereaux' })).toBeVisible();
    await expect(page.getByText('No month closed yet')).toBeVisible();

    await page.goto('/staff/treaties');
    await expect(page.getByText(reinsurerName)).toBeVisible();
    // Its standing is read from its dates, not the stored flag: in force from today, not started from a later day.
    await page.getByRole('button', { name: from === todayIso() ? 'In force' : 'Not started' }).click();
    await expect(page.getByText(reinsurerName)).toBeVisible();
    // Found by name: the register is searchable now, and the search narrows to this one treaty.
    await page.getByLabel('Search reinsurers').fill(reinsurerName);
    await expect(page.getByRole('row')).toHaveCount(2);
    await expect(page.getByRole('row').nth(1)).toContainText(reinsurerName);
    // Nothing matches a name nobody has, and the page says so rather than going blank.
    await page.getByLabel('Search reinsurers').fill(`${reinsurerName} nobody`);
    await expect(page.getByText('No treaties match')).toBeVisible();

    await financeContext.close();
  });

  /*
   * 'rejects a QUOTA_SHARE treaty with no cession percent, before reaching the network' was
   * removed: it opened a second browser context as finance to fill a form it never submitted.
   * createTreatyForm.test.ts pins that rule and its neighbours -- a missing cession percent, one
   * over 100, a zero one, and the SURPLUS/XOL shapes where the field is legitimately absent --
   * which is more of the rule than the single blank case here reached.
   */
  test('issuing a policy against an ACTIVE treaty produces a real cession, visible on the policy', async ({
    browser,
    request,
  }) => {
    const financeContext = await browser.newContext({ storageState: 'e2e/.auth/staff-finance.json' });
    const page = await financeContext.newPage();

    // The treaty that takes today's policies -- created as a 50% quota share only when none is in force (2026-11-08).
    const treaty = await treatyForToday(request);
    test.skip(treaty.treatyType !== 'QUOTA_SHARE' || !treaty.cessionPercent,
      `the treaty in force today (${treaty.reinsurerName}) is a ${treaty.treatyType}; this test measures a quota share`);
    const { treatyId } = treaty;
    const ceded = (2_000_000 * Number(treaty.cessionPercent)) / 100;

    // Manual issue names a real, unissued case now. The policyholder and product
    // come from it by prefill, so this no longer picks them by hand. The sum assured
    // still does: the case view @JsonIgnores it, so the console cannot read it.
    // Issuing is underwriting's, not finance's, since staff permissions (2026-10-26): the admin issues, finance reads
    // the cession.
    const policyUrl = await asAdmin(browser, async (admin) => {
      const caseId = await caseAwaitingManualIssue(admin);
      await admin.goto('/staff/policies/new');
      await selectUnderwritingCase(admin, caseId);
      await expect(admin.getByText('Resolving product version…')).not.toBeVisible();
      await admin.getByLabel('Sum assured').fill('2000000.00');
      await admin.getByLabel('Premium', { exact: true }).fill('800.00');
      // Required, and MIGRATION so the policy is in force rather than an offer.
      await admin.getByLabel('Why is this being issued by hand?').selectOption('MIGRATION');
      await admin.getByLabel('Reason for manual issue').fill('E2E reinsurance fixture');
      await admin.getByRole('button', { name: 'Issue policy' }).click();
      await expect(admin).toHaveURL(/\/staff\/policies\/POL-[A-Z0-9]+$/, { timeout: 15_000 });
      return admin.url();
    });

    // Cessions are a tab on the record now, and the poll below reloads -- which preserves
    // the query string, so landing on the tab once here is enough.
    await page.goto(`${policyUrl}?tab=reinsurance`);

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
    // The treaty's share of a 2,000,000.00 TZS sum assured, exactly what CessionCalculator's
    // QUOTA_SHARE branch computes.
    const cededText = `TZS ${ceded.toLocaleString('en-US', { minimumFractionDigits: 2, maximumFractionDigits: 2 })}`;
    await expect(async () => {
      await page.reload();
      await expect(page.getByText(cededText)).toBeVisible({ timeout: 5_000 });
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
