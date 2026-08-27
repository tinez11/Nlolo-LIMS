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
    await ratingSection.locator('input[placeholder="Band, e.g. 18-30"]').fill('18-30');
    await page.getByLabel('Effective date').fill(dmy('2026-01-01'));

    await page.getByRole('button', { name: 'Publish version' }).click();
    await expect(
      page.getByText('Rating table must cover at least AGE and SUM_ASSURED_BAND'),
    ).toBeVisible();
    expect(versionsRequestFired).toBe(false);

    // Fix it: add SUM_ASSURED_BAND back, then submit for real.
    await ratingSection.getByRole('button', { name: 'Add rating factor' }).click();
    await ratingSection.locator('select').nth(1).selectOption('SUM_ASSURED_BAND');
    await ratingSection.locator('input[placeholder="Band, e.g. 18-30"]').nth(1).fill('1000000-5000000');

    await page.getByRole('button', { name: 'Publish version' }).click();

    // Real POST -> 201 -> navigation to the product's own detail page, now
    // readable through GET /products for the first time (DRAFT -> ACTIVE).
    await expect(page).toHaveURL(/\/staff\/products\/[0-9a-f-]{36}$/, { timeout: 15_000 });
    await expect(page.getByRole('heading', { name })).toBeVisible();

    // publishVersion() also refreshes loadList() -- the product shows up here
    // without a manual reload.
    await page.goto('/staff/products');
    await expect(page.getByText(name)).toBeVisible();
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
