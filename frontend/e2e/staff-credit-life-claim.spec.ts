import { expect, test, type Page } from '@playwright/test';
import { seedCreditLifeScheme } from './creditLife';
import { dmy, todayIso } from './dates';
import { fillPolicyNumberManually } from './guards';

/**
 * Registering a claim on a credit-life scheme, through the screen a person actually uses.
 *
 * <p><b>This could not be done at all until the fix this spec guards.</b> The claim form decided
 * whether a policy insures many lives by testing {@code productCategory === 'GROUP_LIFE'}, so a
 * CREDIT_LIFE scheme was treated as individual business: the "Who died" picker never rendered,
 * the schedule was never fetched, and the form posted with no member. The backend then refused —
 * "Scheme ... insures many lives, so a claim on it names a member" — correctly, and about a field
 * that was not on the screen. There was no way through.
 *
 * <p>The mirror of that bug is asserted in `staff-group-schemes.spec.ts`, which pins that an
 * INDIVIDUAL policy is asked for no member. Between them the two say what the rule actually is:
 * the question is whether the contract insures many lives, not which category it happens to be.
 */

/** Fills and submits the register form for a death on the scheme's opening borrower. */
async function submitDeathClaim(page: Page, policyNumber: string, lenderName: string) {
  await page.goto('/staff/claims/new');
  await expect(page.getByRole('heading', { name: 'Register a claim' })).toBeVisible({
    timeout: 20_000,
  });

  /*
   * CLAIMANT FIRST, POLICY SECOND. Once a scheme's number is entered this page renders an
   * <option> per life, and an <option> answers to a text locator while never being "visible" to
   * Playwright -- so a claimant picker addressed by text can resolve to one and hang. The
   * group-scheme spec records the same ordering for the same reason.
   */
  await page.getByRole('button', { name: 'Search for the claimant by name' }).click();
  await page.getByPlaceholder('Type a name to search').fill('E2E Microfinance');
  await page.getByRole('option', { name: lenderName }).click();

  await fillPolicyNumberManually(page, policyNumber);

  /*
   * THE PICKER EXISTS, which is the fix. And it names the borrower rather than printing their
   * id: a credit-life member is FREEFORM and has no party record, so the old label -- which
   * resolved a name through the party module or fell back to the raw uuid -- offered a column
   * of uuids to the question "who died?".
   */
  const whoDied = page.getByLabel('Who died');
  await expect(whoDied).toBeVisible({ timeout: 20_000 });
  await expect(whoDied).toContainText('Amina Hassan Mwinyi');
  // The reference beside the name, because on a real book several borrowers share one.
  await expect(whoDied).toContainText(/CL-[A-Z0-9]+-\d{6}/);

  await whoDied.selectOption({ index: 1 });

  const today = todayIso();
  await page.getByLabel('Date of event').fill(dmy(today));
  await page.getByLabel('Claim type').selectOption('DEATH');
  await page.getByLabel('Cause of death').fill('Natural causes');
  await page.getByLabel('Place of death').fill('Dar es Salaam');
  await page.getByLabel('Date of death').fill(dmy(today));
  await page.getByLabel('Attending physician').fill('Dr E2E');

  await page.getByRole('button', { name: 'Register claim' }).click();
}

test.describe('staff credit-life claim', () => {
  test('a claim names the borrower who died, by name rather than by uuid', async ({ page }) => {
    test.slow();
    const { policyNumber, lenderName } = await seedCreditLifeScheme();

    await submitDeathClaim(page, policyNumber, lenderName);

    // A real claim, on a real member. The refusal this spec exists for would have landed here.
    await expect(page).toHaveURL(/\/staff\/claims\/[0-9a-f-]{36}$/, { timeout: 30_000 });
    await expect(page.getByText(/insures many lives/)).toHaveCount(0);
  });

  /**
   * The whole of a credit-life death, screen by screen, as three different people.
   *
   * Each step here was a dead end until this change: approval asked for a mobile-money
   * destination on a payout that goes to the lender by bank transfer; the claim then sat at
   * "Settlement requested" with nothing saying it was waiting on finance; the roll showed the
   * dead borrower as an ordinary ACTIVE loan; and nothing stopped the same death being claimed
   * again -- three times, in the dev database, for TZS 1,640,000 against 800,000 of cover.
   */
  test('a death is paid once, to the lender, and the roll follows it', async ({ page, browser }) => {
    test.setTimeout(300_000);
    const { policyNumber, lenderName } = await seedCreditLifeScheme();

    await submitDeathClaim(page, policyNumber, lenderName);
    await expect(page).toHaveURL(/\/staff\/claims\/[0-9a-f-]{36}$/, { timeout: 30_000 });
    const claimUrl = new URL(page.url()).pathname;

    // THE ROLL SAYS SO. Still on cover until paid -- but no longer indistinguishable from a
    // live loan.
    await page.goto(`/staff/group-schemes/${policyNumber}`);
    const borrowerRow = page.getByRole('row').filter({ hasText: 'Amina Hassan Mwinyi' });
    await expect(borrowerRow).toContainText('Death claim in progress', { timeout: 30_000 });
    await expect(borrowerRow).toContainText('Claim pending — cover ends on settlement');

    // ONE DEATH CLAIM PER LIFE. The second attempt is refused and names the first.
    await submitDeathClaim(page, policyNumber, lenderName);
    await expect(page.getByRole('alert')).toContainText(/A death claim already exists for this life/, {
      timeout: 30_000,
    });

    // Assess, as somebody else -- the recommendation opens at the borrower's cover.
    const assessorContext = await browser.newContext({ storageState: 'e2e/.auth/staff-assessor.json' });
    const assessorPage = await assessorContext.newPage();
    await assessorPage.goto(claimUrl);
    await expect(assessorPage.getByLabel('Recommended amount')).not.toHaveValue('', { timeout: 30_000 });
    await assessorPage.getByLabel('Findings').fill('Death certificate verified');
    await assessorPage.getByRole('button', { name: 'Submit assessment' }).click();
    await expect(assessorPage.getByText('Under assessment')).toBeVisible({ timeout: 15_000 });
    await assessorContext.close();

    // Approve, as a third person. NO PAYEE FIELD: the lender is the only payee there is.
    const managerContext = await browser.newContext({ storageState: 'e2e/.auth/staff-manager.json' });
    const managerPage = await managerContext.newPage();
    await managerPage.goto(claimUrl);
    await expect(managerPage.getByRole('heading', { name: 'Decide settlement' })).toBeVisible({
      timeout: 30_000,
    });
    await expect(managerPage.getByText(/the lender who holds this scheme, by bank transfer/)).toBeVisible({
      timeout: 30_000,
    });
    await expect(managerPage.getByLabel('Payee reference')).toHaveCount(0);

    /*
      WAIT FOR THE PREFILL. The amount is written in when the assessment and the cover arrive,
      which can be after the lender line renders. Clicking before that submits a blank amount,
      and the prefill that lands a moment later resets the form -- clearing the error with it --
      so the page ends up filled, focused and silent, with no confirmation. That is exactly how
      this step first failed.
    */
    await expect(managerPage.getByLabel('Approved amount')).not.toHaveValue('', { timeout: 30_000 });
    await expect(managerPage.getByText(/recommended by/)).toBeVisible();

    await managerPage.getByRole('button', { name: 'Approve claim' }).click();
    // On a scheme one life leaves; the policy does not close.
    await expect(managerPage.getByText(/this life leaves the scheme when the claim settles/)).toBeVisible();
    await managerPage.getByRole('button', { name: 'Approve and pay' }).click();

    // WHERE THE MONEY IS. Used to be a bare "Settlement requested" and nothing more.
    await expect(managerPage.getByText('Settlement requested', { exact: true })).toBeVisible({
      timeout: 30_000,
    });
    await expect(
      managerPage.getByText(/Waiting for Finance to make this bank transfer and record it/),
    ).toBeVisible({ timeout: 30_000 });

    // Finance makes the transfer and records it -- the lender, named by the platform, is the
    // payee on the queue.
    const financeContext = await browser.newContext({ storageState: 'e2e/.auth/staff-finance.json' });
    const financePage = await financeContext.newPage();
    await financePage.goto('/staff/bank-transfers');
    // Found by its scheme, which the For column now names.
    const transfer = financePage.getByRole('row').filter({ hasText: `scheme ${policyNumber}` });
    await expect(transfer).toBeVisible({ timeout: 30_000 });
    // WHO, where this used to print a typed payee and a raw claim id: the lender as the payee,
    // the borrower whose death this pays, and the claim it settles.
    await expect(transfer).toContainText(lenderName, { timeout: 30_000 });
    await expect(transfer).toContainText('Amina Hassan Mwinyi');
    await expect(transfer).toContainText('Death claim');
    await expect(transfer).toContainText('Claim settlement');
    await transfer.getByRole('button', { name: 'Record payment made' }).click();
    const confirmation = financePage.getByRole('group', { name: 'Has this transfer been made?' });
    await confirmation.getByLabel('Bank reference', { exact: true }).fill(`E2E-FT-${Date.now()}`);
    await confirmation.getByRole('button', { name: 'Record the transfer' }).click();
    await expect(transfer).toHaveCount(0, { timeout: 30_000 });
    await financeContext.close();

    // Settled, and the borrower off cover for the reason that is true.
    await managerPage.reload();
    await expect(managerPage.getByText('Settled', { exact: true })).toBeVisible({ timeout: 30_000 });
    await managerPage.goto(`/staff/group-schemes/${policyNumber}`);
    const settledRow = managerPage.getByRole('row').filter({ hasText: 'Amina Hassan Mwinyi' });
    await expect(settledRow).toContainText('Death claim paid', { timeout: 30_000 });
    await expect(settledRow).not.toContainText('Death claim in progress');
    await managerContext.close();
  });
});
