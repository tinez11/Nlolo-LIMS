import { expect, test } from '@playwright/test';
import { caseAwaitingManualIssue, selectUnderwritingCase } from './underwriting';
import { issueGroupScheme } from './groupSchemes';

/**
 * `POST /policies/{n}/suspend`/`resume`/`reinstate` -- all three were fully
 * implemented, tested, and event-publishing on the backend since M3, but had
 * NO controller mapping at all until this staff-portal CRUD audit found the
 * gap (see PolicyContractTest for the HTTP-level contract coverage). This
 * file proves the real end-to-end path through the UI.
 *
 * `POLICY_SUSPENSION_ELIGIBLE_CATEGORIES` (refdata/V2) seeds only GROUP_LIFE
 * as suspension-eligible -- the seeded demo product (`DEMO-TERM-01`) is
 * TERM_LIFE, so a real suspend attempt against it is the fixture for the
 * ineligible-category 409, and a fresh GROUP_LIFE product/policy (created
 * through the real Products + Issue Policy screens, not fabricated) is the
 * fixture for the real suspend/resume success path.
 *
 * `reinstate`'s success path (LAPSED -> REINSTATED) has no UI-reachable route
 * to LAPSED at all -- `lapsePolicy` is only ever called automatically off
 * billing arrears, per the same audit -- so it is covered at the backend
 * contract-test level only; this file covers the button's absence on a
 * non-LAPSED policy implicitly (only Suspend/Resume ever render below).
 */

async function issuePolicyAgainst(
  page: import('@playwright/test').Page,
  productLabel: string,
  reason: string,
): Promise<string> {
  // Manual issue names a real, unissued case now. This one still selects the product by
  // hand after the prefill, because these tests issue against a product they published
  // themselves rather than the case's seeded one.
  const caseId = await caseAwaitingManualIssue(page);
  await page.goto('/staff/policies/new');
  await selectUnderwritingCase(page, caseId);
  await page.getByLabel('Product').selectOption({ label: productLabel });
  await expect(page.getByText('Resolving product version…')).not.toBeVisible();
  await page.getByLabel('Sum assured').fill('2000000.00');
  await page.getByLabel('Premium', { exact: true }).fill('800.00');
  // Required, and MIGRATION so the policy is in force rather than an offer.
  await page.getByLabel('Why is this being issued by hand?').selectOption('MIGRATION');
  await page.getByLabel('Reason for manual issue').fill(reason);
  await page.getByRole('button', { name: 'Issue policy' }).click();
  await expect(page).toHaveURL(/\/staff\/policies\/POL-[A-Z0-9]+$/, { timeout: 15_000 });
  return page.url().split('/').pop() as string;
}

test.describe('staff policy lifecycle', () => {
  /*
   * SKIPPED, and the skip is the finding: **no browser can reach an in-force group scheme.**
   *
   * This test used to author a GROUP_LIFE product and issue it from the manual single-life
   * screen on a MIGRATION basis, which put it straight in force. `5571ca6` closed that path
   * on the server -- `NotASingleLifeProductException`, because a group policy issued that
   * way covers nobody: it has no member schedule -- and the console correctly stopped
   * offering group products in that dropdown. The test was not updated and went red.
   *
   * Rebuilding the fixture the only way a group policy is now born (propose -> assess ->
   * decide, via `issueGroupScheme`) gets as far as an OFFER and stops there. Suspend renders
   * only for status ACTIVE (`LifecycleActions`), a scheme becomes ACTIVE when the employer's
   * first premium clears, and **this console has no action that accepts an offer** -- for
   * group or individual business. `staff-group-schemes.spec.ts` hit the same wall for the
   * joiner assertion and documented it there; `e2e/policies.ts` only dodges it for
   * individual policies because MIGRATION is still open to them.
   *
   * So this is a product gap, not a test to repair. The behaviour itself is proven against a
   * real database in `PolicyApiIntegrationTest.suspendAndResumeRoundTripForAnEligibleCategory`
   * and `resumingASuspendedPolicyPublishesPolicyResumed`; what is NOT covered anywhere is the
   * two buttons in this console. Un-skip the day an offer can be accepted from the browser --
   * `issueGroupScheme` already delivers the scheme, so only the activation step is missing.
   */
  test.skip('suspends and resumes a real GROUP_LIFE policy end to end', async ({
    page,
    browser,
  }) => {
    test.slow();
    const policyNumber = await issueGroupScheme(page, browser);
    await page.goto(`/staff/policies/${policyNumber}`);

    await expect(page.getByRole('heading', { level: 2, name: 'Lifecycle' })).toBeVisible();
    await page.getByRole('button', { name: 'Suspend' }).click();
    await page.getByLabel('Reason').fill('Employer group scheme in arrears');
    await page.getByRole('button', { name: 'Suspend policy' }).click();

    // StatusBadge humanizes SUSPENDED/ACTIVE to title case for display.
    await expect(page.getByText('Suspended')).toBeVisible({ timeout: 15_000 });
    await expect(page.getByRole('button', { name: 'Resume' })).toBeVisible();

    await page.getByRole('button', { name: 'Resume' }).click();
    await expect(page.getByText('Active')).toBeVisible({ timeout: 15_000 });
    await expect(page.getByRole('button', { name: 'Suspend' })).toBeVisible();
  });

  test('rejects suspending an ineligible (TERM_LIFE) product category with a real 409', async ({ page }) => {
    const policyNumber = await issuePolicyAgainst(
      page,
      'Demo Term Life (DEMO-TERM-01)',
      'E2E ineligible-suspend fixture',
    );

    await page.getByRole('button', { name: 'Suspend' }).click();
    await page.getByLabel('Reason').fill('Should be rejected -- TERM_LIFE is not suspension-eligible');
    await page.getByRole('button', { name: 'Suspend policy' }).click();

    await expect(page.getByRole('alert')).toBeVisible({ timeout: 15_000 });
    // Still ACTIVE, and the Suspend form is still open (not dismissed as if it
    // had succeeded) -- confirmed by re-checking the URL stayed put, matching
    // this policy's own number.
    await expect(page).toHaveURL(new RegExp(`/staff/policies/${policyNumber}$`));
    await expect(page.getByLabel('Reason')).toBeVisible();
  });

  test('the search bar finds a real policy by its policy number', async ({ page }) => {
    const policyNumber = await issuePolicyAgainst(page, 'Demo Term Life (DEMO-TERM-01)', 'E2E search bar fixture');

    await page.goto('/staff/policies');
    await expect(page.getByRole('heading', { name: 'Policies' })).toBeVisible();
    await page.getByPlaceholder('Search by policy number').fill(policyNumber);
    await expect(page).toHaveURL(new RegExp(`q=${policyNumber}`), { timeout: 5000 });
    await expect(page.getByText(policyNumber)).toBeVisible();
  });
});
