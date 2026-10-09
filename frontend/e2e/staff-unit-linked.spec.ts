import { expect, test, type Browser, type Page } from '@playwright/test';
import { asAdmin } from './admin';
import { dmy, todayIso } from './dates';
import { asSeniorOnSameCase, assess } from './underwriting';

/**
 * Unit-linked (product step 6) through the real stack: finance adds two funds to the register and proposes
 * yesterday's prices; finance cannot approve its own, and an admin does. An admin publishes a unit-linked
 * product offering both funds; Amina is sold it with a 60/40 split; a second person accepts the case; the
 * first premium is collected -- and waits, because forward pricing buys units only at the first price approved
 * after a premium arrives, never one already on screen. A surrender is requested with no figure quoted, and
 * once a second person approves it every unit waits to be sold at the next price.
 *
 * The funds cut off one minute past midnight, so anything received today binds to tomorrow's price, which
 * no one may approve until tomorrow: the spec proves the wait, not a price nobody could yet know.
 */

const formatTzs = (n: number) => `TZS ${n.toLocaleString('en-US', { minimumFractionDigits: 2 })}`;

async function asFinance<T>(browser: Browser, work: (page: Page) => Promise<T>): Promise<T> {
  const context = await browser.newContext({ storageState: 'e2e/.auth/staff-finance.json' });
  try {
    return await work(await context.newPage());
  } finally {
    await context.close();
  }
}

function yesterdayIso(): string {
  const d = new Date(`${todayIso()}T12:00:00Z`);
  d.setUTCDate(d.getUTCDate() - 1);
  return d.toISOString().slice(0, 10);
}

test.describe('unit-linked', () => {
  // Two funds priced by two people, a product published, a case decided by a second person, a collection
  // through the rail, and a surrender through two identities.
  test.setTimeout(420_000);

  test('funds priced by two people, a policy sold on a split, its premium waiting for a forward price, and a surrender quoted at nothing', async ({ page, browser }) => {
    const run = String(Date.now() % 1_000_000).padStart(6, '0');
    const equity = `EQ${run}`;
    const cash = `MM${run}`;
    const code = `UL-E2E-${run}`;
    const name = `E2E Wekeza ${code}`;
    const sumAssured = 6_000_000 + (Date.now() % 1000) * 100;
    const yesterday = yesterdayIso();

    // ---- Finance adds the funds and proposes yesterday's prices; it may not approve its own.
    await asFinance(browser, async (finance) => {
      await finance.goto('/staff/funds');
      for (const [fund, assetClass, charge] of [[equity, 'EQUITY', '1.5'], [cash, 'MONEY_MARKET', '0.5']] as const) {
        const add = finance.getByRole('form', { name: 'Add a fund' });
        await add.getByLabel('Code').fill(fund);
        await add.getByLabel('Name').fill(`E2E ${fund}`);
        await add.getByLabel('Asset class').selectOption(assetClass);
        await add.getByLabel('Management charge (% a year)').fill(charge);
        await add.getByLabel('Daily cut-off (EAT)').fill('00:01');
        await add.getByRole('button', { name: 'Add fund' }).click();
        const card = finance.getByRole('listitem', { name: `Fund ${fund}` });
        await expect(card).toBeVisible({ timeout: 15_000 });

        await card.getByRole('button', { name: 'Prices' }).click();
        const propose = card.getByRole('form', { name: `Propose a price for ${fund}` });
        await propose.getByLabel('Valuation date').fill(dmy(yesterday));
        await propose.getByLabel('Price (TZS per unit)').fill('1.000000');
        await propose.getByRole('button', { name: 'Propose price' }).click();
        const prices = card.getByRole('table', { name: `Prices of ${fund}` });
        await expect(prices).toContainText('Proposed', { timeout: 15_000 });
        // The proposer is refused by the platform, and the screen says so before they try.
        await expect(prices.getByRole('button', { name: 'Approve' })).toBeDisabled();
        await expect(card.getByText('A fund price must be approved by someone other than the person who proposed it')).toBeVisible();
      }
    });

    // ---- A second person -- the admin -- approves both, after their cut-off.
    await asAdmin(browser, async (admin) => {
      await admin.goto('/staff/funds');
      for (const fund of [equity, cash]) {
        const card = admin.getByRole('listitem', { name: `Fund ${fund}` });
        await card.getByRole('button', { name: 'Prices' }).click();
        const prices = card.getByRole('table', { name: `Prices of ${fund}` });
        await prices.getByRole('button', { name: 'Approve' }).click();
        await expect(prices).toContainText('Approved', { timeout: 15_000 });
      }
    });

    // ---- The product, authored by an admin: the two funds, 90% allocated, a unisex mortality table.
    await asAdmin(browser, async (admin) => {
      await admin.goto('/staff/products/new');
      await admin.getByLabel('Product code').fill(code);
      await admin.getByLabel('Product name').fill(name);
      await admin.getByLabel('Category').selectOption('UNIT_LINKED');
      await admin.getByLabel('Default currency').fill('TZS');
      await admin.getByRole('button', { name: 'Create product' }).click();
      await expect(admin.getByText('DRAFT')).toBeVisible();

      // No rating table: the cost of insurance comes from the mortality table.
      await expect(admin.getByText(/Rating table — must cover/)).not.toBeVisible();
      await admin.getByLabel('Effective date').fill(dmy('2026-01-01'));
      await admin.getByLabel('TIRA filing reference').fill('TIRA/E2E/UL');
      await admin.getByLabel('TIRA approval date').fill(dmy('2026-01-15'));
      await admin.getByLabel('Free-look days').fill('15');

      await admin.getByLabel(`${equity} · E2E ${equity}`).check();
      await admin.getByLabel(`${cash} · E2E ${cash}`).check();
      await admin.getByLabel('Allocation bands').fill('1,2,90\n3,,98');
      await admin.getByLabel('Monthly policy fee').fill('2000');
      await admin.getByLabel('Mortality basis').selectOption('UNISEX');
      await admin.getByLabel('Mortality table').fill('0,39,,1.2\n40,59,,4.5\n60,,,25');
      await admin.getByLabel('Minimum years before surrender').fill('0');
      await admin.getByLabel('Warn when the units cover fewer months of charges than').fill('3');
      await admin.getByLabel('Monthly minimum').fill('50000');
      await admin.getByLabel('Sum assured, least multiple of the annual premium').fill('5');
      await admin.getByLabel('Sum assured, greatest multiple of the annual premium').fill('20');

      await admin.getByRole('button', { name: 'Publish version' }).click();
      await admin.getByRole('button', { name: 'Publish and make active' }).click();
      await expect(admin).toHaveURL(/\/staff\/products\/[0-9a-f-]{36}$/, { timeout: 15_000 });
    });

    // ---- The sale: 100,000 a month, 60% equity and 40% cash, for a sum assured inside 5x-20x the annual premium.
    await page.goto('/staff/underwriting/new');
    await page.getByRole('button', { name: 'Search for the applicant by name' }).click();
    await page.getByPlaceholder('Type a name to search').fill('Amina');
    await page.getByRole('option', { name: 'Amina Owner' }).click();
    await page.getByLabel('Product').selectOption({ label: `${name} (${code})` });
    await expect(page.getByText('Resolving product version…')).not.toBeVisible();
    await page.getByLabel(/^Sum assured/).fill(sumAssured.toFixed(2));
    await page.getByLabel('Premium frequency').selectOption('MONTHLY');
    await page.getByLabel('Premium per payment (TZS)').fill('100000');
    await page.getByLabel(`${equity} (%)`).fill('60');
    await page.getByLabel(`${cash} (%)`).fill('30');
    await expect(page.getByText('Split totals 90%; it must total 100%.')).toBeVisible();
    await page.getByLabel(`${cash} (%)`).fill('40');
    await expect(page.getByText('Split totals 100%.')).toBeVisible();
    await expect(page.getByText(/The sum assured may be from TZS 6,000,000.00 to TZS 24,000,000.00/)).toBeVisible();
    await page.getByRole('button', { name: 'Open case' }).click();
    await expect(page).toHaveURL(/\/staff\/underwriting\/[0-9a-f-]{36}$/, { timeout: 15_000 });

    // ---- Assessed, then accepted by someone else. Acceptance issues the policy, unrated: the choice is the price.
    await assess(page, 'Healthy applicant, no adverse findings', '10');
    await asSeniorOnSameCase(page, async (senior) => {
      await senior.getByLabel('Decision').selectOption({ label: 'Accept' });
      await senior.getByLabel('Reason').fill('Accepted at the chosen premium and cover');
      await senior.getByRole('button', { name: 'Record decision' }).click();
      await senior.getByRole('button', { name: 'Record the acceptance' }).click();
      await expect(senior.getByRole('heading', { name: 'Decision', exact: true })).toBeVisible({ timeout: 60_000 });
    });

    const applicantHref = await page.locator('a[href^="/staff/parties/"]').first().getAttribute('href');
    const applicantId = (applicantHref as string).split('/').pop() as string;
    await expect(async () => {
      await page.goto(`/staff/policies?policyholderPartyId=${applicantId}`);
      await expect(page.getByRole('row').filter({ hasText: formatTzs(sumAssured) })).toHaveCount(1);
    }).toPass({ timeout: 30_000 });
    await page.getByRole('row').filter({ hasText: formatTzs(sumAssured) }).getByRole('button').first().click();
    await page.getByRole('link', { name: /full detail/i }).click();
    await expect(page).toHaveURL(/\/staff\/policies\/POL-[A-Z0-9]+$/, { timeout: 15_000 });
    const policyUrl = new URL(page.url()).pathname;

    await page.getByRole('tab', { name: 'Units' }).click();
    await expect(page.getByText('No units yet')).toBeVisible({ timeout: 15_000 });

    // ---- The first premium, collected through the mock rail. It waits for tomorrow's price: forward pricing.
    await asAdmin(browser, async (admin) => {
      await admin.goto(policyUrl);
      await admin.getByRole('tab', { name: 'Billing' }).click();
      await admin.getByRole('button', { name: 'Request payment' }).first().click({ timeout: 20_000 });
      const pay = admin.locator('form').filter({ has: admin.getByLabel('Payer reference') });
      await pay.getByLabel('Payer reference').fill('+255700000781');
      await pay.getByRole('button', { name: 'Request payment' }).click();
      await expect(admin.getByLabel('Payer reference')).not.toBeVisible({ timeout: 60_000 });
    });

    await expect(async () => {
      await page.goto(policyUrl);
      await page.getByRole('tab', { name: 'Units' }).click();
      const waiting = page.getByRole('region', { name: 'Waiting for a price' });
      await expect(waiting).toContainText(equity, { timeout: 10_000 });
      await expect(waiting).toContainText(cash);
    }).toPass({ timeout: 90_000, intervals: [10_000] });
    // Nothing was bought at yesterday's approved price, though it is on screen.
    await expect(page.getByText('No units yet')).toBeVisible();

    // The register says the same: an order waits on each fund for a price no one may approve yet.
    await asFinance(browser, async (finance) => {
      await finance.goto('/staff/funds');
      const card = finance.getByRole('listitem', { name: `Fund ${equity}` });
      await card.getByRole('button', { name: 'Prices' }).click();
      await expect(card.getByLabel(`Waiting orders for ${equity}`)).toContainText('1 order for');
    });

    // ---- A surrender: requested with no figure, approved by a second person, and the units wait to be sold.
    const surrender = page.getByRole('region', { name: 'Surrender' });
    await expect(surrender.getByText(/No figure is quoted/)).toBeVisible();
    await surrender.getByLabel('Payee (mobile money or bank destination)').fill('+255700000781');
    await surrender.getByRole('button', { name: 'Request surrender' }).click();
    await surrender.getByRole('button', { name: 'Record surrender request' }).click();
    await expect(surrender.getByText('Requested', { exact: true })).toBeVisible({ timeout: 15_000 });

    await asAdmin(browser, async (admin) => {
      await admin.goto(policyUrl);
      await admin.getByRole('tab', { name: 'Units' }).click();
      const s = admin.getByRole('region', { name: 'Surrender' });
      await s.getByRole('button', { name: 'Approve surrender' }).click();
      await s.getByRole('button', { name: 'Approve and sell the units' }).click();
      await expect(admin.getByRole('status').filter({ hasText: 'being sold for a surrender' })).toBeVisible({ timeout: 30_000 });
    });
  });
});
