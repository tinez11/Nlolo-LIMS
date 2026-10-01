import { expect, test, type Browser, type Page } from '@playwright/test';
import { asAdmin } from './admin';
import { dmy } from './dates';
import { caseAwaitingManualIssue, MONEY_BACK_PRODUCT, selectUnderwritingCase } from './underwriting';

/**
 * Money owed to a LIVING policyholder, through the real stack.
 *
 * The fixture is a money-back endowment commenced five years and a day ago, so its year-5 survival
 * benefit is already in the past and the due drain brings it DUE. The drain runs every five
 * seconds under the `local` profile (application-local.yml) — hourly in production, which is right
 * for a benefit dated to a day and useless for a test.
 */

/** Five years and a day ago, so the policy's YEAR-5 anniversary has already passed. */
function fiveYearsAndADayAgo(): string {
  const date = new Date();
  date.setFullYear(date.getFullYear() - 5);
  date.setDate(date.getDate() - 1);
  const month = String(date.getMonth() + 1).padStart(2, '0');
  const day = String(date.getDate()).padStart(2, '0');
  return `${date.getFullYear()}-${month}-${day}`;
}

/**
 * A money-back policy whose first survival benefit is already owed.
 *
 * Built as admin, like every other fixture in this suite: the point of the spec is who may REVIEW
 * and APPROVE a payout, and running the whole thing as admin would prove nothing about that.
 */
async function moneyBackPolicyWithADuePayout(browser: Browser): Promise<string> {
  return asAdmin(browser, async (page) => {
    const caseId = await caseAwaitingManualIssue(page, '2000000.00', MONEY_BACK_PRODUCT);
    await page.goto('/staff/policies/new');
    await selectUnderwritingCase(page, caseId);
    await expect(page.getByText('Resolving product version…')).not.toBeVisible();
    await page.getByLabel('Sum assured').fill('2000000.00');
    await page.getByLabel('Premium', { exact: true }).fill('5000.00');
    // MIGRATION so the policy is in force on arrival -- see e2e/policies.ts on why that is the
    // honest basis for a fixture standing in for a contract already running.
    await page.getByLabel('Why is this being issued by hand?').selectOption('MIGRATION');
    await page.getByLabel('Reason for manual issue').fill('E2E fixture: money-back payout');
    await page.getByLabel('Commencement date').fill(dmy(fiveYearsAndADayAgo()));
    await page.getByLabel('Policy term (months)').fill('240');
    await page.getByRole('button', { name: 'Issue policy' }).click();
    await expect(page).toHaveURL(/\/staff\/policies\/POL-[A-Z0-9]+$/, { timeout: 15_000 });
    return page.url().split('/').pop() as string;
  });
}

/** The schedule is written by an AFTER_COMMIT listener and brought due by a sweep, so the first
 *  read after issuance is a poll rather than an assumption about timing. */
async function openDuePayout(page: Page, policyNumber: string): Promise<void> {
  await expect(async () => {
    await page.goto(`/staff/policies/${policyNumber}`);
    await page.getByRole('tab', { name: 'Payouts' }).click();
    await expect(page.getByText('Survival benefit').first()).toBeVisible();
    await expect(page.getByText('Due', { exact: true }).first()).toBeVisible();
  }).toPass({ timeout: 45_000 });
  await page.getByRole('link', { name: 'Open payout' }).first().click();
  await expect(page).toHaveURL(/\/staff\/payouts\/[0-9a-f-]{36}$/);
}

test.describe('payouts register', () => {
  test.use({ storageState: 'e2e/.auth/staff-finance.json' });

  test('lists payouts and filters by one status at a time', async ({ page }) => {
    await page.goto('/staff/payouts');
    await expect(page.getByRole('heading', { name: 'Payouts', exact: true })).toBeVisible();

    // One filter at a time, like every other register here.
    await page.getByRole('button', { name: 'Due' }).click();
    await expect(page).toHaveURL(/status=DUE/);
    await page.getByRole('button', { name: 'All' }).click();
    await expect(page).not.toHaveURL(/status=/);
  });

  test('a policy that pays nothing while alive says so plainly', async ({ page, browser }) => {
    // A term policy carries no schedule, and the tab must say that rather than look broken.
    const policyNumber = await asAdmin(browser, async (adminPage) => {
      const { issueRealPolicy } = await import('./policies');
      return issueRealPolicy(adminPage, 'E2E fixture: payouts empty state');
    });
    await page.goto(`/staff/policies/${policyNumber}`);
    await page.getByRole('tab', { name: 'Payouts' }).click();
    await expect(page.getByText('No payouts scheduled')).toBeVisible();
  });
});

test.describe('a payout passes two people', () => {
  test.use({ storageState: 'e2e/.auth/staff-finance.json' });

  test('reviewed by one, approved by another, and the reviewer is refused', async ({
    page,
    browser,
  }) => {
    const policyNumber = await moneyBackPolicyWithADuePayout(browser);
    await openDuePayout(page, policyNumber);
    const payoutUrl = page.url();

    // 10% of 2,000,000 on the fifth anniversary.
    await expect(page.getByText('TZS 200,000.00').first()).toBeVisible();

    await page.getByLabel('Payee reference').fill('+255700000009');
    await page
      .getByLabel('How was the life assured confirmed alive?')
      .selectOption('IN_PERSON');
    await page.getByRole('button', { name: 'Review', exact: true }).click();

    // The SAME person cannot approve what they reviewed. The gate says so in the server's words
    // and the button is disabled, rather than letting them meet a 422.
    await expect(
      page.getByText(/must be approved by someone other than the person who reviewed it/),
    ).toBeVisible({ timeout: 15_000 });
    await expect(page.getByRole('button', { name: 'Approve', exact: true })).toBeDisabled();

    // A second person releases it.
    await asAdmin(browser, async (adminPage) => {
      await adminPage.goto(payoutUrl);
      await adminPage.getByRole('button', { name: 'Approve', exact: true }).click();
      await adminPage.getByRole('button', { name: 'Approve and pay' }).click();
      // REQUESTED from the rail, not paid -- and the page must not claim otherwise.
      await expect(
        adminPage.getByText('The payment has been requested from the provider.'),
      ).toBeVisible({ timeout: 20_000 });
    });
  });
});

test.describe('the payouts queue is finance’s', () => {
  // The default staff identity is `staff.underwriter`, who has no business releasing money.
  test('an underwriter cannot reach it', async ({ page }) => {
    await page.goto('/staff/payouts');
    await expect(page.getByRole('heading', { name: 'Payouts', exact: true })).toHaveCount(0);
  });
});
