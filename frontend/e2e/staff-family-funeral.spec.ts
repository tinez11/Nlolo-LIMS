import { expect, test, type Page } from '@playwright/test';
import { asAdmin } from './admin';
import { dmy, todayIso } from './dates';
import { fillPolicyNumberManually } from './guards';
import { asSeniorOnSameCase, assess } from './underwriting';

/**
 * Family funeral cover through the real stack: a funeral plan published with its plans, premium table
 * and claim rules; a family sold on it with the server's live quote; accepted by a second person, which
 * issues one policy carrying every life; a baby added; the first premium collected; and two deaths
 * inside the six-month waiting period -- an accident, which the product waives and pays, and a natural
 * death, whose approval is refused and which is declined for the waiting period.
 *
 * The spec authors its own product. Its main-member benefit is unique to the run, so the new policy is
 * the one row with that sum assured among the seeded Amina Owner's (born 12 April 1990, so 36: band
 * 18-100 at 60,000 a year). Two children at 6,000 each: 72,000 a year, 6,300 a month with the 5% loading.
 */

const formatTzs = (n: number) => `TZS ${n.toLocaleString('en-US', { minimumFractionDigits: 2 })}`;

async function registerDeath(page: Page, policyNumber: string, whoDied: string, accidental: boolean): Promise<string> {
  const today = todayIso();
  await page.goto('/staff/claims/new');
  await fillPolicyNumberManually(page, policyNumber);
  await page.getByRole('button', { name: 'Search for the claimant by name' }).click();
  await page.getByPlaceholder('Type a name to search').fill('Amina');
  // By role: "Who died?" already lists "Amina Owner (main member)" as a hidden <option>.
  await page.getByRole('option', { name: 'Amina Owner' }).click();
  await page.getByLabel('Date of event').fill(dmy(today));
  await page.getByLabel('Claim type').selectOption('DEATH');
  await page.getByLabel('Who died?').selectOption({ label: `${whoDied} (child)` });
  if (accidental) await page.getByLabel('Accidental death').check();
  await page.getByLabel('Cause of death').fill(accidental ? 'Road accident' : 'Malaria');
  await page.getByLabel('Place of death').fill('Dar es Salaam');
  await page.getByLabel('Date of death').fill(dmy(today));
  await page.getByLabel('Attending physician').fill('Dr. E2E Test');
  await page.getByRole('button', { name: 'Register claim' }).click();
  await expect(page).toHaveURL(/\/staff\/claims\/[0-9a-f-]{36}$/, { timeout: 15_000 });
  return page.url().split('/').pop() as string;
}

async function assessClaim(page: Page, claimId: string) {
  const browser = page.context().browser()!;
  const context = await browser.newContext({ storageState: 'e2e/.auth/staff-assessor.json' });
  try {
    const assessor = await context.newPage();
    await assessor.goto(`/staff/claims/${claimId}`);
    await assessor.getByLabel('Findings').fill('Death certificate seen');
    await assessor.getByLabel('Recommended amount').fill('1000000.00');
    await assessor.getByRole('button', { name: 'Submit assessment' }).click();
    await expect(assessor.getByText('Under assessment')).toBeVisible({ timeout: 15_000 });
  } finally {
    await context.close();
  }
}

async function asManager<T>(page: Page, claimId: string, work: (manager: Page) => Promise<T>): Promise<T> {
  const browser = page.context().browser()!;
  const context = await browser.newContext({ storageState: 'e2e/.auth/staff-manager.json' });
  try {
    const manager = await context.newPage();
    await manager.goto(`/staff/claims/${claimId}`);
    return await work(manager);
  } finally {
    await context.close();
  }
}

test.describe('family funeral cover', () => {
  // A product published, a case decided by a second person, a collection through the rail, a life
  // added, and two claims each through three identities.
  test.setTimeout(420_000);

  test('a family is sold plan B, a baby added, an accident paid and a natural death declined inside the waiting period', async ({ page, browser }) => {
    const code = `FUN-E2E-${Date.now()}`;
    const name = `E2E Familia ${code}`;
    const mainBenefit = 2_000_000 + (Date.now() % 1000) * 100;

    // ---- The product, authored by an admin.
    await asAdmin(browser, async (admin) => {
      await admin.goto('/staff/products/new');
      await admin.getByLabel('Product code').fill(code);
      await admin.getByLabel('Product name').fill(name);
      await admin.getByLabel('Category').selectOption('FUNERAL');
      await admin.getByLabel('Default currency').fill('TZS');
      await admin.getByRole('button', { name: 'Create product' }).click();
      await expect(admin.getByText('DRAFT')).toBeVisible();

      // No rating table on a funeral plan: its premium table is its whole price.
      await expect(admin.getByText(/Rating table -- must cover/)).not.toBeVisible();
      await admin.getByLabel('Effective date').fill(dmy('2026-01-01'));
      await admin.getByLabel('TIRA filing reference').fill('TIRA/E2E/FUN');
      await admin.getByLabel('TIRA approval date').fill(dmy('2026-01-15'));
      await admin.getByLabel('Free-look days').fill('15');
      await admin.getByLabel('Monthly loading %').fill('5');

      await admin.getByRole('button', { name: 'Add plan' }).click();
      await admin.getByLabel('Plan code').fill('B');
      await admin.getByLabel('Plan name').fill('Familia B');
      await admin.getByLabel('Main member benefit').fill(String(mainBenefit));
      await admin.getByLabel('Spouse benefit').fill('2000000');
      await admin.getByLabel('Child benefit').fill('1000000');
      // The premium grid: one box per covered role, at the default entry ages.
      await admin.getByLabel('B Main member ages 18–100 yearly premium').fill('60000');
      await admin.getByLabel('B Spouse ages 18–100 yearly premium').fill('60000');
      await admin.getByLabel('B Child ages 0–24 yearly premium').fill('6000');
      await admin.getByLabel('Waiting period (months)').fill('6');
      await admin.getByLabel('When a dependant dies, pay').selectOption('MAIN_MEMBER');
      await admin.getByLabel('When the main member dies').selectOption('POLICY_ENDS');

      await admin.getByRole('button', { name: 'Publish version' }).click();
      await admin.getByRole('button', { name: 'Publish and make active' }).click();
      await expect(admin).toHaveURL(/\/staff\/products\/[0-9a-f-]{36}$/, { timeout: 15_000 });
    });

    // ---- The sale: Amina and two children on plan B, monthly, quoted live.
    const today = todayIso();
    const neemaBorn = `${Number(today.slice(0, 4)) - 10}-01-15`;
    const barakaBorn = `${Number(today.slice(0, 4)) - 7}-03-20`;
    await page.goto('/staff/underwriting/new');
    await page.getByRole('button', { name: 'Search for the applicant by name' }).click();
    await page.getByPlaceholder('Type a name to search').fill('Amina');
    await page.getByRole('option', { name: 'Amina Owner' }).click();
    await page.getByLabel('Product').selectOption({ label: `${name} (${code})` });
    await expect(page.getByText('Resolving product version…')).not.toBeVisible();
    await page.getByLabel('Plan', { exact: true }).selectOption('B');
    // The sum assured is the plan's main-member benefit, filled for staff (R3).
    await expect(page.getByLabel(/^Sum assured/)).toHaveValue(mainBenefit.toFixed(2));
    await page.getByLabel('Premium frequency').selectOption('MONTHLY');
    await page.getByRole('button', { name: 'Add dependant' }).click();
    await page.getByLabel('Full name').nth(0).fill('Neema');
    await page.getByLabel('Date of birth').nth(0).fill(dmy(neemaBorn));
    await page.getByRole('button', { name: 'Add dependant' }).click();
    await page.getByLabel('Full name').nth(1).fill('Baraka');
    await page.getByLabel('Date of birth').nth(1).fill(dmy(barakaBorn));
    const quote = page.getByRole('table', { name: 'Family quote' });
    await expect(quote).toContainText('TZS 72,000.00', { timeout: 15_000 });
    await expect(quote.getByLabel('Instalment')).toHaveText('TZS 6,300.00');
    await page.getByRole('button', { name: 'Open case' }).click();
    await expect(page).toHaveURL(/\/staff\/underwriting\/[0-9a-f-]{36}$/, { timeout: 15_000 });
    await expect(page.getByRole('heading', { name: 'Funeral plan' })).toBeVisible({ timeout: 15_000 });

    // ---- Assessed, then accepted by someone else. Acceptance issues the policy with its lives.
    await assess(page, 'Healthy family, no adverse findings', '10');
    await asSeniorOnSameCase(page, async (senior) => {
      await senior.getByLabel('Decision').selectOption({ label: 'Accept' });
      await senior.getByLabel('Reason').fill('Family accepted at standard terms');
      await senior.getByRole('button', { name: 'Record decision' }).click();
      await senior.getByRole('button', { name: 'Record the acceptance' }).click();
      await expect(senior.getByRole('heading', { name: 'Decision', exact: true })).toBeVisible({ timeout: 60_000 });
    });

    const applicantHref = await page.locator('a[href^="/staff/parties/"]').first().getAttribute('href');
    const applicantId = (applicantHref as string).split('/').pop() as string;
    await expect(async () => {
      await page.goto(`/staff/policies?policyholderPartyId=${applicantId}`);
      await expect(page.getByRole('row').filter({ hasText: formatTzs(mainBenefit) })).toHaveCount(1);
    }).toPass({ timeout: 30_000 });
    await page.getByRole('row').filter({ hasText: formatTzs(mainBenefit) }).getByRole('button').first().click();
    await page.getByRole('link', { name: /full detail/i }).click();
    await expect(page).toHaveURL(/\/staff\/policies\/POL-[A-Z0-9]+$/, { timeout: 15_000 });
    const policyUrl = new URL(page.url()).pathname;
    const policyNumber = policyUrl.split('/').pop() as string;

    await page.getByRole('tab', { name: 'Covered lives' }).click();
    const lives = page.getByRole('table', { name: 'Covered lives' });
    await expect(lives).toContainText('Amina Owner', { timeout: 15_000 });
    await expect(lives).toContainText('Neema');
    await expect(lives).toContainText('Baraka');

    // ---- The first premium, collected through the mock rail: the policy goes in force.
    await asAdmin(browser, async (admin) => {
      await admin.goto(policyUrl);
      await admin.getByRole('tab', { name: 'Billing' }).click();
      await admin.getByRole('button', { name: 'Request payment' }).first().click({ timeout: 20_000 });
      const pay = admin.locator('form').filter({ has: admin.getByLabel('Payer reference') });
      await pay.getByLabel('Payer reference').fill('+255700000779');
      await pay.getByRole('button', { name: 'Request payment' }).click();
      await expect(admin.getByLabel('Payer reference')).not.toBeVisible({ timeout: 60_000 });
    });

    // ---- A baby joins from the next premium date.
    await expect(async () => {
      await page.goto(policyUrl);
      await page.getByRole('tab', { name: 'Covered lives' }).click();
      await page.getByRole('button', { name: 'Add life' }).click();
      const form = page.locator('form').filter({ has: page.getByLabel('Full name') });
      await form.getByLabel('Role').selectOption('CHILD');
      await form.getByLabel('Full name').fill('Imani');
      await form.getByLabel('Date of birth').fill(dmy(today));
      await form.getByRole('button', { name: 'Add life' }).click();
      await expect(page.getByRole('table', { name: 'Covered lives' })).toContainText('Imani', { timeout: 10_000 });
    }).toPass({ timeout: 90_000, intervals: [10_000] });

    // ---- An accidental death inside the waiting period: waived by the product, so it is paid.
    const accident = await registerDeath(page, policyNumber, 'Neema', true);
    await assessClaim(page, accident);
    await asManager(page, accident, async (manager) => {
      // The amount prefills when the assessment arrives; typing first makes the form dirty and the
      // panel then (by design) never overwrites it, leaving the amount blank.
      await expect(manager.getByLabel('Approved amount')).toHaveValue('1000000.00', { timeout: 30_000 });
      await manager.getByLabel('Payee reference').fill('+255700000779');
      await manager.getByRole('button', { name: 'Approve claim' }).click();
      await manager.getByRole('button', { name: 'Approve and pay' }).click();
      await expect(manager.getByText(/Settlement requested|Settled/).first()).toBeVisible({ timeout: 60_000 });
    });

    // ---- A natural death inside the waiting period: approval is refused, the decline names it.
    const natural = await registerDeath(page, policyNumber, 'Baraka', false);
    await assessClaim(page, natural);
    await asManager(page, natural, async (manager) => {
      // The amount prefills when the assessment arrives; typing first makes the form dirty and the
      // panel then (by design) never overwrites it, leaving the amount blank.
      await expect(manager.getByLabel('Approved amount')).toHaveValue('1000000.00', { timeout: 30_000 });
      await manager.getByLabel('Payee reference').fill('+255700000779');
      await manager.getByRole('button', { name: 'Approve claim' }).click();
      await manager.getByRole('button', { name: 'Approve and pay' }).click();
      await expect(manager.getByText(/was inside the waiting period/)).toBeVisible({ timeout: 30_000 });

      await manager.getByRole('radio', { name: 'Reject' }).click();
      await manager.getByLabel('Rejection reason (optional)').fill('Natural death inside the waiting period');
      await manager.getByLabel('Policy-term reason (optional)').selectOption('WITHIN_WAITING_PERIOD');
      await manager.getByRole('button', { name: 'Reject claim' }).click();
      await manager.getByRole('button', { name: 'Record the rejection' }).click();
      await expect(manager.getByText('Rejected', { exact: true })).toBeVisible({ timeout: 15_000 });
    });

    // ---- The family on the policy now: the paid child has died, the declined one is still covered.
    await expect(async () => {
      await page.goto(policyUrl);
      await page.getByRole('tab', { name: 'Covered lives' }).click();
      const table = page.getByRole('table', { name: 'Covered lives' });
      await expect(table.getByRole('row').filter({ hasText: 'Neema' })).toContainText('Deceased');
      await expect(table.getByRole('row').filter({ hasText: 'Baraka' })).toContainText('Covered');
    }).toPass({ timeout: 120_000, intervals: [10_000] });
  });
});
