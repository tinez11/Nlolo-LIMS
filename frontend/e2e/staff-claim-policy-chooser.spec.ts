import { expect, test, type Page } from '@playwright/test';
import { expectStaffShellReady } from './guards';

/**
 * Claim intake, claimant first.
 *
 * The policy field used to be a free-text `POL-XXXXXXXX` box, so the clerk had to already know
 * the number -- meaning it came off a paper form or a second browser tab, and a typo produced a
 * 404 with no hint of what was meant. It is now a list of the policies the CLAIMANT is
 * connected to, in whatever capacity the platform records: owner, insured life, or active named
 * beneficiary.
 *
 * The three-way filter itself is proven server-side in `PolicyContractTest`, where a policy
 * that must be ABSENT can be controlled and a negative control can break the routing on
 * purpose. What is proven here is the wiring: that the list is scoped to the chosen claimant,
 * that it says in what capacity, that correcting the claimant does not leave the previous
 * claimant's policy selected, and that the manual escape hatch still exists.
 */

const SEEDED_POLICYHOLDER = 'Amina Owner';
/** Amina Owner's own policy in the seeded dev tenant -- she is its policyholder. */
const HER_POLICY = 'POL-6BD5702F';
/** The seeded counterpart identity, used to prove a change of claimant resets the choice. */
const OTHER_CLAIMANT = 'Baraka Other';

async function openRegisterClaim(page: Page) {
  await page.goto('/staff/claims/new');
  await expectStaffShellReady(page);
  await expect(page.getByRole('heading', { name: 'Register a claim' })).toBeVisible({
    timeout: 30_000,
  });
}

async function pickClaimant(page: Page, name: string) {
  await page.getByRole('button', { name: 'Search for the claimant by name' }).click();
  await page.getByPlaceholder('Type a name to search').fill(name.split(' ')[0] ?? name);
  await page.getByRole('option', { name }).first().click();
}

test.describe('staff claim intake -- policy chooser', () => {
  test('the policy field waits for a claimant, then lists that claimant\'s policies', async ({
    page,
  }) => {
    await openRegisterClaim(page);

    // Before a claimant there is nothing honest to list, and the field says so rather than
    // rendering an empty box that looks like "no policies exist".
    await expect(page.getByText('Choose the claimant above and their policies are listed here')).toBeVisible();

    await pickClaimant(page, SEEDED_POLICYHOLDER);

    const chooser = page.getByRole('radiogroup', { name: 'Policy to claim against' });
    await expect(chooser).toBeVisible({ timeout: 20_000 });

    // This fixture party owns more than the server's 100-row page cap, accumulated over many
    // e2e runs, so the target policy is genuinely NOT in the first page -- which is what the
    // truncation notice and the server-side filter exist for. Filtering here is therefore
    // testing the real path for this data, not working around the assertion.
    await expect(page.getByText(/Showing the \d+ most recent of \d+/)).toBeVisible();
    await page.getByLabel('Filter policies').fill(HER_POLICY);

    const herPolicy = page.getByRole('radio').filter({ hasText: HER_POLICY });
    await expect(herPolicy).toBeVisible({ timeout: 20_000 });
    // The capacity is the reason the list is worth showing at all -- a bare policy number
    // would not tell a clerk which claim they are about to register, or on whose life.
    await expect(herPolicy).toContainText('Owner');
    await expect(herPolicy).toContainText('owns this contract');

    await herPolicy.click();
    await expect(herPolicy).toHaveAttribute('aria-checked', 'true');
  });

  /**
   * The regression guard that matters most. `claimantPolicies` is keyed by party id and the
   * chosen policy is cleared when the claimant changes -- without either, a clerk who picks
   * the wrong claimant and corrects it could file the claim against the first claimant's
   * contract, with a complete-looking form and nothing on screen looking wrong.
   */
  test('correcting the claimant clears the policy chosen for the previous one', async ({ page }) => {
    await openRegisterClaim(page);
    await pickClaimant(page, SEEDED_POLICYHOLDER);

    // Any of this claimant's policies will do -- what is being tested is that the SELECTION
    // does not survive a change of claimant, not which policy was picked.
    const firstPolicy = page.getByRole('radio').first();
    await expect(firstPolicy).toBeVisible({ timeout: 20_000 });
    await firstPolicy.click();
    await expect(firstPolicy).toHaveAttribute('aria-checked', 'true');

    // Re-open the claimant picker and choose a genuinely different seeded person. Named
    // explicitly rather than "the first option that is not Amina": PartyPicker needs a
    // two-character query before it searches at all, and a filter-by-exclusion would pick up
    // whichever E2E fixture party happens to sort first that day.
    await page.getByRole('button', { name: SEEDED_POLICYHOLDER }).click();
    await page.getByPlaceholder('Type a name to search').fill('Baraka');
    await page.getByRole('option', { name: OTHER_CLAIMANT }).first().click();

    // Whatever the new claimant's list looks like, the OLD selection must not survive it.
    await expect(page.getByRole('radio', { checked: true })).toHaveCount(0, { timeout: 20_000 });
  });

  /**
   * The escape hatch is load-bearing, not a courtesy: `ClaimsApiImpl.registerClaim` enforces no
   * relationship between claimant and policy at all, so an executor or an assignee is a
   * legitimate claimant with no recorded connection. A chooser that could only ever offer
   * connected policies would make this console stricter than the platform.
   *
   * Reachable BEFORE a claimant too, so the claimant-first order stays a default rather than a
   * precondition for a clerk who already holds the number.
   */
  test('a policy number can still be typed directly, before any claimant is chosen', async ({
    page,
  }) => {
    await openRegisterClaim(page);

    await page.getByRole('button', { name: 'Enter a policy number instead' }).click();
    const manual = page.getByPlaceholder('POL-XXXXXXXX');
    await expect(manual).toBeVisible();
    await manual.fill(HER_POLICY);
    await expect(manual).toHaveValue(HER_POLICY);

    // The coverage gates read the typed policy, which proves the value reached the form and
    // not just the input -- they render only once a real policy has been resolved.
    await page.getByLabel('Date of event').fill('01/09/2026');
    await expect(page.getByText('Coverage checks')).toBeVisible({ timeout: 20_000 });
  });
});
