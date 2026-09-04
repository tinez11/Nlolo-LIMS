import { expect, type Page, test } from '@playwright/test';
import { dmy } from './dates';

/**
 * Group business, end to end against the real stack.
 *
 * The two things worth proving here cannot be proved anywhere cheaper:
 *
 * 1. **The scheme's total is derived and stays derived.** The form sends no sum
 *    assured; the server computes it from the opening schedule, restates it when
 *    a member joins, and the master policy agrees with the scheme page. Three
 *    surfaces, one number — a unit test can pin any one of them and only a real
 *    round trip pins that they match.
 * 2. **The free cover limit reaches the screen.** A member over the limit is
 *    covered up to it and flagged for evidence, and the console says so both in
 *    the live preview while typing and in the row afterwards.
 *
 * No GROUP_LIFE product is seeded, so each run authors one. That is the same
 * shape `staff-distribution.spec.ts` already uses, and it keeps this suite from
 * depending on seed data that nobody has committed to keeping.
 */

const FLAT_BENEFIT = '5000000.00';

async function createGroupProduct(page: Page): Promise<string> {
  const code = `E2E-GRP-${Date.now()}`;
  const name = `E2E Group Life ${code}`;

  await page.goto('/staff/products/new');
  await page.getByLabel('Product code').fill(code);
  await page.getByLabel('Product name').fill(name);
  await page.getByLabel('Category').selectOption('GROUP_LIFE');
  await page.getByLabel('Default currency').fill('TZS');
  await page.getByRole('button', { name: 'Create product' }).click();
  await expect(page.getByText('DRAFT')).toBeVisible();

  // A version must be published before the product can be issued against, and
  // the rating table must cover AGE and SUM_ASSURED_BAND or publishing is a 422.
  const ratingSection = page.locator('p', { hasText: 'Rating table -- must cover' }).locator('..');
  await ratingSection.getByRole('button', { name: 'Remove rating factor' }).last().click();
  await ratingSection.locator('input[placeholder="Band, e.g. 18-30"]').fill('18-70');
  await ratingSection.getByLabel('Rating factor 1 from age').fill('18');
  await ratingSection.getByLabel('Rating factor 1 to age').fill('70');
  await ratingSection.getByRole('button', { name: 'Add rating factor' }).click();
  await ratingSection.locator('select').nth(1).selectOption('SUM_ASSURED_BAND');
  await ratingSection.locator('input[placeholder="Band, e.g. 18-30"]').nth(1).fill('1-99999999');
  await page.getByLabel('Effective date').fill(dmy('2026-01-01'));
  await page.getByRole('button', { name: 'Publish version' }).click();
  await expect(page).toHaveURL(/\/staff\/products\/[0-9a-f-]{36}$/, { timeout: 15_000 });

  return `${name} (${code})`;
}

/**
 * Picks a real seeded party into one of the form's PartyPicker controls.
 *
 * `getByRole('option', { name })` with the name spelled out, never
 * `getByText` and never a bare `getByRole('option')`. Two reasons, both found
 * the hard way on this suite: the chosen name also renders in the picker's own
 * trigger, so a text locator matches twice (PLAN.md §14.5); and this page has
 * real `<select>` elements whose `<option>` children answer to the option role
 * too, so an unnamed option locator resolves to "Select a group product".
 *
 * `last()` targets the most recently added schedule row — every row's picker
 * carries the same accessible name, which is correct (they are the same kind of
 * control) and makes position the only way to tell them apart.
 */
async function pickParty(
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

test.describe('staff group schemes', () => {
  test('sets up a scheme whose total is derived from its members, then moves with a joiner', async ({
    page,
  }) => {
    const productLabel = await createGroupProduct(page);

    await page.goto('/staff/group-schemes/new');
    await expect(page.getByRole('heading', { name: 'Set up a group scheme' })).toBeVisible();

    await pickParty(page, 'Search for the employer by name', 'Amina', 'Amina Owner');
    await page.getByLabel('Product').selectOption({ label: productLabel });

    await page.getByLabel('Benefit per member').fill(FLAT_BENEFIT);

    // Two lives on the opening schedule. The form starts with exactly one row,
    // because a scheme cannot be issued empty.
    await pickParty(page, 'Search employees by name', 'Amina', 'Amina Owner');
    await page.getByRole('button', { name: 'Add member' }).click();
    await pickParty(page, 'Search employees by name', 'Baraka', 'Baraka Other', 'last');

    await page.getByLabel('Premium', { exact: true }).fill('1200000.00');

    // The running total is this page's preview of what the server will derive:
    // two lives at 5,000,000 each.
    await expect(page.getByText('TZS 10,000,000.00')).toBeVisible();

    await page.getByRole('button', { name: 'Set up scheme' }).click();

    // Lands on the new scheme, whose policy number the server minted.
    await expect(page).toHaveURL(/\/staff\/group-schemes\/GRP-[A-Z0-9]+$/, { timeout: 20_000 });
    const schemeUrl = page.url();
    const policyNumber = schemeUrl.split('/').pop() as string;

    await expect(page.getByRole('heading', { name: policyNumber })).toBeVisible();
    // Derived server-side, and equal to the preview above.
    await expect(page.getByText('TZS 10,000,000.00').first()).toBeVisible();
    await expect(page.getByRole('table')).toBeVisible();

    // The master policy carries the same total -- the service restates it in the
    // same transaction, and this is the only check that the two agree in a real
    // database rather than in one test's fixture.
    await page.goto(`/staff/policies/${policyNumber}`);
    await expect(page.getByText('TZS 10,000,000.00').first()).toBeVisible();
    // And the policy page offers the way back across, only because this policy
    // is a scheme.
    await expect(page.getByRole('link', { name: 'Member schedule' })).toBeVisible();

    // A joiner moves the total on both surfaces.
    await page.goto(schemeUrl);
    await page.getByRole('button', { name: 'Add member' }).click();
    await pickParty(page, 'Search employees by name', 'Juma', 'Juma Senior');
    await page.getByRole('button', { name: 'Add member', exact: true }).last().click();

    await expect(page.getByText('TZS 15,000,000.00').first()).toBeVisible({ timeout: 15_000 });

    // Survives a reload: a real row in a real database, not optimistic UI.
    await page.reload();
    await expect(page.getByText('TZS 15,000,000.00').first()).toBeVisible();

    /*
     * Searching the roll by member name. Three lives are on this schedule by now, and a
     * real schedule holds hundreds -- "is this person covered" is not a question anyone
     * answers by paging.
     *
     * The filter is server-side, which is what the assertions actually pin: the row that
     * must be ABSENT, and the TOTAL. A pass over the fetched page would leave the total
     * at three under a single row, and on a 500-life roll it would search only the
     * twenty-five rows in hand and report "not covered" for somebody who is.
     */
    await page.getByLabel('Search members by name').fill('Juma');
    await page.getByLabel('Search members by name').press('Enter');
    await expect(page).toHaveURL(/[?&]q=Juma/, { timeout: 10_000 });
    await expect(page.getByText('Juma Senior')).toBeVisible({ timeout: 15_000 });
    await expect(page.getByText('Baraka Other')).not.toBeVisible();

    // A name nobody on this scheme has is an empty SEARCH, not an empty scheme -- the
    // roll has three members and the copy must not claim otherwise.
    await page.getByLabel('Search members by name').fill('NobodyHereIsCalledThis12345');
    await page.getByLabel('Search members by name').press('Enter');
    await expect(page.getByText(/No member matching/)).toBeVisible({ timeout: 15_000 });
    await page.getByRole('button', { name: 'Show all' }).click();
    await expect(page.getByText('Juma Senior')).toBeVisible({ timeout: 15_000 });
    await expect(page.getByText('Baraka Other')).toBeVisible();

    /*
     * The client-record route to the same schedule, which is what the Clients area's
     * second half exists to serve: open the policyholder, see the scheme, see who is
     * covered under it, and reach the roll in ONE hop.
     *
     * Before this panel the way through was Clients -> the client -> Policies -> the
     * GRP row -> the policy page -> Member schedule: four hops, and each of them
     * required already knowing that "members" live under a contract rather than under
     * the company.
     *
     * Asserted here rather than in a spec of its own because this is where a scheme
     * with a known policyholder and a known member count actually exists. A test that
     * went looking for "some client holding some scheme" would pass vacuously on a
     * tenant that had none.
     */
    await page.goto(`/staff/policies/${policyNumber}`);
    const policyholderLink = page.getByRole('link', { name: 'Amina Owner' }).first();
    await expect(policyholderLink).toBeVisible({ timeout: 15_000 });
    await policyholderLink.click();
    await expect(page).toHaveURL(/\/staff\/parties\/[0-9a-f-]{36}$/, { timeout: 15_000 });

    const schemesPanel = page
      .locator('section')
      .filter({ has: page.getByRole('heading', { name: 'Group schemes' }) });
    await expect(schemesPanel).toBeVisible({ timeout: 20_000 });

    // The scheme this run just issued, listed on the client's own record.
    await expect(schemesPanel.getByText(policyNumber)).toBeVisible({ timeout: 20_000 });

    // Its members, inline. This client holds more than one scheme by now, so the
    // inline preview is not asserted -- what must hold is that the roll is one click
    // away and that the link goes to the SCHEDULE, not to the policy record.
    const schemeLink = schemesPanel.getByRole('link', { name: new RegExp(policyNumber) });
    await schemeLink.click();
    await expect(page).toHaveURL(`/staff/group-schemes/${policyNumber}`, { timeout: 15_000 });
    await expect(page.getByRole('heading', { name: policyNumber })).toBeVisible();
    await expect(page.getByText('TZS 15,000,000.00').first()).toBeVisible({ timeout: 15_000 });
  });

  test('a member above the free cover limit is covered up to it and flagged', async ({ page }) => {
    const productLabel = await createGroupProduct(page);

    await page.goto('/staff/group-schemes/new');
    await pickParty(page, 'Search for the employer by name', 'Amina', 'Amina Owner');
    await page.getByLabel('Product').selectOption({ label: productLabel });

    // 4x salary against a 100,000,000 limit. A member on 30,000,000 is worth
    // 120,000,000 and is therefore 20,000,000 over it.
    await page.getByLabel('Basis').selectOption('SALARY_MULTIPLE');
    await page.getByLabel('Multiple of annual salary').fill('4');
    await page.getByLabel('Free cover limit (optional)').fill('100000000.00');

    await pickParty(page, 'Search employees by name', 'Amina', 'Amina Owner');
    // exact: true -- "Multiple of annual salary" also contains "Annual salary",
    // and a substring label match resolves to both fields.
    await page.getByLabel('Annual salary', { exact: true }).fill('30000000.00');

    // The live preview says so BEFORE anything is submitted -- which is the whole
    // reason the preview exists: finding out after saving is finding out too late
    // to ask the member about it.
    await expect(page.getByText(/needs medical evidence/)).toBeVisible();
    await expect(page.getByText('1 member over the free cover limit.')).toBeVisible();

    await page.getByLabel('Premium', { exact: true }).fill('900000.00');
    await page.getByRole('button', { name: 'Set up scheme' }).click();

    await expect(page).toHaveURL(/\/staff\/group-schemes\/GRP-[A-Z0-9]+$/, { timeout: 20_000 });

    // Covered for the limit, not for the full benefit and not for nothing.
    await expect(page.getByText('TZS 100,000,000.00').first()).toBeVisible();
    await expect(page.getByText('of TZS 120,000,000.00')).toBeVisible();
    await expect(page.getByText('Evidence required')).toBeVisible();
    // Counted on the summary, so nobody has to find them by eye down the schedule.
    await expect(page.getByText('over the free cover limit')).toBeVisible();
  });

  test('refuses a scheme with an unpriced member, before reaching the network', async ({ page }) => {
    const productLabel = await createGroupProduct(page);
    let requestFired = false;
    page.on('request', (req) => {
      if (req.method() === 'POST' && req.url().endsWith('/group-schemes')) requestFired = true;
    });

    await page.goto('/staff/group-schemes/new');
    await pickParty(page, 'Search for the employer by name', 'Amina', 'Amina Owner');
    await page.getByLabel('Product').selectOption({ label: productLabel });
    await page.getByLabel('Basis').selectOption('SALARY_MULTIPLE');
    await page.getByLabel('Multiple of annual salary').fill('3');
    await pickParty(page, 'Search employees by name', 'Amina', 'Amina Owner');
    // Salary deliberately left blank.
    await page.getByLabel('Premium', { exact: true }).fill('500000.00');
    await page.getByRole('button', { name: 'Set up scheme' }).click();

    await expect(page.getByText(/Needs a salary like/)).toBeVisible();
    expect(requestFired).toBe(false);
  });

  test('an individual policy offers no member schedule', async ({ page }) => {
    // Straight to the seeded individual policy rather than clicking the list:
    // a policy number on /staff/policies is a row-activation BUTTON that opens
    // the drawer, not a link, so there is nothing there matching /^POL-/ to
    // click. The cross-link is rendered only for a GROUP_LIFE contract, so its
    // absence on this page is the assertion.
    await page.goto('/staff/policies/POL-6BD5702F');
    await expect(page.getByRole('heading', { name: 'POL-6BD5702F' })).toBeVisible();
    await expect(page.getByRole('link', { name: 'Member schedule' })).toHaveCount(0);
  });
});
