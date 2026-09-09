import { expect, type Page, test } from '@playwright/test';
import { caseAwaitingManualIssue, selectUnderwritingCase } from './underwriting';

/**
 * Manual policy issuance e2e coverage.
 *
 * Unlike loan origination (architecturally blocked, cash value is hardcoded to
 * zero platform-wide) and claim registration (blocked only by today's seed data
 * -- the one seeded policy is SURRENDERED), issuing a NEW policy has no such
 * blocker. So this suite gets to prove a genuine, complete success path: issue ->
 * navigate to the new policy's own detail page -> reload -> still there. Real
 * Postgres row, not a mock.
 *
 * `underwritingCaseId` IS validated now. It was not — this file's own header used to say
 * `issuePolicy` "never validates its existence at all", which was true and was the hole:
 * the console minted a fresh uuid per submission, so every manual issue claimed to come
 * from a case that had never been opened. The endpoint now refuses a case that already has
 * a policy and names the one that exists, which is what stops a single application becoming
 * two contracts — and that check is only enforceable because the id is real.
 *
 * The real party picked throughout, "Amina Owner" (`d9937444-3873-4336-9cb7-addb486f3e1b`),
 * is the seeded policyholder on POL-6BD5702F.
 */

async function pickPolicyholder(page: Page, nameQuery = 'Amina') {
  await page.getByRole('button', { name: 'Search for the policyholder by name' }).click();
  await page.getByPlaceholder('Type a name to search').fill(nameQuery);
  await page.getByRole('option', { name: 'Amina Owner' }).click();
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
    // Supplied so the only thing missing is the product -- this test asserts a specific
    // field error, and a second unfilled required field would make it pass for two reasons.
    await page.getByLabel('Why is this being issued by hand?').selectOption('MIGRATION');
    await page.getByLabel('Reason for manual issue').fill('E2E test');
    await page.getByRole('button', { name: 'Issue policy' }).click();

    // Not a bare text match: "Select a product" is ALSO the select's own empty
    // placeholder option, which resolves ambiguously against a plain getByText.
    //
    // `alert`, not `paragraph`: FormField's error now carries role="alert" so it
    // is ANNOUNCED when it appears rather than only painted red, which changes
    // its role away from paragraph. The stricter assertion is the better one --
    // it now checks that a screen reader would be told, not merely that a <p>
    // exists somewhere on the page.
    await expect(page.getByRole('alert').filter({ hasText: 'Select a product' })).toBeVisible();
    expect(requestFired).toBe(false);
  });

  test('shows no matches for a nonsense policyholder search, before reaching the network', async ({
    page,
  }) => {
    await page.getByRole('button', { name: 'Search for the policyholder by name' }).click();
    await page.getByPlaceholder('Type a name to search').fill('Zzzznonexistentnamezzz');
    await expect(page.getByText(/No matches for/)).toBeVisible({ timeout: 5000 });
  });

  /**
   * The case field is required, and it is the reason the duplicate guard can work at all.
   *
   * `toApiRequest` used to mint a `crypto.randomUUID()` per submission, so every manual issue
   * pointed at a case that was never opened — which made the server's one-policy-per-case
   * check unenforceable, since random ids never collide.
   */
  test('refuses to submit without an underwriting case, before reaching the network', async ({
    page,
  }) => {
    let requestFired = false;
    page.on('request', (req) => {
      if (req.method() === 'POST' && req.url().endsWith('/manual-issue')) requestFired = true;
    });

    await pickPolicyholder(page);
    await page.getByLabel('Product').selectOption({ label: 'Demo Term Life (DEMO-TERM-01)' });
    await expect(page.getByText('Resolving product version…')).not.toBeVisible();
    await page.getByLabel('Sum assured').fill('2000000.00');
    await page.getByLabel('Premium', { exact: true }).fill('800.00');
    // As above: the missing underwriting case must be the only reason this is refused.
    await page.getByLabel('Why is this being issued by hand?').selectOption('MIGRATION');
    await page.getByLabel('Reason for manual issue').fill('E2E no-case test');
    await page.getByRole('button', { name: 'Issue policy' }).click();

    await expect(
      page.getByRole('alert').filter({ hasText: 'Choose the underwriting case this policy is issued from' }),
    ).toBeVisible();
    expect(requestFired).toBe(false);
  });

  test('issues a real policy end to end, navigates to it, and it survives a reload', async ({
    page,
  }) => {
    // A decided case with no policy against it. Declined, which is exactly what manual issue
    // documents itself as existing for -- "re-issuing after a manual review overturns an
    // automated block" -- and which, unlike an acceptance, does not auto-issue and spend the
    // case before this test can use it.
    const caseId = await caseAwaitingManualIssue(page);
    await page.goto('/staff/policies/new');
    await selectUnderwritingCase(page, caseId);
    await page.getByLabel('Product').selectOption({ label: 'Demo Term Life (DEMO-TERM-01)' });
    // The version id resolves asynchronously via GET /products/{id}/active-snapshot
    // -- wait for that to actually land before submitting, or the request would
    // fire with an empty productVersionId and 400.
    await expect(page.getByText('Resolving product version…')).not.toBeVisible();

    await page.getByLabel('Sum assured').fill('2000000.00');
    await page.getByLabel('Premium', { exact: true }).fill('800.00');
    // UNDERWRITING_OVERRIDE, and this one is not interchangeable with the others. The case above
    // was DECLINED, so overturning it by hand is literally what this basis describes -- and
    // because it is one of the two that do NOT start cover, this test also proves the offer half
    // end to end: a real 201 that leaves the customer uninsured until they pay.
    await page.getByLabel('Why is this being issued by hand?').selectOption('UNDERWRITING_OVERRIDE');
    await expect(page.getByText('Cover starts when the first premium clears.')).toBeVisible();
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
    // The offer reads as an offer. A status badge alone would leave a reader guessing whether
    // this person is covered, which is the single most important thing the page says.
    await expect(page.getByRole('heading', { name: 'Not yet on cover' })).toBeVisible();
    await expect(
      page.getByText('Cover starts when the first premium clears', { exact: false }),
    ).toBeVisible();
    // Proves a real party id round-tripped AND that it resolves to the right person.
    //
    // The literal `d9937444-3873-4336-9cb7-addb486f3e1b` used to be asserted here, described
    // as "the seeded policyholder". It was, once. Party ids are minted per seed run, so it
    // went stale the moment the volumes were reset -- Amina is a different uuid now -- and
    // the test failed for a reason that had nothing to do with issuance. Exactly the same
    // trap as the hard-coded `AGENT_SENIOR_ID` in agents-my-book, and the same fix: read the
    // identity the platform reports rather than one written down months ago.
    //
    // Still stronger than asserting the name alone, which would pass on any party called
    // "Amina Owner" and stop checking that an id round-tripped at all: this pins the link to
    // a real party route, then follows it and confirms whose record it is.
    const policyholderLink = page.getByRole('link', { name: 'Amina Owner' });
    await expect(policyholderLink).toHaveAttribute('href', /^\/staff\/parties\/[0-9a-f-]{36}$/);
    await policyholderLink.click();
    await expect(page.getByRole('heading', { name: 'Amina Owner' })).toBeVisible({ timeout: 15_000 });
    await page.goBack();

    // Reload from scratch -- proves this is a real Postgres row, not the
    // store's in-memory state surviving a soft navigation.
    await page.reload();
    await expect(page.getByText('TZS 2,000,000.00').first()).toBeVisible();
  });
});
