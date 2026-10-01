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
    // 60s -- see staff-free-look.spec.ts's note: issuance returns only after the whole
    // AFTER_COMMIT chain, so the policy exists before the response does.
    await expect(page).toHaveURL(/\/staff\/policies\/POL-[A-Z0-9]+$/, { timeout: 60_000 });
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
  // The empty-state test issues a real policy first, which is an underwriting case, a second
  // underwriter's decision and a manual issue before it can look at a tab. 60s is not enough for
  // that on a machine also running Keycloak, Postgres and the backend.
  test.setTimeout(180_000);

  test('lists payouts and filters by one status at a time', async ({ page }) => {
    await page.goto('/staff/payouts');
    await expect(page.getByRole('heading', { name: 'Payouts', exact: true })).toBeVisible();

    // One filter at a time, like every other register here.
    await page.getByRole('button', { name: 'Due' }).click();
    await expect(page).toHaveURL(/status=DUE/);
    await page.getByRole('button', { name: 'All' }).click();
    await expect(page).not.toHaveURL(/status=/);
  });

});

/*
  THE EMPTY STATE IS NOT TESTED HERE, ON PURPOSE.

  "A policy with no schedule says so" is one sentence of rendering with no server integration
  behind it, and reaching it end to end costs a whole policy issuance -- an underwriting case, a
  second underwriter's decision and a manual issue -- before the assertion can run. That issuance
  also returns only after the entire synchronous AFTER_COMMIT chain (SMS, commission, projections)
  completes, which on a loaded dev stack exceeded the shared helper's navigation budget while the
  policy itself was created perfectly well. Paying that cost, and inheriting that flakiness, to
  assert a string is the wrong trade.

  It lives in PayoutsPanel.test.tsx instead, along with the paid-up restatement and the hold
  reason. What is worth e2e is the integrated flow above: a payout really falling due behind a
  real drain, reviewed by one person and approved by another.
*/

test.describe('a payout passes two people', () => {
  test.use({ storageState: 'e2e/.auth/staff-finance.json' });
  // The fixture builds an underwriting case, a second underwriter's decision and a manual issue,
  // then WAITS on a sweep -- the drain runs every five seconds under the local profile, but the
  // instalment only falls due on the pass after issuance commits. The approval then runs in its
  // own browser context. None of that is the behaviour under test; it is the cost of reaching it.
  test.setTimeout(240_000);

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
