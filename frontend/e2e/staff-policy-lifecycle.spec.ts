import { expect, test } from '@playwright/test';
import { issuePolicyAgainst } from './manualIssue';
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

  // Audit 2026-10-07: the console offered Suspend on every policy and the server refused it on all but the
  // categories POLICY_SUSPENSION_ELIGIBLE_CATEGORIES names. It now reads that list and says why instead; the
  // server's own 409 stays covered by PolicyApiIntegrationTest.
  test('does not offer to suspend an ineligible (TERM_LIFE) product category, and says why', async ({ page }) => {
    const policyNumber = await issuePolicyAgainst(
      page,
      'Demo Term Life (DEMO-TERM-01)',
      'E2E ineligible-suspend fixture',
    );

    await expect(page).toHaveURL(new RegExp(`/staff/policies/${policyNumber}$`));
    await expect(page.getByText('This kind of policy is not suspended')).toBeVisible({ timeout: 15_000 });
    await expect(page.getByRole('button', { name: 'Suspend' })).toHaveCount(0);
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
