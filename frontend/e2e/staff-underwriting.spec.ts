import { expect, test } from '@playwright/test';

/**
 * Underwriting domain e2e coverage against the real backend.
 *
 * `POST /underwriting/cases` is the ONLY entry point onto this domain that
 * exists server-side: no list/search endpoint, and unlike every other
 * fetch-by-id entity on this console, no other domain's response ever
 * re-surfaces a real case id either (verified directly: a real `GET /policies`
 * response for the seeded policyholder below carries no `underwritingCaseId`
 * field at all, confirming the wire DTO, not just the OpenAPI spec, drops it).
 * So every test here must open its own case and work from the id its own
 * response hands back -- there is no discovery path to lean on.
 *
 * `d9937444-3873-4336-9cb7-addb486f3e1b` is the same real seeded policyholder
 * used throughout staff-issue-policy.spec.ts.
 */

const REAL_PARTY_ID = 'd9937444-3873-4336-9cb7-addb486f3e1b';

test.describe('staff underwriting', () => {
  test.beforeEach(async ({ page }) => {
    await page.goto('/staff/underwriting/new');
    await expect(page.getByRole('heading', { name: 'Open an underwriting case' })).toBeVisible();
  });

  test('the real seeded product populates the picker', async ({ page }) => {
    const select = page.getByLabel('Product');
    await expect(select.locator('option', { hasText: 'Demo Term Life' })).toHaveCount(1);
  });

  test('rejects a malformed applicant party id client-side, before reaching the network', async ({
    page,
  }) => {
    let requestFired = false;
    page.on('request', (req) => {
      if (req.method() === 'POST' && req.url().endsWith('/underwriting/cases')) requestFired = true;
    });

    await page.getByLabel('Applicant party id').fill('not-a-uuid');
    await page.getByLabel('Product').selectOption({ label: 'Demo Term Life (DEMO-TERM-01)' });
    await expect(page.getByText('Resolving product version…')).not.toBeVisible();
    await page.getByLabel('Sum assured').fill('1500000.00');
    await page.getByRole('button', { name: 'Open case' }).click();

    await expect(page.getByText('Not a valid party id')).toBeVisible();
    expect(requestFired).toBe(false);
  });

  test('opens a case, decides it by submitting one assessment, and the decision survives a reload', async ({
    page,
  }) => {
    await page.getByLabel('Applicant party id').fill(REAL_PARTY_ID);
    await page.getByLabel('Product').selectOption({ label: 'Demo Term Life (DEMO-TERM-01)' });
    await expect(page.getByText('Resolving product version…')).not.toBeVisible();
    await page.getByLabel('Sum assured').fill('1500000.00');

    await page.getByRole('button', { name: 'Open case' }).click();

    // A real POST -> 201 -> navigation to the new case's own url. The case id is
    // server-generated (a UUID), so match the pattern, not a literal value.
    await expect(page).toHaveURL(/\/staff\/underwriting\/[0-9a-f-]{36}$/, { timeout: 15_000 });
    await expect(page.getByRole('heading', { name: 'Underwriting case' })).toBeVisible();
    await expect(page.getByText('None', { exact: true })).toBeVisible(); // referral status

    // decideIfPossible runs unconditionally on this one call -- submitting a
    // single assessment IS the decision, whatever it resolves to.
    await page.getByLabel('Findings').fill('E2E test assessment, standard risk');
    await page.getByLabel('Risk score (optional)').fill('10');
    await page.getByRole('button', { name: 'Submit assessment' }).click();

    await expect(page.getByRole('heading', { name: 'Decision' })).toBeVisible({ timeout: 15_000 });
    // The assessment form is gone -- there is no way to submit a second one
    // through this UI once a case is decided.
    await expect(page.getByRole('button', { name: 'Submit assessment' })).not.toBeVisible();

    // Reload from scratch -- proves this is a real Postgres row, not the
    // store's in-memory state surviving a soft navigation.
    await page.reload();
    await expect(page.getByRole('heading', { name: 'Decision' })).toBeVisible();
  });

  test('refers a case to a senior underwriter', async ({ page }) => {
    await page.getByLabel('Applicant party id').fill(REAL_PARTY_ID);
    await page.getByLabel('Product').selectOption({ label: 'Demo Term Life (DEMO-TERM-01)' });
    await expect(page.getByText('Resolving product version…')).not.toBeVisible();
    await page.getByLabel('Sum assured').fill('1500000.00');
    await page.getByRole('button', { name: 'Open case' }).click();
    await expect(page).toHaveURL(/\/staff\/underwriting\/[0-9a-f-]{36}$/, { timeout: 15_000 });

    await page.getByRole('button', { name: 'Refer to senior underwriter' }).click();

    // referralStatus flips for real -- the button (only rendered while
    // referralStatus === 'NONE') disappears once it does.
    await expect(page.getByText('Referred to senior')).toBeVisible({ timeout: 10_000 });
    await expect(page.getByRole('button', { name: 'Refer to senior underwriter' })).not.toBeVisible();
  });

  test('a second assessment racing against an already-decided case genuinely 409s, and does not resurface after a reload', async ({
    page,
    context,
  }) => {
    await page.getByLabel('Applicant party id').fill(REAL_PARTY_ID);
    await page.getByLabel('Product').selectOption({ label: 'Demo Term Life (DEMO-TERM-01)' });
    await expect(page.getByText('Resolving product version…')).not.toBeVisible();
    await page.getByLabel('Sum assured').fill('1500000.00');
    await page.getByRole('button', { name: 'Open case' }).click();
    await expect(page).toHaveURL(/\/staff\/underwriting\/[0-9a-f-]{36}$/, { timeout: 15_000 });
    const caseUrl = page.url();

    // A second tab loads the SAME not-yet-decided case, capturing the
    // assessment form in memory before tab A decides it -- the real way this
    // 409 happens (two staff racing the same case), not a fabricated request.
    const page2 = await context.newPage();
    await page2.goto(caseUrl);
    await expect(page2.getByRole('button', { name: 'Submit assessment' })).toBeVisible();

    await page.getByLabel('Findings').fill('Tab A decides first');
    await page.getByRole('button', { name: 'Submit assessment' }).click();
    await expect(page.getByRole('heading', { name: 'Decision' })).toBeVisible({ timeout: 15_000 });

    await page2.getByLabel('Findings').fill('Tab B arrives too late');
    await page2.getByRole('button', { name: 'Submit assessment' }).click();
    await expect(page2.getByRole('alert')).toBeVisible({ timeout: 15_000 });

    // `submittingAssessment` is keyed by case id and outlives this form's own
    // mount/unmount -- reset-on-mount must clear it, or the 409 above would
    // resurface on the very next visit to this same case.
    await page2.reload();
    await expect(page2.getByRole('alert')).not.toBeVisible();
    await expect(page2.getByRole('heading', { name: 'Decision' })).toBeVisible();

    await page2.close();
  });

  test('does not resurface a stale open-case rejection on a fresh visit to the page', async ({
    page,
  }) => {
    // Trigger a real rejection: a well-formed but nonexistent party id.
    // partyApi.getParty(...) genuinely checks existence, so this 404s for real.
    await page.getByLabel('Applicant party id').fill('00000000-0000-4000-8000-000000000000');
    await page.getByLabel('Product').selectOption({ label: 'Demo Term Life (DEMO-TERM-01)' });
    await expect(page.getByText('Resolving product version…')).not.toBeVisible();
    await page.getByLabel('Sum assured').fill('1500000.00');
    await page.getByRole('button', { name: 'Open case' }).click();

    await expect(page.getByRole('alert')).toBeVisible({ timeout: 15_000 });

    // Wait for the first navigation to genuinely settle before firing the
    // second -- back-to-back goto() calls with no intervening wait can race
    // the app's own in-flight requests from the first page (seen previously
    // on staff-issue-policy.spec.ts).
    await page.goto('/staff/policies');
    await expect(page.getByRole('heading', { name: 'Policies' })).toBeVisible();
    await page.goto('/staff/underwriting/new');
    await expect(page.getByRole('alert')).not.toBeVisible();
  });
});
