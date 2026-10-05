import { expect, test, type Browser, type Page } from '@playwright/test';
import { asAdmin } from './admin';
import { dmy, todayIso } from './dates';
import { asSeniorOnSameCase, assess } from './underwriting';

/**
 * Unit-linked U2 through the real stack: an admin publishes a version offering switches, withdrawals, top-ups and a
 * surrender charge; a policy is sold and its first premium collected. Forward pricing means nothing done today can be
 * priced today -- the premium waits for tomorrow's price, so the policy holds no units yet. The spec therefore proves
 * every request reaches the state it should from there: a redirection lands in the split history; a switch is not
 * offered on a policy with nothing to move; a withdrawal is refused in the server's words because it would leave less
 * than the version's minimum; a top-up is collected once; and an on-demand statement is filed and downloads as a PDF.
 * Switches and withdrawals priced, charged and paid are the integration tests' (SwitchIntegrationTest,
 * WithdrawalIntegrationTest, UnitLinkedU2AccountingIntegrationTest), where a test can choose the day.
 */

async function asFinance<T>(browser: Browser, work: (page: Page) => Promise<T>): Promise<T> {
  const context = await browser.newContext({ storageState: 'e2e/.auth/staff-finance.json' });
  try {
    return await work(await context.newPage());
  } finally {
    await context.close();
  }
}

function daysAgoIso(days: number): string {
  const d = new Date(`${todayIso()}T12:00:00Z`);
  d.setUTCDate(d.getUTCDate() - days);
  return d.toISOString().slice(0, 10);
}

const formatTzs = (n: number) => `TZS ${n.toLocaleString('en-US', { minimumFractionDigits: 2 })}`;

test.describe('unit-linked U2', () => {
  test.setTimeout(420_000);

  test('a version with options, a redirection, a refused withdrawal, a top-up and an on-demand statement', async ({ page, browser }) => {
    const run = String(Date.now() % 1_000_000).padStart(6, '0');
    const equity = `EQ${run}`;
    const cash = `MM${run}`;
    const code = `UL2-E2E-${run}`;
    const name = `E2E Wekeza Plus ${code}`;
    const sumAssured = 6_000_000 + (Date.now() % 1000) * 100;
    const yesterday = daysAgoIso(1);

    // ---- Two funds with yesterday's prices, proposed by finance and approved by an admin.
    await asFinance(browser, async (finance) => {
      await finance.goto('/staff/funds');
      for (const [fund, assetClass] of [[equity, 'EQUITY'], [cash, 'MONEY_MARKET']] as const) {
        const add = finance.getByRole('form', { name: 'Add a fund' });
        await add.getByLabel('Code').fill(fund);
        await add.getByLabel('Name').fill(`E2E ${fund}`);
        await add.getByLabel('Asset class').selectOption(assetClass);
        await add.getByLabel('Management charge (% a year)').fill('1');
        await add.getByLabel('Daily cut-off (EAT)').fill('00:01');
        await add.getByRole('button', { name: 'Add fund' }).click();
        const card = finance.getByRole('listitem', { name: `Fund ${fund}` });
        await expect(card).toBeVisible({ timeout: 15_000 });
        await card.getByRole('button', { name: 'Prices' }).click();
        const propose = card.getByRole('form', { name: `Propose a price for ${fund}` });
        await propose.getByLabel('Valuation date').fill(dmy(yesterday));
        await propose.getByLabel('Price (TZS per unit)').fill('1.000000');
        await propose.getByRole('button', { name: 'Propose price' }).click();
        await expect(card.getByRole('table', { name: `Prices of ${fund}` })).toContainText('Proposed', { timeout: 15_000 });
      }
    });
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

    // ---- The version, with every U2 option: the publish form refuses half a pair before it sends anything.
    await asAdmin(browser, async (admin) => {
      await admin.goto('/staff/products/new');
      await admin.getByLabel('Product code').fill(code);
      await admin.getByLabel('Product name').fill(name);
      await admin.getByLabel('Category').selectOption('UNIT_LINKED');
      await admin.getByLabel('Default currency').fill('TZS');
      await admin.getByRole('button', { name: 'Create product' }).click();
      await expect(admin.getByText('DRAFT')).toBeVisible();

      await admin.getByLabel('Effective date').fill(dmy('2026-01-01'));
      await admin.getByLabel('TIRA filing reference').fill('TIRA/E2E/UL2');
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

      await admin.getByLabel('Free switches per policy year').fill('2');
      await admin.getByRole('button', { name: 'Publish version' }).click();
      await expect(admin.getByText('Switching needs both the free switches per year and the fee for each switch after them')).toBeVisible();
      await admin.getByLabel('Fee per extra switch').fill('5000');
      await admin.getByLabel('Minimum withdrawal').fill('100000');
      await admin.getByLabel('Minimum value left after a withdrawal').fill('500000');
      await admin.getByLabel('Top-up allocation (%)').fill('98');
      await admin.getByLabel('Minimum top-up').fill('50000');
      await admin.getByLabel('Surrender charge bands').fill('1,1,10\n2,5,5\n6,,0');

      await admin.getByRole('button', { name: 'Publish version' }).click();
      await admin.getByRole('button', { name: 'Publish and make active' }).click();
      await expect(admin).toHaveURL(/\/staff\/products\/[0-9a-f-]{36}$/, { timeout: 15_000 });
    });

    // ---- The sale, 60/40, accepted by a second person.
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
    await page.getByLabel(`${cash} (%)`).fill('40');
    await page.getByRole('button', { name: 'Open case' }).click();
    await expect(page).toHaveURL(/\/staff\/underwriting\/[0-9a-f-]{36}$/, { timeout: 15_000 });
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

    // ---- The first premium through the mock rail; it waits for tomorrow's price.
    await asAdmin(browser, async (admin) => {
      await admin.goto(policyUrl);
      await admin.getByRole('tab', { name: 'Billing' }).click();
      await admin.getByRole('button', { name: 'Request payment' }).first().click({ timeout: 20_000 });
      const pay = admin.locator('form').filter({ has: admin.getByLabel('Payer reference') });
      await pay.getByLabel('Payer reference').fill('+255700000782');
      await pay.getByRole('button', { name: 'Request payment' }).click();
      await expect(admin.getByLabel('Payer reference')).not.toBeVisible({ timeout: 60_000 });
    });
    await expect(async () => {
      await page.goto(policyUrl);
      await page.getByRole('tab', { name: 'Units' }).click();
      await expect(page.getByRole('region', { name: 'Waiting for a price' })).toContainText(equity, { timeout: 10_000 });
    }).toPass({ timeout: 90_000, intervals: [10_000] });

    // ---- Redirection: future premiums 30/70; the issue split stays in the history.
    const split = page.getByRole('region', { name: 'Premium split' });
    await expect(split).toContainText(`${equity} 60%, ${cash} 40%`, { timeout: 15_000 });
    await split.getByLabel(`${equity} (%)`).fill('30');
    await split.getByLabel(`${cash} (%)`).fill('60');
    await split.getByRole('button', { name: 'Redirect future premiums' }).click();
    await expect(split.getByRole('alert')).toHaveText('The fund split totals 90%; it must total 100%');
    await split.getByLabel(`${cash} (%)`).fill('70');
    await split.getByRole('button', { name: 'Redirect future premiums' }).click();
    await expect(split).toContainText(`${equity} 30%, ${cash} 70%`, { timeout: 15_000 });
    await expect(split.getByRole('list', { name: 'Split history' })).toContainText(`${equity} 60%, ${cash} 40%`);

    // ---- No units yet (nothing is priced today), so there is nothing to switch.
    const switching = page.getByRole('region', { name: 'Fund switch' });
    await expect(switching).toContainText('2 free switches a policy year');
    await expect(switching.getByRole('form', { name: 'Request a switch' })).toHaveCount(0);

    // ---- A withdrawal from a policy holding nothing is refused, in the server's words.
    const withdrawal = page.getByRole('region', { name: 'Partial withdrawal' });
    await withdrawal.getByLabel('Gross amount (TZS)').fill('50000');
    await withdrawal.getByLabel('Pay to').fill('+255700000600');
    await withdrawal.getByRole('button', { name: 'Request withdrawal' }).click();
    await expect(withdrawal.getByText('A withdrawal is at least 100,000.00 TZS')).toBeVisible();
    await withdrawal.getByLabel('Gross amount (TZS)').fill('100000');
    await withdrawal.getByRole('button', { name: 'Request withdrawal' }).click();
    await expect(
      withdrawal.getByText('This withdrawal would leave about 0.00 TZS; at least 500,000.00 TZS must stay in the policy'),
    ).toBeVisible({ timeout: 15_000 });

    // ---- A top-up, collected through the mock rail once.
    const topUp = page.getByRole('region', { name: 'Top-up' });
    await topUp.getByLabel('Amount (TZS)').fill('200000');
    await topUp.getByLabel('Collect from').fill('+255700000700');
    await topUp.getByRole('button', { name: 'Request top-up' }).click();
    await expect(topUp.getByRole('list', { name: 'Top-ups' }).getByRole('listitem')).toHaveCount(1, { timeout: 15_000 });
    await expect(topUp.getByRole('list', { name: 'Top-ups' })).toContainText('TZS 200,000.00 from +255700000700');

    // ---- An on-demand statement for the last 30 days: listed, and it downloads as a PDF.
    const statements = page.getByRole('region', { name: 'Unit statements' });
    await statements.getByLabel('From').fill(dmy(daysAgoIso(30)));
    await statements.getByRole('button', { name: 'File statement' }).click();
    const filed = statements.getByLabel('Filed statements');
    await expect(filed).toContainText('On demand', { timeout: 30_000 });
    const download = page.waitForEvent('download');
    await filed.getByRole('button', { name: 'Download' }).first().click();
    expect((await download).suggestedFilename()).toMatch(/^unit-statement-POL-[A-Z0-9]+-.*\.pdf$/);

    // ---- The top-up's money arrives through the rail and waits, like any premium, for tomorrow's price.
    await expect(async () => {
      await page.goto(policyUrl);
      await page.getByRole('tab', { name: 'Units' }).click();
      await expect(page.getByRole('region', { name: 'Top-up' })).toContainText('received', { timeout: 10_000 });
    }).toPass({ timeout: 90_000, intervals: [10_000] });
  });
});
