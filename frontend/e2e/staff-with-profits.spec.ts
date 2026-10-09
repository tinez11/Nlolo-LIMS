import { expect, test } from '@playwright/test';
import { asAdmin } from './admin';
import { dmy, todayIso } from './dates';
import { caseAwaitingManualIssue, selectUnderwritingCase } from './underwriting';

/**
 * A with-profits endowment through the real stack (product step 4): the version published with its
 * bonus terms, a policy issued on it, a declaration proposed by one person and approved by another,
 * attached by the drain (every ten seconds under `local`), and seen on the policy's Bonuses tab.
 *
 * The spec authors its own product rather than reusing the seeded WP-ENDOW-01: one declaration is
 * approved per product and valuation date, so a second run on the same day would be refused there.
 */
test.describe('a with-profits policy', () => {
  test.use({ storageState: 'e2e/.auth/staff-admin.json' });
  // A product published, an underwriting case decided by two people, a manual issue, a declaration
  // by two people and a drain -- the cost of reaching one attached bonus.
  test.setTimeout(300_000);

  test('receives an approved bonus and shows it on the policy', async ({ page, browser }) => {
    const code = `WP-E2E-${Date.now()}`;
    const name = `E2E With Profits ${code}`;
    // The civil date, as the server's "today" is: a UTC date valued the declaration the day before
    // issue between 00:00 and 03:00, and the drain rightly found the policy not yet issued.
    const today = todayIso();

    // ---- The product: an endowment paying the sum assured at maturity, with profits.
    await page.goto('/staff/products/new');
    await page.getByLabel('Product code').fill(code);
    await page.getByLabel('Product name').fill(name);
    await page.getByLabel('Category').selectOption('ENDOWMENT');
    await page.getByLabel('Default currency').fill('TZS');
    await page.getByRole('button', { name: 'Create product' }).click();
    await expect(page.getByText('DRAFT')).toBeVisible();

    const ratingSection = page.locator('p', { hasText: 'Rating table — must cover' }).locator('..');
    await ratingSection.getByLabel('Rating factor 1 band').fill('18-80');
    await ratingSection.getByLabel('Rating factor 1 from age').fill('18');
    await ratingSection.getByLabel('Rating factor 1 to age').fill('80');
    await ratingSection.getByLabel('Rating factor 2 band').fill('1-999999999');
    await page.getByLabel('Effective date').fill(dmy('2026-01-01'));
    await page.getByLabel('TIRA filing reference').fill('TIRA/E2E/WP');
    await page.getByLabel('TIRA approval date').fill(dmy('2026-01-15'));
    await page.getByLabel('Free-look days').fill('15');

    await page.getByRole('button', { name: 'Add a payout' }).click();
    await page.getByLabel('Payout 1 kind').selectOption('MATURITY');
    await page.getByLabel('Payout 1 basis').selectOption('PERCENT_OF_SA');
    await page.getByLabel('Payout 1 amount').fill('100');

    await page.getByLabel('With-profits version').check();
    await page.getByLabel('Bonus method').selectOption('COMPOUND');
    await page.getByLabel('Bonus surrender basis').selectOption('OWN_SCALE');
    await page.getByRole('button', { name: 'Add a bonus surrender row' }).click();
    await page.getByLabel('Bonus surrender row 1 from completed years').fill('0');
    await page.getByLabel('Bonus surrender row 1 value per mille').fill('400');

    await page.getByRole('button', { name: 'Publish version' }).click();
    await page.getByRole('button', { name: 'Publish and make active' }).click();
    await expect(page).toHaveURL(/\/staff\/products\/[0-9a-f-]{36}$/, { timeout: 15_000 });
    const productUrl = new URL(page.url()).pathname;

    // ---- The policy: 1,000,000 for fifteen years, in force on arrival.
    const policyNumber = await asAdmin(browser, async (admin) => {
      const caseId = await caseAwaitingManualIssue(admin, '1000000.00', `${name} (${code})`);
      await admin.goto('/staff/policies/new');
      await selectUnderwritingCase(admin, caseId);
      await expect(admin.getByText('Resolving product version…')).not.toBeVisible();
      await admin.getByLabel('Sum assured').fill('1000000.00');
      await admin.getByLabel('Premium', { exact: true }).fill('50000.00');
      // MIGRATION so the policy is in force on arrival -- see e2e/policies.ts.
      await admin.getByLabel('Why is this being issued by hand?').selectOption('MIGRATION');
      await admin.getByLabel('Reason for manual issue').fill('E2E fixture: with-profits');
      await admin.getByLabel('Commencement date').fill(dmy(today));
      await admin.getByLabel('Policy term (months)').fill('180');
      await admin.getByRole('button', { name: 'Issue policy' }).click();
      await expect(admin).toHaveURL(/\/staff\/policies\/POL-[A-Z0-9]+$/, { timeout: 60_000 });
      return admin.url().split('/').pop() as string;
    });

    // ---- The declaration: the admin proposes, and may not approve their own.
    await page.goto(productUrl);
    const panel = page.locator('form').filter({ hasText: 'Declare a bonus' });
    await panel.getByLabel('Valuation date').fill(dmy(today));
    await panel.getByLabel('Reversionary rate (%)').fill('3');
    await panel.getByLabel('Terminal rate (% of attached bonuses)').fill('50');
    await panel.getByRole('button', { name: 'Propose declaration' }).click();
    const declarations = page.getByRole('list', { name: 'Bonus declarations' });
    await expect(declarations.getByText('3% reversionary · 50% terminal')).toBeVisible({ timeout: 15_000 });
    await expect(declarations.getByRole('button', { name: 'Approve declaration' })).toBeDisabled();
    await expect(
      declarations.getByText('A bonus declaration must be approved by someone other than the person who proposed it'),
    ).toBeVisible();

    // A finance officer is the second person.
    const financeContext = await browser.newContext({ storageState: 'e2e/.auth/staff-finance.json' });
    try {
      const finance = await financeContext.newPage();
      await finance.goto(productUrl);
      const list = finance.getByRole('list', { name: 'Bonus declarations' });
      await list.getByRole('button', { name: 'Approve declaration' }).click();
      await expect(list.getByText(/approved by/)).toBeVisible({ timeout: 15_000 });
    } finally {
      await financeContext.close();
    }

    // ---- Attached by the drain -- a poll, not an assumption about timing.
    await page.goto(`/staff/policies/${policyNumber}`);
    await expect(async () => {
      await page.reload();
      await page.getByRole('tab', { name: 'Bonuses' }).click();
      await expect(page.getByRole('list', { name: 'Bonus history' }).getByText('Reversionary bonus')).toBeVisible();
    }).toPass({ timeout: 90_000 });
    // 1,000,000 x 3%, attached once, and the declaration says why.
    await expect(page.getByRole('list', { name: 'Bonus history' })).toContainText('TZS 30,000.00');
    await expect(page.getByRole('list', { name: 'Declaration outcomes' })).toContainText('Attached');
  });
});
