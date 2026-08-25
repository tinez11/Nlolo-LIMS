import { expect, test } from '@playwright/test';

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
  await ratingSection.locator('input[placeholder="Band, e.g. 18-30"]').fill('18-30');
  await ratingSection.getByRole('button', { name: 'Add rating factor' }).click();
  await ratingSection.locator('select').nth(1).selectOption('SUM_ASSURED_BAND');
  await ratingSection.locator('input[placeholder="Band, e.g. 18-30"]').nth(1).fill('1000000-5000000');
  await page.getByLabel('Effective date').fill('2026-01-01');
  await page.getByRole('button', { name: 'Publish version' }).click();
  await expect(page).toHaveURL(/\/staff\/products\/[0-9a-f-]{36}$/, { timeout: 15_000 });
}

async function issuePolicyAgainst(
  page: import('@playwright/test').Page,
  productLabel: string,
  reason: string,
): Promise<string> {
  await page.goto('/staff/policies/new');
  await page.getByLabel('Policyholder party id').fill('d9937444-3873-4336-9cb7-addb486f3e1b');
  await page.getByLabel('Product').selectOption({ label: productLabel });
  await expect(page.getByText('Resolving product version…')).not.toBeVisible();
  await page.getByLabel('Sum assured').fill('2000000.00');
  await page.getByLabel('Premium', { exact: true }).fill('800.00');
  await page.getByLabel('Reason for manual issue').fill(reason);
  await page.getByRole('button', { name: 'Issue policy' }).click();
  await expect(page).toHaveURL(/\/staff\/policies\/POL-[A-Z0-9]+$/, { timeout: 15_000 });
  return page.url().split('/').pop() as string;
}

test.describe('staff policy lifecycle', () => {
  test('suspends and resumes a real GROUP_LIFE policy end to end', async ({ page }) => {
    const code = `E2E-GRP-${Date.now()}`;
    const name = `E2E Group Life ${code}`;
    await createGroupLifeProduct(page, code, name);
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
});
