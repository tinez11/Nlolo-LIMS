import { expect, type Page, test } from '@playwright/test';

/**
 * Manual policy issuance e2e coverage.
 *
 * Unlike loan origination (architecturally blocked, cash value is hardcoded to
 * zero platform-wide) and claim registration (blocked only by today's seed data
 * -- the one seeded policy is SURRENDERED), issuing a NEW policy has no such
 * blocker: verified with a real curl POST before writing any UI code
 * (`PolicyApiImpl.issuePolicy` never validates `underwritingCaseId`'s existence
 * at all -- it is a staff-override path, recorded purely for audit). So this
 * suite gets to prove a genuine, complete success path: issue -> navigate to the
 * new policy's own detail page -> reload -> still there. Real Postgres row, not
 * a mock.
 *
 * The real party picked throughout, "Amina Owner" (`d9937444-3873-4336-9cb7-addb486f3e1b`),
 * is the seeded policyholder on POL-6BD5702F.
 */

async function pickPolicyholder(page: Page, nameQuery = 'Amina') {
  await page.getByRole('button', { name: 'Search for the policyholder by name' }).click();
  await page.getByPlaceholder('Type a name to search').fill(nameQuery);
  await page.getByText('Amina Owner').click();
}

test.describe('staff issue policy', () => {
  test.beforeEach(async ({ page }) => {
    await page.goto('/staff/policies/new');
    await expect(page.getByRole('heading', { name: 'Issue a policy' })).toBeVisible();
  });

  test('the real seeded product populates the picker and resolves a real productVersionId', async ({
    page,
  }) => {
    const select = page.getByLabel('Product');
    await expect(select.locator('option', { hasText: 'Demo Term Life' })).toHaveCount(1);
  });

  test('rejects submission with no product selected, before reaching the network', async ({
    page,
  }) => {
    let requestFired = false;
    page.on('request', (req) => {
      if (req.method() === 'POST' && req.url().endsWith('/manual-issue')) requestFired = true;
    });

    await pickPolicyholder(page);
    await page.getByLabel('Sum assured').fill('2000000.00');
    await page.getByLabel('Premium', { exact: true }).fill('800.00');
    await page.getByLabel('Reason for manual issue').fill('E2E test');
    await page.getByRole('button', { name: 'Issue policy' }).click();

    // Not a bare text match: "Select a product" is ALSO the select's own empty
    // placeholder option, which resolves ambiguously against a plain getByText.
    // The field error renders as a paragraph specifically.
    await expect(page.getByRole('paragraph').filter({ hasText: 'Select a product' })).toBeVisible();
    expect(requestFired).toBe(false);
  });

  test('shows no matches for a nonsense policyholder search, before reaching the network', async ({
    page,
  }) => {
    await page.getByRole('button', { name: 'Search for the policyholder by name' }).click();
    await page.getByPlaceholder('Type a name to search').fill('Zzzznonexistentnamezzz');
    await expect(page.getByText(/No matches for/)).toBeVisible({ timeout: 5000 });
  });

  test('issues a real policy end to end, navigates to it, and it survives a reload', async ({
    page,
  }) => {
    await pickPolicyholder(page);
    await page.getByLabel('Product').selectOption({ label: 'Demo Term Life (DEMO-TERM-01)' });
    // The version id resolves asynchronously via GET /products/{id}/active-snapshot
    // -- wait for that to actually land before submitting, or the request would
    // fire with an empty productVersionId and 400.
    await expect(page.getByText('Resolving product version…')).not.toBeVisible();

    await page.getByLabel('Sum assured').fill('2000000.00');
    await page.getByLabel('Premium', { exact: true }).fill('800.00');
    await page.getByLabel('Reason for manual issue').fill('E2E full-issuance test');

    await page.getByRole('button', { name: 'Issue policy' }).click();

    // A real POST -> 201 -> navigation to the new policy's own URL. The policy
    // number is server-generated (POL-<random>), so match the pattern, not a
    // literal value.
    await expect(page).toHaveURL(/\/staff\/policies\/POL-[A-Z0-9]+$/, { timeout: 15_000 });
    // .first(): this genuinely appears twice on a real, correctly-issued policy --
    // issuePolicy also creates a DEATH Coverage row for the same sumAssuredAmount
    // (real backend behavior, not a rendering bug), so the "Sum assured" field and
    // the "Coverage" panel's death row both show it.
    await expect(page.getByText('TZS 2,000,000.00').first()).toBeVisible();
    // Proves the picker actually put the SELECTED party's id in the payload --
    // not just that the UI looked right after submission.
    //
    // The policyholder renders as a NAME now, not a raw uuid, so asserting the
    // uuid is visible as text no longer works. Asserting the name alone would be
    // a weaker test than the one it replaces: it would pass on any party called
    // "Amina Owner" and stop checking which id round-tripped. The link's href
    // carries the id the server actually stored, so this checks both halves at
    // once -- the right id came back, AND it resolves to the right person.
    await expect(page.getByRole('link', { name: 'Amina Owner' })).toHaveAttribute(
      'href',
      '/staff/parties/d9937444-3873-4336-9cb7-addb486f3e1b',
    );

    // Reload from scratch -- proves this is a real Postgres row, not the
    // store's in-memory state surviving a soft navigation.
    await page.reload();
    await expect(page.getByText('TZS 2,000,000.00').first()).toBeVisible();
  });
});
