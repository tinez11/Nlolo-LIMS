import { expect, test } from '@playwright/test';
import { dmy } from './dates';

/**
 * Products domain e2e coverage against the real backend.
 *
 * A product is invisible everywhere on this platform (including `GET /products`,
 * which only ever returns `status: ACTIVE`) until a version is published --
 * there is no `GET /products/{id}` at all. So "create" and "publish" are tested
 * together as one genuine two-phase flow, the same way the UI forces it, rather
 * than as two independent unit-of-work tests.
 *
 * `DEMO-TERM-01` / "Demo Term Life" is the seeded product used by
 * staff-issue-policy.spec.ts -- already published, so it is a safe fixture for
 * a read-only assertion without depending on create/publish having run first.
 */

test.describe('staff products', () => {
  // Authoring and publishing are ADMIN-only server-side, so this suite runs as staff.admin
  // rather than the project default (staff.underwriter). The denial from the other side --
  // an underwriter is offered no way in -- is its own test below.
  test.use({ storageState: 'e2e/.auth/staff-admin.json' });

  test('lists the real seeded Demo Term Life product', async ({ page }) => {
    await page.goto('/staff/products');
    await expect(page.getByRole('heading', { name: 'Products' })).toBeVisible();
    await expect(page.getByText('Demo Term Life')).toBeVisible();
  });

  test('creates a product, blocks an incomplete rating table client-side, then publishes and lists it', async ({
    page,
  }) => {
    const code = `E2E-PUB-${Date.now()}`;
    // The code alone is unique per run, but the product NAME is what the later
    // list/detail assertions match on -- a repeat run leaves a prior run's
    // same-named product in the real DB (products are never deleted), so the
    // name must carry the same uniqueness or a strict-mode locator resolves to
    // two real rows.
    const name = `E2E Publish Test ${code}`;

    await page.goto('/staff/products/new');
    await expect(page.getByRole('heading', { name: 'New product' })).toBeVisible();

    await page.getByLabel('Product code').fill(code);
    await page.getByLabel('Product name').fill(name);
    await page.getByLabel('Default currency').fill('TZS');
    await page.getByRole('button', { name: 'Create product' }).click();

    // Step 2 only renders once the real POST /products succeeded and returned
    // a DRAFT product -- this IS the assertion that creation worked.
    await expect(page.getByText('DRAFT')).toBeVisible();

    const ratingSection = page.locator('p', { hasText: 'Rating table -- must cover' }).locator('..');

    let versionsRequestFired = false;
    page.on('request', (req) => {
      if (req.method() === 'POST' && req.url().includes('/versions')) versionsRequestFired = true;
    });

    // Remove the pre-filled SUM_ASSURED_BAND row, leaving only AGE. Fill in the
    // remaining row's required band so the ONLY failure is the coverage rule
    // itself (ProductApiImpl.publishVersion: coveredFactorTypes must contain
    // both AGE and SUM_ASSURED_BAND), mirrored client-side in publishVersionSchema.
    await ratingSection.getByRole('button', { name: 'Remove rating factor' }).last().click();
    // Selected by accessible name, like the from/to fields below. The band placeholder is
    // now shaped per factor type -- an AGE example on a SUM_ASSURED_BAND row was suggesting
    // a band that would resolve against nothing -- so a placeholder selector would break the
    // moment the row's type changes, which is exactly what this test does further down.
    await ratingSection.getByLabel('Rating factor 1 band').fill('18-30');
    // AGE is rated by range now: the band text is a label, these two are what the platform
    // resolves against. Publishing without them is a real 422.
    await ratingSection.getByLabel('Rating factor 1 from age').fill('18');
    await ratingSection.getByLabel('Rating factor 1 to age').fill('30');
    await page.getByLabel('Effective date').fill(dmy('2026-01-01'));
    // The TIRA filing that authorises this version -- required as of V12.
    await page.getByLabel('TIRA filing reference').fill('TIRA/E2E/0001');
    await page.getByLabel('TIRA approval date').fill(dmy('2026-01-15'));

    await page.getByRole('button', { name: 'Publish version' }).click();
    await expect(
      page.getByText('Rating table must cover at least AGE and SUM_ASSURED_BAND'),
    ).toBeVisible();
    expect(versionsRequestFired).toBe(false);

    // Fix it: add SUM_ASSURED_BAND back, then submit for real.
    await ratingSection.getByRole('button', { name: 'Add rating factor' }).click();
    await ratingSection.locator('select').nth(1).selectOption('SUM_ASSURED_BAND');
    await ratingSection.getByLabel('Rating factor 2 band').fill('1000000-5000000');
    // A sum assured is rated by RANGE as of product V9, exactly as an age is: the band text
    // above is a label, and these two are what the platform resolves against. Filled here
    // rather than left neutral because this is the only test on the platform that carries a
    // rating factor over the real wire -- the bounds existed on every layer but the request
    // DTO once, so a publish over HTTP dropped them and stored a row that rated nobody.
    await ratingSection.getByLabel('Rating factor 2 multiplier').fill('1.25');
    await ratingSection.getByLabel('Rating factor 2 from sum assured').fill('1000000');
    await ratingSection.getByLabel('Rating factor 2 to sum assured').fill('5000000');

    await page.getByRole('button', { name: 'Publish version' }).click();

    // Real POST -> 201 -> navigation to the product's own detail page, now
    // readable through GET /products for the first time (DRAFT -> ACTIVE).
    await expect(page).toHaveURL(/\/staff\/products\/[0-9a-f-]{36}$/, { timeout: 15_000 });
    await expect(page.getByRole('heading', { name })).toBeVisible();

    // The whole round trip, which is the only place it is ever exercised: the amounts typed
    // on the previous screen, through the request DTO, into the database, and back out onto
    // the screen an actuary reviews their own rating table on. Every layer of this had the
    // bounds except the request record once, and nothing failed -- the publish returned 201
    // and stored a band that rated nobody.
    await expect(page.getByText(/1,000,000–5,000,000/)).toBeVisible();

    // publishVersion() also refreshes loadList() -- the product shows up here
    // without a manual reload.
    await page.goto('/staff/products');
    await expect(page.getByText(name)).toBeVisible();
  });

  /**
   * The abandoned-draft path, end to end against the real API.
   *
   * Authoring is two phases and only the second flips DRAFT to ACTIVE, so walking
   * away after phase one used to leave a product that appeared in NO list on the
   * platform while the database went on holding its code. It surfaced as a 409 on
   * a later attempt -- "a product with code Education02 already exists for this
   * tenant" -- against a catalogue showing no such product. True, and impossible
   * to act on: the draft could be neither seen, finished nor removed.
   *
   * This walks the whole recovery: abandon, find it listed as unfinished, open it,
   * publish it, and watch it move from the drafts panel into the catalogue.
   */
  test('an abandoned draft is listed as unfinished, and can be opened and published from there', async ({
    page,
  }) => {
    const code = `E2E-DRAFT-${Date.now()}`;
    const name = `E2E Abandoned Draft ${code}`;

    // Phase one only, then walk away -- exactly what strands a draft.
    await page.goto('/staff/products/new');
    await page.getByLabel('Product code').fill(code);
    await page.getByLabel('Product name').fill(name);
    await page.getByLabel('Default currency').fill('TZS');
    await page.getByRole('button', { name: 'Create product' }).click();
    await expect(page.getByText('DRAFT')).toBeVisible();

    // The catalogue does not show it -- correct, and the whole reason the drafts
    // panel has to exist.
    await page.goto('/staff/products');
    await expect(page.getByRole('heading', { name: 'Products' })).toBeVisible();
    await expect(page.getByRole('link', { name })).toBeVisible();
    await expect(page.getByText(/created but not published/)).toBeVisible();

    // Open it from the panel. This is what used to render the not-found state:
    // the page resolved products from the ACTIVE list only.
    await page.getByRole('link', { name }).click();
    await expect(page).toHaveURL(/\/staff\/products\/[0-9a-f-]{36}$/);
    await expect(page.getByRole('heading', { name })).toBeVisible();

    // Finish the job the draft represents.
    await page.getByRole('button', { name: 'Publish new version' }).click();
    const ratingSection = page.locator('p', { hasText: 'Rating table -- must cover' }).locator('..');
    await ratingSection.getByLabel('Rating factor 1 band').fill('18-30');
    await ratingSection.getByLabel('Rating factor 1 from age').fill('18');
    await ratingSection.getByLabel('Rating factor 1 to age').fill('30');
    await ratingSection.getByLabel('Rating factor 2 band').fill('1-99999999');
    await page.getByLabel('Effective date').fill(dmy('2026-01-01'));
    // The TIRA filing that authorises this version -- required as of V12.
    await page.getByLabel('TIRA filing reference').fill('TIRA/E2E/0001');
    await page.getByLabel('TIRA approval date').fill(dmy('2026-01-15'));
    await page.getByRole('button', { name: 'Publish version' }).click();

    // Published: it joins the catalogue and stops being an unfinished task. Both
    // halves matter -- the store refreshes `list` and `drafts` together, and a
    // product left in the drafts panel after publishing would be a lie.
    await page.goto('/staff/products');
    await expect(page.getByText(name)).toBeVisible();
    await expect(page.getByRole('link', { name })).toHaveCount(0);
  });

  /**
   * Publishing a PRICED version, which this console could not do at all.
   *
   * The form sent no `baseRates`, so every product published here was permanently
   * unquotable -- `POST /products/{id}/quote-premium` refuses a version with no rate
   * table, and there is no endpoint to add rates afterwards. The product screen said
   * so honestly ("this version is unpriced ... a rate table has to be supplied at
   * publish time") while describing something the UI made impossible.
   *
   * This publishes a real 2x2 rate table through the form and reads the rates back
   * off the product page, which is the only proof that the cells reached the
   * database in the shape the wire wanted.
   */
  test('publishes a priced version with a real base rate table, and reads the rates back', async ({
    page,
  }) => {
    const code = `E2E-PRICED-${Date.now()}`;
    const name = `E2E Priced Product ${code}`;

    await page.goto('/staff/products/new');
    await page.getByLabel('Product code').fill(code);
    await page.getByLabel('Product name').fill(name);
    await page.getByLabel('Default currency').fill('TZS');
    await page.getByRole('button', { name: 'Create product' }).click();
    await expect(page.getByText('DRAFT')).toBeVisible();

    const ratingSection = page.locator('p', { hasText: 'Rating table -- must cover' }).locator('..');

    // One age band, added for both sexes at once, priced for smoker and non-smoker.
    // Priced BEFORE the rating table is corrected, because that order is what a person
    // does and it is where the guard has to speak: pricing the table makes the
    // pre-filled AGE multiplier a double count, and the form has to say so on the row.
    const rateSection = page.locator('p', { hasText: 'Base rates (optional)' }).locator('..');
    await rateSection.getByRole('button', { name: 'Add age band' }).click();
    for (const [row, rates] of [
      [1, { ns: '0.6200', s: '1.4880' }],
      [2, { ns: '0.8370', s: '2.0088' }],
    ] as const) {
      await rateSection.getByLabel(`Base rate ${row} from age`).fill('18');
      await rateSection.getByLabel(`Base rate ${row} to age`).fill('30');
      await rateSection.getByLabel(`Base rate ${row} non-smoker rate`).fill(rates.ns);
      await rateSection.getByLabel(`Base rate ${row} smoker rate`).fill(rates.s);
    }

    // The guard, live and without a submit. Before this it existed only in the schema's
    // object-level refinement, which zod skips once a field on the row has failed -- and
    // one always has, since the AGE row ships empty. What the user got instead was the
    // opposite instruction, "an AGE factor needs a from and to age", on a row that has
    // to be deleted.
    await expect(
      ratingSection.getByText(/Age is a key of the base rate table below/),
    ).toBeVisible();
    await expect(ratingSection.getByText(/needs a from and to age/)).toHaveCount(0);
    await expect(
      page.getByText('Rating table -- must cover at least SUM_ASSURED_BAND'),
    ).toBeVisible();

    // So the pre-filled AGE row goes, leaving SUM_ASSURED_BAND, which stays required
    // even when priced -- and the guard goes with it.
    await ratingSection.getByRole('button', { name: 'Remove rating factor' }).first().click();
    await ratingSection.getByLabel('Rating factor 1 band').fill('1-99999999');
    await ratingSection.locator('select').first().selectOption('SUM_ASSURED_BAND');
    await expect(
      ratingSection.getByText(/Age is a key of the base rate table below/),
    ).toHaveCount(0);

    await page.getByLabel('Effective date').fill(dmy('2026-01-01'));
    // The TIRA filing that authorises this version -- required as of V12.
    await page.getByLabel('TIRA filing reference').fill('TIRA/E2E/0001');
    await page.getByLabel('TIRA approval date').fill(dmy('2026-01-15'));

    // A priced version must say what ages it sells to, and the form refuses the submit until
    // it does. Asserted before filling them in, because this is the rule that stops a rate
    // table's own span from silently becoming the product's selling range -- which is how a
    // real product came to accept 18-78 while pricing no woman under 56.
    await page.getByRole('button', { name: 'Publish version' }).click();
    await expect(page.getByText(/A priced product must say what entry age it sells to/)).toBeVisible();
    await expect(page).toHaveURL(/\/staff\/products\/new$/);

    await page.getByLabel('Minimum entry age').fill('18');
    await page.getByLabel('Maximum entry age').fill('30');

    // What paying in instalments costs. A monthly payer is charged more over a year than an
    // annual one -- the annual payer's premium is investable on day one, twelve collections
    // cost more than one, and monthly business lapses part-paid.
    await page.getByLabel('Monthly loading %').fill('8');
    await page.getByLabel('Quarterly loading %').fill('3');

    await page.getByRole('button', { name: 'Publish version' }).click();

    // A real 201 and a navigation to the product's own page.
    await expect(page).toHaveURL(/\/staff\/products\/[0-9a-f-]{36}$/, { timeout: 15_000 });
    await expect(page.getByRole('heading', { name })).toBeVisible();

    // The rate table renders, which means four cells round-tripped. The unpriced
    // warning must be gone -- that copy is the whole symptom being fixed.
    await expect(page.getByRole('table', { name: 'Base rate table' })).toBeVisible();
    await expect(page.getByText(/This version is unpriced/)).toHaveCount(0);

    // And the instalment loading survived the round trip, which is the half a backend test
    // cannot see: the percentages have to reach ProductVersionSpec on the wire, come back on
    // VersionRatingView, and render. The field was missing from the request schema entirely at
    // one point, with every backend test green.
    await expect(page.getByText('Instalment loading')).toBeVisible();
    await expect(page.getByText('+ 8%')).toBeVisible();
    await expect(page.getByText('+ 3%')).toBeVisible();

    // Four decimals, so the column reads down. Both sexes present, both statuses.
    await expect(page.getByRole('cell', { name: '0.6200', exact: true })).toBeVisible();
    await expect(page.getByRole('cell', { name: '2.0088', exact: true })).toBeVisible();
    await expect(page.getByRole('cell', { name: 'Female', exact: true }).first()).toBeVisible();
    await expect(page.getByRole('cell', { name: 'Male', exact: true }).first()).toBeVisible();
    await expect(page.getByRole('cell', { name: 'Non smoker', exact: true }).first()).toBeVisible();

    // And the rail's own count, which is the honest "is this priced" signal.
    await expect(page.getByText('Base rate cells')).toBeVisible();
    await expect(page.getByText('4', { exact: true })).toBeVisible();
  });

  test('rejects a duplicate product code with a real 409, and does not resurface it on a fresh visit', async ({
    page,
  }) => {
    const code = `E2E-DUP-${Date.now()}`;

    await page.goto('/staff/products/new');
    await page.getByLabel('Product code').fill(code);
    await page.getByLabel('Product name').fill('E2E Duplicate Code Test');
    await page.getByLabel('Default currency').fill('TZS');
    await page.getByRole('button', { name: 'Create product' }).click();
    await expect(page.getByText('DRAFT')).toBeVisible();

    // Real DB unique constraint (ux_product_code on tenant_id + product_code) --
    // ProductApiImpl.createProduct catches the DataIntegrityViolationException
    // and rethrows as DuplicateProductCodeException, mapped to a real 409.
    await page.goto('/staff/products/new');
    await page.getByLabel('Product code').fill(code);
    await page.getByLabel('Product name').fill('E2E Duplicate Code Test 2');
    await page.getByLabel('Default currency').fill('TZS');
    await page.getByRole('button', { name: 'Create product' }).click();

    await expect(page.getByRole('alert')).toBeVisible({ timeout: 15_000 });
    // Step 1's form is still showing (not replaced by the DRAFT summary) --
    // `created` never advanced past null on this rejected attempt.
    await expect(page.getByLabel('Product code')).toBeVisible();

    // `creating` is a single-slot resource that outlives this component's
    // mount/unmount -- reset-on-mount must clear it, or the 409 above would
    // resurface on the very next visit to this page.
    await page.goto('/staff/policies');
    await expect(page.getByRole('heading', { name: 'Policies' })).toBeVisible();
    await page.goto('/staff/products/new');
    await expect(page.getByRole('alert')).not.toBeVisible();
  });
});
