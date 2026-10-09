import { expect, type Browser, type Page } from '@playwright/test';
import { asAdmin } from './admin';
import { dmy } from './dates';
import { decideAsSenior } from './underwriting';

/**
 * Building a real GROUP_LIFE scheme through the console, for any spec that needs one.
 *
 * Extracted from `staff-group-schemes.spec.ts` when a second spec needed the same fixture.
 * `staff-policy-lifecycle.spec.ts` used to build its group policy by authoring a GROUP_LIFE
 * product and issuing it from the manual single-life screen; `5571ca6` closed that path on
 * the server (`NotASingleLifeProductException` -- such a policy covers nobody, because it
 * has no member schedule) and the console stopped offering group products in that dropdown.
 * The test was not updated and went red. A group policy is now born one way only, and this
 * module is that way.
 */

const FLAT_BENEFIT = '5000000.00';

/**
 * Picks a real seeded party into one of the form's PartyPicker controls.
 *
 * `getByRole('option', { name })` with the name spelled out, never `getByText` and never a
 * bare `getByRole('option')`. Two reasons, both found the hard way on this suite: the chosen
 * name also renders in the picker's own trigger, so a text locator matches twice
 * (PLAN.md §14.5); and this page has real `<select>` elements whose `<option>` children
 * answer to the option role too, so an unnamed option locator resolves to "Select a group
 * product".
 *
 * `last()` targets the most recently added schedule row — every row's picker carries the
 * same accessible name, which is correct (they are the same kind of control) and makes
 * position the only way to tell them apart.
 */
export async function pickParty(
  page: Page,
  buttonName: string,
  query: string,
  optionName: string,
  which: 'first' | 'last' = 'first',
) {
  const trigger = page.getByRole('button', { name: buttonName });
  await (which === 'last' ? trigger.last() : trigger.first()).click();
  await page.getByPlaceholder('Type a name to search').fill(query);
  await page.getByRole('option', { name: optionName }).click();
}

/**
 * Authors and publishes a GROUP_LIFE product, returning the label its `<option>` carries.
 *
 * No GROUP_LIFE product is seeded, so each run authors one. That keeps these suites from
 * depending on seed data nobody has committed to keeping.
 */
export async function createGroupProduct(page: Page): Promise<string> {
  const code = `E2E-GRP-${Date.now()}`;
  const name = `E2E Group Life ${code}`;

  await page.goto('/staff/products/new');
  await page.getByLabel('Product code').fill(code);
  await page.getByLabel('Product name').fill(name);
  await page.getByLabel('Category').selectOption('GROUP_LIFE');
  await page.getByLabel('Default currency').fill('TZS');
  await page.getByRole('button', { name: 'Create product' }).click();
  await expect(page.getByText('DRAFT')).toBeVisible();

  // A version must be published before the product can be issued against, and the rating
  // table must cover AGE and SUM_ASSURED_BAND or publishing is a 422.
  const ratingSection = page.locator('p', { hasText: 'Rating table — must cover' }).locator('..');
  await ratingSection.getByRole('button', { name: 'Remove rating factor' }).last().click();
  await ratingSection.getByLabel('Rating factor 1 band').fill('18-70');
  await ratingSection.getByLabel('Rating factor 1 from age').fill('18');
  await ratingSection.getByLabel('Rating factor 1 to age').fill('70');
  await ratingSection.getByRole('button', { name: 'Add rating factor' }).click();
  await ratingSection.locator('select').nth(1).selectOption('SUM_ASSURED_BAND');
  await ratingSection.getByLabel('Rating factor 2 band').fill('1-99999999');
  await page.getByLabel('Effective date').fill(dmy('2026-01-01'));
  // The TIRA filing that authorises this version -- required as of V12.
  await page.getByLabel('TIRA filing reference').fill('TIRA/E2E/0001');
  await page.getByLabel('TIRA approval date').fill(dmy('2026-01-15'));
  await page.getByRole('button', { name: 'Publish version' }).click();
  // Publishing retires the currently-active version, so it is confirmed.
  await page.getByRole('button', { name: 'Publish and make active' }).click();
  await expect(page).toHaveURL(/\/staff\/products\/[0-9a-f-]{36}$/, { timeout: 15_000 });

  return `${name} (${code})`;
}

/**
 * Carry a proposed scheme through the pipeline and return the policy number it issued.
 *
 * <p>The form PROPOSES: it opens an underwriting case, an underwriter decides it, and the
 * decision issues the scheme as an offer. Everything downstream of a scheme existing
 * therefore has to come through here first — which is the change, not an inconvenience.
 *
 * <p>A group case carries NO engine recommendation, so there is nothing to depart from and
 * no override to approve -- asserted rather than assumed, because it is the half of the
 * design most likely to be broken by a later change to the rules engine. It is decided by
 * staff.senior only because staff.underwriter proposed and assessed it, and separation of
 * duties keeps those two from deciding it; seniority plays no part.
 *
 * <p>The issued scheme is found as the newest policy. /staff/policies orders by createdAt
 * DESC, this scheme was created seconds ago inside a serial run, and the case detail page
 * offers no link to what it produced — a real gap, and the one thing here that is a
 * workaround rather than a design.
 */
export async function acceptProposedScheme(page: Page): Promise<string> {
  await expect(page).toHaveURL(/\/staff\/underwriting\/[0-9a-f-]{36}$/, { timeout: 20_000 });

  await page.getByLabel('Findings').fill('Employer accounts and claims experience reviewed');
  await page.getByLabel('Risk score (optional)').fill('10');

  /*
   * WAIT FOR THE ASSESSMENT TO LAND before deciding, rather than clicking straight on.
   * `decide` refuses a case with no assessment ("there is nothing to decide on"), and that
   * count is read on the server: clicking the two buttons back to back races the first POST
   * against the second, and the failure it produces reads like a broken guard rather than a
   * broken test. It failed exactly once in three runs, which is the worst kind.
   */
  await Promise.all([
    page.waitForResponse(
      (r) => r.request().method() === 'POST' && r.url().endsWith('/assessments') && r.status() < 400,
    ),
    page.getByRole('button', { name: 'Submit assessment' }).click(),
  ]);
  await expect(page.getByText(/The rules engine recommends/)).toHaveCount(0);

  // Separation of duties: this identity proposed and assessed the scheme, so it is told to
  // leave the decision to someone else -- and a second underwriter makes it.
  await expect(page.getByText(/another underwriter must decide it/)).toBeVisible();
  await decideAsSenior(page, 'Accept', 'Scheme accepted');

  // The Decision PANEL, not the word "Accept" -- which also names an <option> inside the
  // decision <select>, so a text locator matched the still-open form and reported the
  // failure as "hidden" rather than as the refusal the alert was actually showing.
  await page.reload();
  await expect(page.getByRole('heading', { name: 'Decision', exact: true })).toBeVisible({
    timeout: 20_000,
  });

  await page.goto('/staff/policies');
  const number = await page
    .getByRole('button', { name: /^GRP-[A-Z0-9]+$/ })
    .first()
    .textContent();
  return (number ?? '').trim();
}

/**
 * The whole fixture in one call: a published GROUP_LIFE product, a two-life scheme proposed
 * against it, underwritten and accepted, returning the master policy number.
 *
 * Takes `browser` because the product must be authored by an ADMIN and the scheme proposed
 * by the underwriter the calling spec is signed in as — two identities, and the product half
 * is the only part that needs the second one.
 */
export async function issueGroupScheme(page: Page, browser: Browser): Promise<string> {
  const productLabel = await asAdmin(browser, createGroupProduct);

  await page.goto('/staff/group-schemes/new');
  await expect(page.getByRole('heading', { name: 'Propose a group scheme' })).toBeVisible();

  await pickParty(page, 'Search for the employer by name', 'Amina', 'Amina Owner');
  await page.getByLabel('Product').selectOption({ label: productLabel });
  await page.getByLabel('Benefit per member').fill(FLAT_BENEFIT);

  // Two lives on the opening schedule. The form starts with exactly one row, because a
  // scheme cannot be issued empty.
  await pickParty(page, 'Search employees by name', 'Amina', 'Amina Owner');
  await page.getByRole('button', { name: 'Add member' }).click();
  await pickParty(page, 'Search employees by name', 'Baraka', 'Baraka Other', 'last');

  await page.getByLabel('Premium', { exact: true }).fill('1200000.00');
  await page.getByRole('button', { name: 'Propose scheme' }).click();

  return acceptProposedScheme(page);
}
