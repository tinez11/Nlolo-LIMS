import { expect, test, type Page } from '@playwright/test';
import { asAdmin } from './admin';
import { dmy, todayIso } from './dates';
import { fillPolicyNumberManually } from './guards';
import { acceptProposedScheme, pickParty } from './groupSchemes';

/**
 * A group funeral scheme through the real stack (2026-10-07): a FUNERAL product sold to group schemes,
 * plan A1 at 3,000 per member per month; an association's scheme proposed with one family -- Juma and
 * his child -- underwritten and accepted by a second person; the first bill paid; a second member
 * joining, which moves the bill to 2 x 3,000; and the child's accidental death claimed and paid at
 * plan A1's child benefit inside the six-month waiting period, which the version waives for accidents.
 *
 * The version pays a dependant's death to the beneficiary the main member named, so the seeded Amina
 * Owner can file it without a promotion step.
 */

async function asStaff<T>(page: Page, state: string, url: string, work: (other: Page) => Promise<T>): Promise<T> {
  const context = await page.context().browser()!.newContext({ storageState: state });
  try {
    const other = await context.newPage();
    await other.goto(url);
    return await work(other);
  } finally {
    await context.close();
  }
}

test.describe('group funeral schemes', () => {
  test.setTimeout(420_000);

  test('an association is proposed one family, pays, gains a member and is paid a child’s death', async ({ page, browser }) => {
    const code = `FUN-GRP-E2E-${Date.now()}`;
    const name = `E2E Chama ${code}`;
    const year = Number(todayIso().slice(0, 4));
    const suffix = String(Date.now() % 100000);
    const juma = `Juma E2E ${suffix}`;
    const neema = `Neema E2E ${suffix}`;
    const rehema = `Rehema E2E ${suffix}`;

    // ---- The product, sold to group schemes only, authored by an admin.
    await asAdmin(browser, async (admin) => {
      await admin.goto('/staff/products/new');
      await admin.getByLabel('Product code').fill(code);
      await admin.getByLabel('Product name').fill(name);
      await admin.getByLabel('Category').selectOption('FUNERAL');
      await admin.getByLabel('Default currency').fill('TZS');
      await admin.getByRole('button', { name: 'Create product' }).click();
      await expect(admin.getByText('DRAFT')).toBeVisible();

      await admin.getByLabel('Effective date').fill(dmy('2026-01-01'));
      await admin.getByLabel('TIRA filing reference').fill('TIRA/E2E/FUNGRP');
      await admin.getByLabel('TIRA approval date').fill(dmy('2026-01-15'));
      await admin.getByLabel('Free-look days').fill('15');
      await admin.getByLabel('Sold as').selectOption('GROUP');
      await admin.getByRole('button', { name: 'Add plan' }).click();
      await admin.getByLabel('Plan code').fill('A1');
      await admin.getByLabel('Plan name').fill('Plan A1');
      await admin.getByLabel('Group rate per member per month').fill('3000');
      await admin.getByLabel('Main member benefit').fill('2000000');
      await admin.getByLabel('Spouse benefit').fill('1000000');
      await admin.getByLabel('Child benefit').fill('500000');
      await admin.getByLabel('When a dependant dies, pay').selectOption('MAIN_MEMBER_BENEFICIARY');
      await admin.getByLabel('When the main member dies').selectOption('POLICY_ENDS');
      await admin.getByRole('button', { name: 'Publish version' }).click();
      await admin.getByRole('button', { name: 'Publish and make active' }).click();
      await expect(admin).toHaveURL(/\/staff\/products\/[0-9a-f-]{36}$/, { timeout: 15_000 });
    });

    // ---- The proposal: one family, Juma and his child, on plan A1.
    await page.goto('/staff/group-funeral-schemes/new');
    await expect(page.getByRole('heading', { name: 'Propose a group funeral scheme' })).toBeVisible();
    await pickParty(page, 'Search for the association by name', 'Amina', 'Amina Owner');
    await page.getByLabel('Funeral product').selectOption({ label: `${name} (${code})` });
    await page.getByLabel('Plan', { exact: true }).selectOption('A1', { timeout: 15_000 });
    const family = page.getByLabel('Family M001');
    await family.getByLabel('Main member').fill(juma);
    await family.getByLabel('Date of birth').first().fill(dmy(`${year - 40}-03-10`));
    await family.getByLabel('Beneficiary (optional)').fill('Amina Owner');
    await family.getByRole('button', { name: 'Add family member' }).click();
    await family.getByLabel('Role').selectOption('CHILD');
    await family.getByLabel('Name').fill(neema);
    await family.getByLabel('Date of birth').last().fill(dmy(`${year - 8}-06-01`));
    await expect(page.getByText('1 member x TZS 3,000.00 = TZS 3,000.00 a month')).toBeVisible();
    await page.getByRole('button', { name: 'Propose scheme' }).click();

    // ---- Underwritten and accepted by someone else: the scheme is issued as an offer.
    const policyNumber = await acceptProposedScheme(page);
    expect(policyNumber).toMatch(/^GRP-/);

    // ---- The association's first bill, paid through the mock rail: the scheme goes in force.
    await asAdmin(browser, async (admin) => {
      await admin.goto(`/staff/policies/${policyNumber}`);
      await admin.getByRole('tab', { name: 'Billing' }).click();
      await admin.getByRole('button', { name: 'Request payment' }).first().click({ timeout: 20_000 });
      const pay = admin.locator('form').filter({ has: admin.getByLabel('Payer reference') });
      await pay.getByLabel('Payer reference').fill('+255700000781');
      await pay.getByRole('button', { name: 'Request payment' }).click();
      await expect(admin.getByLabel('Payer reference')).not.toBeVisible({ timeout: 60_000 });
    });

    // ---- A second member joins: the bill becomes 2 x 3,000 from the next billing date.
    await expect(async () => {
      await page.goto(`/staff/group-schemes/${policyNumber}`);
      await page.getByRole('button', { name: 'Add member' }).click({ timeout: 10_000 });
      const joining = page.getByLabel('Family M002');
      await joining.getByLabel('Main member').fill(rehema);
      await joining.getByLabel('Date of birth').first().fill(dmy(`${year - 45}-01-20`));
      await page.getByRole('button', { name: 'Join the family' }).click();
      await expect(page.getByLabel('Member M002')).toContainText(rehema, { timeout: 10_000 });
    }).toPass({ timeout: 120_000, intervals: [10_000] });
    await expect(page.getByText('The bill: 2 members, TZS 6,000.00 a month')).toBeVisible({ timeout: 15_000 });

    // ---- The child's death, claimed by the beneficiary Juma named and paid at plan A1's child benefit.
    const today = todayIso();
    await page.goto('/staff/claims/new');
    await fillPolicyNumberManually(page, policyNumber);
    await page.getByRole('button', { name: 'Search for the claimant by name' }).click();
    await page.getByPlaceholder('Type a name to search').fill('Amina');
    await page.getByRole('option', { name: 'Amina Owner' }).click();
    await page.getByLabel('Date of event').fill(dmy(today));
    await page.getByLabel('Claim type').selectOption('DEATH');
    await page.getByLabel('Who died?').selectOption({ label: `M001 ${juma} — ${neema} (child)` });
    // An accident: the version's six-month waiting period (the form's default) waives it, so it is paid on day one.
    await page.getByLabel('Accidental death').check();
    await page.getByLabel('Cause of death').fill('Road accident');
    await page.getByLabel('Place of death').fill('Dar es Salaam');
    await page.getByLabel('Date of death').fill(dmy(today));
    await page.getByLabel('Attending physician').fill('Dr. E2E Test');
    await page.getByRole('button', { name: 'Register claim' }).click();
    await expect(page).toHaveURL(/\/staff\/claims\/[0-9a-f-]{36}$/, { timeout: 15_000 });
    const claimUrl = new URL(page.url()).pathname;

    await asStaff(page, 'e2e/.auth/staff-assessor.json', claimUrl, async (assessor) => {
      await assessor.getByLabel('Findings').fill('Death certificate seen');
      await assessor.getByLabel('Recommended amount').fill('500000.00');
      await assessor.getByRole('button', { name: 'Submit assessment' }).click();
      await expect(assessor.getByText('Under assessment')).toBeVisible({ timeout: 15_000 });
    });
    await asStaff(page, 'e2e/.auth/staff-manager.json', claimUrl, async (manager) => {
      await expect(manager.getByLabel('Approved amount')).toHaveValue('500000.00', { timeout: 30_000 });
      await manager.getByLabel('Payee reference').fill('+255700000781');
      await manager.getByRole('button', { name: 'Approve claim' }).click();
      await manager.getByRole('button', { name: 'Approve and pay' }).click();
      await expect(manager.getByText(/Settlement requested|Settled/).first()).toBeVisible({ timeout: 60_000 });
    });

    // ---- The child is off cover; Juma's membership and the bill carry on.
    await expect(async () => {
      await page.goto(`/staff/group-schemes/${policyNumber}`);
      const jumaFamily = page.getByLabel('Member M001');
      await expect(jumaFamily.getByRole('row').filter({ hasText: neema })).toContainText('Ended', { timeout: 5_000 });
      await expect(jumaFamily.getByRole('row').filter({ hasText: juma })).toContainText('Covered');
    }).toPass({ timeout: 120_000, intervals: [10_000] });
    await expect(page.getByText('The bill: 2 members, TZS 6,000.00 a month')).toBeVisible();
  });
});
