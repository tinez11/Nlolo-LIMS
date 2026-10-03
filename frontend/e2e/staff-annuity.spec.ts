import { expect, test, type Page } from '@playwright/test';
import { dmy, todayIso } from './dates';
import { asSeniorOnSameCase } from './underwriting';

/**
 * An immediate annuity through the real stack (product step 5): a version published with its form
 * and rate grid, a purchase opened on a case with a live quote, accepted on proof of age by a second
 * person and issued automatically, its single premium collected through the mock rail, the income
 * locked from the rate table, and the first instalment paid net of a withholding rule finance
 * proposed and a second person approved.
 *
 * The spec authors its own product: the seeded ANN-LIFE-01 sells from age 55, and the seeded Amina
 * Owner is younger. This one sells from 18 at one flat rate, which is all the spec needs.
 *
 * Income is paid IN ADVANCE here so the first instalment falls due on the collection day, and the
 * due drain (every five seconds under `local`) brings it DUE inside the test.
 */

const RATE_PER_MILLE = 72;
const MONTHLY_FACTOR = 0.98;

/** TZS cents as the console prints money: "TZS 294,000.00". */
function tzs(cents: number): string {
  return `TZS ${(cents / 100).toLocaleString('en-US', { minimumFractionDigits: 2, maximumFractionDigits: 2 })}`;
}

/** An approved 10% rule on annuity income, in force today: proposed by finance, approved by admin. */
async function ensureWithholdingRule(page: Page, financePage: Page, today: string): Promise<void> {
  await financePage.goto('/staff/withholding-rules');
  const list = financePage.getByRole('list', { name: 'Withholding rules' });
  const approved = list.getByRole('listitem').filter({ hasText: '10% from Annuity income' }).filter({ hasText: /approved by/ });
  // An approved rule cannot yet be ended, and a second overlapping one is refused at approval -- so
  // a rerun reuses the first run's rule rather than failing on its own leftovers.
  // Loaded once either the list or its empty state is on screen -- not networkidle, which the
  // sidebar's polled counts never reach.
  await expect(list.or(financePage.getByText('No withholding rule', { exact: true }))).toBeVisible({ timeout: 30_000 });
  if ((await approved.count()) > 0) return;

  const form = financePage.locator('form').filter({ hasText: 'Propose a rule' });
  await form.getByLabel('Rate (%)').fill('10');
  await form.getByLabel('Legal reference').fill('E2E: Income Tax Act withholding on annuities');
  await form.getByLabel('Effective from').fill(dmy(today));
  await form.getByRole('button', { name: 'Propose rule' }).click();
  const proposed = financePage.getByRole('list', { name: 'Withholding rules' })
    .getByRole('listitem').filter({ hasText: '10% from Annuity income' }).filter({ has: financePage.getByRole('button', { name: 'Approve rule' }) });
  await expect(proposed.first()).toBeVisible({ timeout: 15_000 });
  // The proposer may not approve their own.
  await expect(proposed.first().getByRole('button', { name: 'Approve rule' })).toBeDisabled();

  await page.goto('/staff/withholding-rules');
  const toApprove = page.getByRole('list', { name: 'Withholding rules' })
    .getByRole('listitem').filter({ hasText: '10% from Annuity income' }).filter({ has: page.getByRole('button', { name: 'Approve rule' }) });
  await toApprove.first().getByRole('button', { name: 'Approve rule' }).click();
  await expect(
    page.getByRole('list', { name: 'Withholding rules' }).getByRole('listitem')
      .filter({ hasText: '10% from Annuity income' }).filter({ hasText: /approved by/ }).first(),
  ).toBeVisible({ timeout: 15_000 });
}

test.describe('an immediate annuity', () => {
  test.use({ storageState: 'e2e/.auth/staff-admin.json' });
  // A product published, a withholding rule by two people, a case decided by a second person, a
  // collection through the rail, a lock, a drain and a payout by two people.
  test.setTimeout(300_000);

  test('is bought on a case, locked at payment, and paid net of an approved withholding rule', async ({ page, browser }) => {
    const code = `ANN-E2E-${Date.now()}`;
    const name = `E2E Annuity ${code}`;
    const today = todayIso();
    // A purchase price unique to this run, so its policy is the one row with it among Amina's. A
    // multiple of 10,000 keeps every figure whole cents: the instalment is price x 72/1000 x 0.98 / 12.
    const k = Date.now() % 1000;
    const price = 50_000_000 + k * 10_000;
    const instalmentCents = Math.round((price * RATE_PER_MILLE * MONTHLY_FACTOR * 100) / 1000 / 12);
    const withheldCents = instalmentCents / 10;
    const netCents = instalmentCents - withheldCents;

    // ---- The product: one life-only form, ages 18-85 at one rate, paid monthly or annually.
    await page.goto('/staff/products/new');
    await page.getByLabel('Product code').fill(code);
    await page.getByLabel('Product name').fill(name);
    await page.getByLabel('Category').selectOption('ANNUITY');
    await page.getByLabel('Default currency').fill('TZS');
    await page.getByRole('button', { name: 'Create product' }).click();
    await expect(page.getByText('DRAFT')).toBeVisible();

    const ratingSection = page.locator('p', { hasText: 'Rating table -- must cover' }).locator('..');
    await ratingSection.getByLabel('Rating factor 1 band').fill('18-85');
    await ratingSection.getByLabel('Rating factor 1 from age').fill('18');
    await ratingSection.getByLabel('Rating factor 1 to age').fill('85');
    await ratingSection.getByLabel('Rating factor 2 band').fill('1-999999999');
    await page.getByLabel('Effective date').fill(dmy('2026-01-01'));
    await page.getByLabel('TIRA filing reference').fill('TIRA/E2E/ANN');
    await page.getByLabel('TIRA approval date').fill(dmy('2026-01-15'));
    await page.getByLabel('Free-look days').fill('15');
    await page.getByLabel('Minimum entry age').fill('18');
    await page.getByLabel('Maximum entry age').fill('85');

    await page.getByLabel('Income paid').selectOption('ADVANCE');
    await page.getByLabel('Rate basis reference').fill('E2E-BASIS-NOT-ACTUARIAL');
    await page.getByLabel('Rate basis date').fill(dmy('2026-01-01'));
    await page.getByLabel('Monthly factor').fill(String(MONTHLY_FACTOR));
    await page.getByRole('button', { name: 'Add an annuity form' }).click();
    await page.getByLabel('Form code').fill('LIFE');
    const grid = Array.from({ length: 85 - 18 + 1 }, (_, i) => `${18 + i}, ${RATE_PER_MILLE}`).join('\n');
    await page.getByLabel(/^Rates — one line each/).fill(grid);

    await page.getByRole('button', { name: 'Publish version' }).click();
    await page.getByRole('button', { name: 'Publish and make active' }).click();
    await expect(page).toHaveURL(/\/staff\/products\/[0-9a-f-]{36}$/, { timeout: 15_000 });

    // ---- Withholding: proposed by finance, approved by someone else.
    const financeContext = await browser.newContext({ storageState: 'e2e/.auth/staff-finance.json' });
    try {
      const finance = await financeContext.newPage();
      await ensureWithholdingRule(page, finance, today);

      // ---- The purchase: Amina, the LIFE form, monthly, quoted live before anything is stored.
      await page.goto('/staff/underwriting/new');
      await page.getByRole('button', { name: 'Search for the applicant by name' }).click();
      await page.getByPlaceholder('Type a name to search').fill('Amina');
      await page.getByRole('option', { name: 'Amina Owner' }).click();
      await page.getByLabel('Product').selectOption({ label: `${name} (${code})` });
      await expect(page.getByText('Resolving product version…')).not.toBeVisible();
      await page.getByLabel('Purchase price').fill(`${price}.00`);
      await page.getByLabel('Annuity form').selectOption('LIFE');
      await page.getByLabel('Income paid').selectOption('MONTHLY');
      await expect(page.getByText(`Quote today: ${tzs(instalmentCents)}`)).toBeVisible({ timeout: 15_000 });
      // No term and no premium frequency on an annuity: one single premium, paid for life.
      await expect(page.getByLabel('Term (months)')).toHaveCount(0);
      await page.getByRole('button', { name: 'Open case' }).click();
      await expect(page).toHaveURL(/\/staff\/underwriting\/[0-9a-f-]{36}$/, { timeout: 15_000 });
      await expect(page.getByRole('heading', { name: 'Annuity purchase' })).toBeVisible();

      // ---- Accepted on proof of age, by someone other than whoever opened it. Auto-issues.
      await asSeniorOnSameCase(page, async (senior) => {
        const decision = senior.getByLabel('Decision');
        await expect(decision.locator('option')).toHaveText(['Accept', 'Decline']);
        await senior.getByLabel('Reason').fill('Passport seen: date of birth confirmed');
        await senior.getByRole('button', { name: 'Record decision' }).click();
        await expect(senior.getByText('An annuity is accepted only once proof of age is confirmed')).toBeVisible();
        await senior.getByLabel(/^Age evidence confirmed/).check();
        await senior.getByRole('button', { name: 'Record decision' }).click();
        await senior.getByRole('button', { name: 'Record the acceptance' }).click();
        await expect(senior.getByRole('heading', { name: 'Decision', exact: true })).toBeVisible({ timeout: 15_000 });
      });

      // The policy arrives through an AFTER_COMMIT listener; found by its unique purchase price.
      const applicantHref = await page.locator('a[href^="/staff/parties/"]').first().getAttribute('href');
      const applicantId = (applicantHref as string).split('/').pop() as string;
      const priceText = tzs(price * 100);
      await expect(async () => {
        await page.goto(`/staff/policies?policyholderPartyId=${applicantId}`);
        await expect(page.getByRole('row').filter({ hasText: priceText })).toHaveCount(1);
      }).toPass({ timeout: 30_000 });
      await page.getByRole('row').filter({ hasText: priceText }).getByRole('button').first().click();
      await page.getByRole('link', { name: /full detail/i }).click();
      await expect(page).toHaveURL(/\/staff\/policies\/POL-[A-Z0-9]+$/, { timeout: 15_000 });
      const policyUrl = new URL(page.url()).pathname;

      // Awaiting the premium: the income is not yet locked.
      await page.getByRole('tab', { name: 'Annuity' }).click();
      await expect(page.getByText('Locked when the single premium is collected', { exact: false })).toBeVisible();

      // ---- The single premium, collected through the mock rail.
      await page.getByRole('tab', { name: 'Billing' }).click();
      await page.getByRole('button', { name: 'Request payment' }).first().click({ timeout: 20_000 });
      const pay = page.locator('form').filter({ has: page.getByLabel('Payer reference') });
      await pay.getByLabel('Payer reference').fill('+255700000777');
      await pay.getByRole('button', { name: 'Request payment' }).click();
      // 60s: the request answers only after the whole AFTER_COMMIT chain -- payment, the rail's
      // confirmation, billing, and the annuity's lock -- has run on its thread.
      await expect(page.getByLabel('Payer reference')).not.toBeVisible({ timeout: 60_000 });

      // ---- Locked from the rate table: 72 per 1,000 a year, x 0.98, paid monthly.
      await expect(async () => {
        await page.goto(policyUrl);
        await page.getByRole('tab', { name: 'Annuity' }).click();
        await expect(page.getByText('In payment', { exact: false }).first()).toBeVisible();
      }).toPass({ timeout: 90_000 });
      await expect(page.getByText(`${tzs(instalmentCents)} a month`)).toBeVisible();
      await expect(page.getByText(/72 per 1,000 at age \d+/)).toBeVisible();

      // ---- The first instalment, due today: reviewed by finance, approved by admin.
      await expect(async () => {
        await finance.goto(policyUrl);
        await finance.getByRole('tab', { name: 'Payouts' }).click();
        await expect(finance.getByText('Annuity income').first()).toBeVisible();
        await expect(finance.getByText('Due', { exact: true }).first()).toBeVisible();
      }).toPass({ timeout: 60_000 });
      await finance.getByRole('link', { name: 'Open payout' }).first().click();
      await expect(finance).toHaveURL(/\/staff\/payouts\/[0-9a-f-]{36}$/);
      const payoutUrl = finance.url();
      await finance.getByLabel('Payee reference').fill('+255700000777');
      await finance.getByLabel('How was the life assured confirmed alive?').selectOption('IN_PERSON');
      await finance.getByRole('button', { name: 'Review', exact: true }).click();
      await expect(finance.getByRole('button', { name: 'Approve', exact: true })).toBeDisabled({ timeout: 15_000 });

      await page.goto(payoutUrl);
      await page.getByRole('button', { name: 'Approve', exact: true }).click();
      await page.getByRole('button', { name: 'Approve and pay' }).click();
      await expect(page.getByText('The payment has been requested from the provider.')).toBeVisible({ timeout: 20_000 });
      // Gross, the tax withheld by the approved rule, and the net the rail is asked to pay.
      await page.reload();
      await expect(page.getByText('Tax withheld')).toBeVisible({ timeout: 15_000 });
      await expect(page.getByText(tzs(withheldCents), { exact: true })).toBeVisible();
      await expect(page.getByText(tzs(netCents), { exact: true })).toBeVisible();
    } finally {
      await financeContext.close();
    }
  });
});
