import { expect, test } from '@playwright/test';
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
test.describe('staff credit-life claim', () => {
  test('a claim names the borrower who died, by name rather than by uuid', async ({ page }) => {
    test.slow();
    const { policyNumber, lenderName } = await seedCreditLifeScheme();

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

    // A real claim, on a real member. The refusal this spec exists for would have landed here.
    await expect(page).toHaveURL(/\/staff\/claims\/[0-9a-f-]{36}$/, { timeout: 30_000 });
    await expect(page.getByText(/insures many lives/)).toHaveCount(0);
  });
});
