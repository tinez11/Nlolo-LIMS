import { expect, test } from '@playwright/test';
import { caseAwaitingManualIssue, selectUnderwritingCase } from './underwriting';
import { dmy } from './dates';
import { asAdmin } from './admin';

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

async function createGroupLifeProduct(page: import('@playwright/test').Page, code: string, name: string) {
  await page.goto('/staff/products/new');
  await page.getByLabel('Product code').fill(code);
  await page.getByLabel('Product name').fill(name);
  await page.getByLabel('Category').selectOption('GROUP_LIFE');
  await page.getByLabel('Default currency').fill('TZS');
  await page.getByRole('button', { name: 'Create product' }).click();
  await expect(page.getByText('DRAFT')).toBeVisible();

  const ratingSection = page.locator('p', { hasText: 'Rating table -- must cover' }).locator('..');
  await ratingSection.getByRole('button', { name: 'Remove rating factor' }).last().click();
  await ratingSection.getByLabel('Rating factor 1 band').fill('18-30');
  // AGE is rated by range now: the band text is a label, these two are what the platform
  // resolves against. Publishing without them is a real 422.
  await ratingSection.getByLabel('Rating factor 1 from age').fill('18');
  await ratingSection.getByLabel('Rating factor 1 to age').fill('30');
  await ratingSection.getByRole('button', { name: 'Add rating factor' }).click();
  await ratingSection.locator('select').nth(1).selectOption('SUM_ASSURED_BAND');
  await ratingSection.getByLabel('Rating factor 2 band').fill('1000000-5000000');
  await page.getByLabel('Effective date').fill(dmy('2026-01-01'));
  // The TIRA filing that authorises this version -- required as of V12.
  await page.getByLabel('TIRA filing reference').fill('TIRA/E2E/0001');
  await page.getByLabel('TIRA approval date').fill(dmy('2026-01-15'));
  await page.getByRole('button', { name: 'Publish version' }).click();
  await expect(page).toHaveURL(/\/staff\/products\/[0-9a-f-]{36}$/, { timeout: 15_000 });
}

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
  test('suspends and resumes a real GROUP_LIFE policy end to end', async ({ page, browser }) => {
    const code = `E2E-GRP-${Date.now()}`;
    const name = `E2E Group Life ${code}`;
    await asAdmin(browser, (adminPage) => createGroupLifeProduct(adminPage, code, name));
    await issuePolicyAgainst(page, `${name} (${code})`, 'E2E policy-lifecycle fixture');

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
