import { expect, test } from '@playwright/test';
import { dmy, todayIso } from './dates';
import { asSeniorOnSameCase } from './underwriting';

/**
 * A pension through the real stack (product step 5 D2): a deferred annuity published with its
 * savings account and vesting terms, sold on a case by retirement age and accepted on proof of age,
 * its first contribution collected through the mock rail, then instructed by staff to vest TODAY with
 * a 25% lump sum -- and the vesting sweep (every minute under `local`) closes the account, schedules
 * the lump sum and starts the income.
 *
 * The spec authors its own product: its vesting window is 18-85, so today is inside it for the seeded
 * Amina Owner (born 1990), and it is unlocked. Its grid covers the VESTING window -- one flat rate.
 */

const RATE_PER_MILLE = 72;

test.describe('a pension (deferred annuity)', () => {
  test.use({ storageState: 'e2e/.auth/staff-admin.json' });
  // A product published, a case decided by a second person, a collection through the rail, an
  // instruction, and a sweep that runs once a minute.
  test.setTimeout(300_000);

  test('is sold by retirement age, funded, instructed to vest today with a lump sum, and pays', async ({ page }) => {
    const code = `PEN-E2E-${Date.now()}`;
    const name = `E2E Pension ${code}`;
    const today = todayIso();
    // A contribution unique to this run, so its policy is the one row with it among Amina's.
    const contribution = 200_000 + (Date.now() % 1000) * 100;

    // ---- The product: a 4% account that saves from 18, vesting 18-85 into one life-only form.
    await page.goto('/staff/products/new');
    await page.getByLabel('Product code').fill(code);
    await page.getByLabel('Product name').fill(name);
    await page.getByLabel('Category').selectOption('ANNUITY');
    await page.getByLabel('Default currency').fill('TZS');
    await page.getByRole('button', { name: 'Create product' }).click();
    await expect(page.getByText('DRAFT')).toBeVisible();

    const ratingSection = page.locator('p', { hasText: 'Rating table -- must cover' }).locator('..');
    await ratingSection.getByLabel('Rating factor 1 band').fill('18-84');
    await ratingSection.getByLabel('Rating factor 1 from age').fill('18');
    await ratingSection.getByLabel('Rating factor 1 to age').fill('84');
    await ratingSection.getByLabel('Rating factor 2 band').fill('1-999999999');
    await page.getByLabel('Effective date').fill(dmy('2026-01-01'));
    await page.getByLabel('TIRA filing reference').fill('TIRA/E2E/PEN');
    await page.getByLabel('TIRA approval date').fill(dmy('2026-01-15'));
    await page.getByLabel('Free-look days').fill('15');
    await page.getByLabel('Minimum entry age').fill('18');
    await page.getByLabel('Maximum entry age').fill('84');

    // Deferred: the account section appears, and the grid covers the vesting window instead.
    await page.getByLabel('Annuity kind').selectOption('DEFERRED');
    await page.getByLabel('Guaranteed interest rate (% a year)').fill('4');
    await page.getByLabel('Minimum balance after a withdrawal').fill('0');
    await page.getByRole('button', { name: 'Add a charge row' }).click();
    await page.getByLabel('Charge row 1 from policy year').fill('1');
    await page.getByLabel('Charge row 1 allocation charge on premiums').fill('0');
    await page.getByLabel('Charge row 1 monthly policy fee').fill('0');

    await page.getByLabel('Income paid').selectOption('ARREARS');
    await page.getByLabel('Rate basis reference').fill('E2E-BASIS-NOT-ACTUARIAL');
    await page.getByLabel('Rate basis date').fill(dmy('2026-01-01'));
    await page.getByLabel('Monthly factor').fill('0.98');
    await page.getByRole('button', { name: 'Add an annuity form' }).click();
    await page.getByLabel('Form code').fill('LIFE');
    const grid = Array.from({ length: 85 - 18 + 1 }, (_, i) => `${18 + i}, ${RATE_PER_MILLE}`).join('\n');
    await page.getByLabel(/^Rates — one line each/).fill(grid);

    await page.getByLabel('Minimum vesting age').fill('18');
    await page.getByLabel('Maximum vesting age').fill('85');
    await page.getByLabel('Lump-sum cap (%)').fill('25');
    await page.getByLabel('Default form').selectOption('LIFE');
    await page.getByLabel('Default frequency').selectOption('MONTHLY');
    await page.getByLabel('Locked before vesting').selectOption('NO');

    await page.getByRole('button', { name: 'Publish version' }).click();
    await page.getByRole('button', { name: 'Publish and make active' }).click();
    await expect(page).toHaveURL(/\/staff\/products\/[0-9a-f-]{36}$/, { timeout: 15_000 });

    // ---- The sale: Amina, retiring at 60, contributing monthly.
    await page.goto('/staff/underwriting/new');
    await page.getByRole('button', { name: 'Search for the applicant by name' }).click();
    await page.getByPlaceholder('Type a name to search').fill('Amina');
    await page.getByRole('option', { name: 'Amina Owner' }).click();
    await page.getByLabel('Product').selectOption({ label: `${name} (${code})` });
    await expect(page.getByText('Resolving product version…')).not.toBeVisible();
    await page.getByLabel('Contribution per payment').fill(`${contribution}.00`);
    await page.getByLabel('Retirement age').fill('60');
    // Amina was born 12 April 1990.
    await expect(page.getByText('Vests on Apr 12, 2050')).toBeVisible({ timeout: 15_000 });
    await page.getByLabel('Contribution frequency').selectOption('MONTHLY');
    await page.getByRole('button', { name: 'Open case' }).click();
    await expect(page).toHaveURL(/\/staff\/underwriting\/[0-9a-f-]{36}$/, { timeout: 15_000 });
    await expect(page.getByRole('heading', { name: 'Pension' })).toBeVisible();

    // ---- Accepted on proof of age, by someone other than whoever opened it. Auto-issues.
    await asSeniorOnSameCase(page, async (senior) => {
      await expect(senior.getByLabel('Decision').locator('option')).toHaveText(['Accept', 'Decline']);
      await senior.getByLabel('Reason').fill('Passport seen: date of birth confirmed');
      await senior.getByLabel(/^Age evidence confirmed/).check();
      await senior.getByRole('button', { name: 'Record decision' }).click();
      await senior.getByRole('button', { name: 'Record the acceptance' }).click();
      // 60s: the decision answers only after its AFTER_COMMIT chain -- issue, billing's schedule, the
      // account opening and the pension's contract and vesting -- has run on its thread.
      await expect(senior.getByRole('heading', { name: 'Decision', exact: true })).toBeVisible({ timeout: 60_000 });
    });

    const applicantHref = await page.locator('a[href^="/staff/parties/"]').first().getAttribute('href');
    const applicantId = (applicantHref as string).split('/').pop() as string;
    const contributionText = `TZS ${contribution.toLocaleString('en-US', { minimumFractionDigits: 2 })}`;
    await expect(async () => {
      await page.goto(`/staff/policies?policyholderPartyId=${applicantId}`);
      await expect(page.getByRole('row').filter({ hasText: contributionText })).toHaveCount(1);
    }).toPass({ timeout: 30_000 });
    await page.getByRole('row').filter({ hasText: contributionText }).getByRole('button').first().click();
    await page.getByRole('link', { name: /full detail/i }).click();
    await expect(page).toHaveURL(/\/staff\/policies\/POL-[A-Z0-9]+$/, { timeout: 15_000 });
    const policyUrl = new URL(page.url()).pathname;

    // Saving: the Pension panel shows when it vests.
    await page.getByRole('tab', { name: 'Annuity' }).click();
    await expect(page.getByText('Vests on Apr 12, 2050')).toBeVisible({ timeout: 15_000 });

    // ---- The first contribution, collected through the mock rail.
    await page.getByRole('tab', { name: 'Billing' }).click();
    await page.getByRole('button', { name: 'Request payment' }).first().click({ timeout: 20_000 });
    const pay = page.locator('form').filter({ has: page.getByLabel('Payer reference') });
    await pay.getByLabel('Payer reference').fill('+255700000778');
    await pay.getByRole('button', { name: 'Request payment' }).click();
    await expect(page.getByLabel('Payer reference')).not.toBeVisible({ timeout: 60_000 });

    // ---- Instructed to vest today, with a quarter of the balance as a lump sum.
    await page.goto(policyUrl);
    await page.getByRole('tab', { name: 'Annuity' }).click();
    await page.getByRole('button', { name: 'Record vesting instruction' }).click({ timeout: 15_000 });
    const instruction = page.getByRole('form', { name: 'Vesting instruction' });
    // Cleared first: the field is pre-filled with the target, and once focused the DatePicker selects
    // only its day section -- a fill straight over it replaces "12" alone and is rightly discarded.
    await instruction.getByLabel('Vesting date').fill('');
    await instruction.getByLabel('Vesting date').fill(dmy(today));
    await expect(instruction.getByLabel('Vesting date')).toHaveValue(dmy(today));
    await instruction.getByLabel('Form', { exact: true }).selectOption('LIFE');
    await instruction.getByLabel('Payment frequency').selectOption('MONTHLY');
    await instruction.getByLabel('Lump sum (%)').fill('25');
    await instruction.getByRole('button', { name: 'Record instruction' }).click();
    await expect(page.getByText(/LIFE · monthly · lump sum 25%/)).toBeVisible({ timeout: 15_000 });

    // ---- The sweep vests it: the income starts, priced at the day's rate.
    await expect(async () => {
      await page.goto(policyUrl);
      await page.getByRole('tab', { name: 'Annuity' }).click();
      await expect(page.getByText('In payment', { exact: false }).first()).toBeVisible();
    }).toPass({ timeout: 150_000, intervals: [10_000] });
    await expect(page.getByText(/72 per 1,000 at age \d+/)).toBeVisible();

    // ---- The lump sum is its own payout.
    await page.getByRole('tab', { name: 'Payouts' }).click();
    await expect(page.getByText('Pension lump sum').first()).toBeVisible({ timeout: 15_000 });
  });
});
