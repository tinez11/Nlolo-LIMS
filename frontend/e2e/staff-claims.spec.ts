import { expect, test, type Page } from '@playwright/test';
import { issueRealPolicy } from './policies';
import { dmy, todayIso } from './dates';
import { fillPolicyNumberManually } from './guards';
import { caseAwaitingManualIssue, selectUnderwritingCase } from './underwriting';

/**
 * Claims e2e coverage against the real stack.
 *
 * This file used to open by explaining that a SUCCESSFUL claim registration was unreachable
 * here: `ClaimsApiImpl.registerClaim` requires the policy to be in force, and "the only
 * seeded policy, POL-6BD5702F" was SURRENDERED. It called that a data limitation rather than
 * an architectural one, and it was right on both counts -- the limitation has simply gone.
 * A spec can issue its own ACTIVE policy, so registration succeeds and these tests own the
 * claims they assert on.
 *
 * That matters beyond tidiness. Every fixture here was pinned to ids minted per seed run --
 * POL-6BD5702F and a literal CLAIM_ID -- so when the volumes were last reset they stopped
 * existing and could never be recreated. Three tests failed for reasons unrelated to claims,
 * and one passed for the wrong reason: it asserted an error banner did not resurface, and the
 * banner it was clearing was a 404 rather than the 422 it claimed to be about.
 *
 * Still deliberately out of scope: assessment, settlement decision and reopen are covered in
 * staff-claims-adjudication.spec.ts, not here.
 */

/**
 * A freshly issued policy with a real DEATH claim registered against it.
 *
 * Registration SUCCEEDS here, which this file's header long said was unreachable: the only
 * seeded policy was SURRENDERED, so `registerClaim`'s in-force rule refused every attempt.
 * That was a data limitation and it is gone -- a spec can issue its own ACTIVE policy now,
 * so the success path is reachable and these tests no longer have to hunt for a claim
 * somebody else left behind.
 */
async function policyWithRealClaim(page: Page): Promise<string> {
  const policyNumber = await issueRealPolicy(page, 'E2E claims-list fixture');

  await page.goto('/staff/claims/new');
  await fillPolicyNumberManually(page, policyNumber);
  await page.getByRole('button', { name: 'Search for the claimant by name' }).click();
  await page.getByPlaceholder('Type a name to search').fill('Amina');
  await page.getByText('Amina Owner').click();
  // Today, not a fixed date: the policy above is issued today and cover starts on issue, so a death
  // on 2026-08-01 is correctly refused as "not on risk" -- which this success path is not about.
  await page.getByLabel('Date of event').fill(dmy(todayIso()));
  await page.getByLabel('Cause of death').fill('Natural causes');
  await page.getByLabel('Place of death').fill('Dar es Salaam');
  await page.getByLabel('Date of death').fill(dmy(todayIso()));
  await page.getByLabel('Attending physician').fill('Dr. E2E Claims Fixture');
  await page.getByRole('button', { name: 'Register claim' }).click();
  await expect(page).toHaveURL(/\/staff\/claims\/[0-9a-f-]{36}$/, { timeout: 15_000 });

  return policyNumber;
}

/**
 * An INDIVIDUAL policy that was not on risk on 2026-08-01, the date the claim below names.
 *
 * Issued fresh rather than found. This used to take the first SURRENDERED row in the register and
 * assume it was an individual policy -- but a group or credit-life scheme whose last member leaves
 * is closed as SURRENDERED too, and the register has no product-type column or filter to tell them
 * apart. Whenever a scheme happened to sort first, the claim form showed its member picker, the
 * client refused with "Choose which member this claim is for", and the server's refusal this test
 * exists to prove was never reached.
 *
 * The commencement date is set explicitly, AFTER the death. `issueRealPolicy` leaves it blank,
 * and a policy with no commencement date has no lower bound on its cover (`Policy.wasOnRiskOn`):
 * it is on risk on any day before today, which is exactly why `policyWithRealClaim` above can
 * register a death on 2026-08-01 against one. Starting cover on 2026-08-02 makes the death the day
 * before it -- the "was not on risk on 2026-08-01" the server should answer, with no dependence on
 * what else the database happens to hold.
 */
async function findNotInForcePolicy(page: Page): Promise<string> {
  const caseId = await caseAwaitingManualIssue(page);
  await page.goto('/staff/policies/new');
  await selectUnderwritingCase(page, caseId);
  await expect(page.getByText('Resolving product version…')).not.toBeVisible();
  await page.getByLabel('Sum assured').fill('2000000.00');
  await page.getByLabel('Premium', { exact: true }).fill('800.00');
  await page.getByLabel('Why is this being issued by hand?').selectOption('MIGRATION');
  await page.getByLabel('Reason for manual issue').fill('E2E fixture: claim dated before cover started');
  await page.getByLabel('Commencement date').fill(dmy('2026-08-02'));
  await page.getByRole('button', { name: 'Issue policy' }).click();
  // 60s, as the payout fixtures allow: issuance answers only after the whole AFTER_COMMIT chain.
  await expect(page).toHaveURL(/\/staff\/policies\/POL-[A-Z0-9]+$/, { timeout: 60_000 });
  return page.url().split('/').pop() as string;
}

async function firstClaimRow(page: Page) {
  const table = page.getByRole('table', { name: 'Claims' });
  const empty = page.getByText('No claims yet');
  // 20s, not the 10s default, matching every other wait in this file. The register is paged, so
  // the request is bounded -- but there are 152 claims behind it now and the first paint has to
  // wait for the fetch. It cleared 10s by 2.3s on a quiet machine and blew through it inside a
  // five-file run, which is not a margin worth defending.
  await expect(table.or(empty)).toBeVisible({ timeout: 20_000 });
  if (await empty.isVisible()) return null;
  const row = table.getByRole('button').first();
  await expect(row).toBeVisible();
  return row;
}

test.describe('staff claims', () => {
  test('lands on the claims list with the real seeded DEATH claim', async ({ page }) => {
    await page.goto('/staff/claims');
    await expect(page.getByRole('heading', { name: 'Claims' })).toBeVisible();

    const row = await firstClaimRow(page);
    expect(row).not.toBeNull();
    await expect(row!).toContainText('Death');
  });

  test('the tenant_id claim reaches the backend for a paged, real endpoint', async ({ page }) => {
    const responsePromise = page.waitForResponse(
      (r) => r.url().includes('/claims') && r.request().method() === 'GET' && !r.url().includes('/evidence'),
      { timeout: 30_000 },
    );
    await page.goto('/staff/claims');
    const response = await responsePromise;
    expect(response.status()).toBe(200);
  });

  test('opens a row preview showing real DEATH-claim details, then navigates to full detail', async ({
    page,
  }) => {
    // Its own claim, on its own policy. This searched for POL-6BD5702F and asserted the
    // seeder's exact wording ("Dr. Juma"), which pinned it to a policy and a claim that no
    // longer exist -- and could not be recreated, since both ids are minted per run.
    test.slow();
    const policyNumber = await policyWithRealClaim(page);

    await page.goto('/staff/claims');
    // Searched by policy number rather than taking the first row: the list is
    // newest-created-first and other specs' fixtures create newer claims ahead of this one.
    await page.getByPlaceholder('Search by policy number').fill(policyNumber);
    await expect(page).toHaveURL(new RegExp(`q=${policyNumber}`), { timeout: 5000 });
    const row = await firstClaimRow(page);
    expect(row).not.toBeNull();
    await row!.click();

    const drawer = page.getByRole('dialog', { name: 'Death' });
    await expect(drawer).toBeVisible();
    // The exact fields policyWithRealClaim wrote, read back off a real Postgres row.
    await expect(drawer).toContainText('Natural causes');
    await expect(drawer).toContainText('Dar es Salaam');
    await expect(drawer).toContainText('Dr. E2E Claims Fixture');

    // No mutating action lives in the drawer -- this slice never built one, but
    // the invariant is worth asserting the same way PolicyDrawer's is.
    await expect(drawer.getByRole('button', { name: /approve|reject|settle|reopen/i })).toHaveCount(0);

    await drawer.getByRole('link', { name: /full detail/i }).click();
    await expect(page).toHaveURL(/\/staff\/claims\/[0-9a-f-]{36}$/);
    await expect(page.getByRole('heading', { name: 'Death' })).toBeVisible();
    await expect(page.getByText('Natural causes')).toBeVisible();
  });

  test('a filter is shareable through the URL', async ({ page }) => {
    await page.goto('/staff/claims?status=REOPENED');
    await expect(page.getByRole('heading', { name: 'Claims' })).toBeVisible();
    await expect(page.getByRole('button', { name: 'Reopened', pressed: true })).toBeVisible();
    // The one seeded claim really is REOPENED, so filtering to it must not empty the table.
    await expect(page.getByText('No claims yet')).not.toBeVisible();
  });

  test('registering against a policy that is not in force genuinely 422s, client validation intact', async ({
    page,
  }) => {
    // It now issues its own policy -- an underwriting case, a second underwriter's decision and a
    // manual issue -- before the behaviour under test starts.
    test.setTimeout(180_000);
    const notInForce = await findNotInForcePolicy(page);

    await page.goto('/staff/claims/new');
    await expect(page.getByRole('heading', { name: 'Register a claim' })).toBeVisible();

    // Submitting fully blank first -- every required field's client-side message
    // should appear, and nothing should reach the network.
    let requestFired = false;
    page.on('request', (req) => {
      if (req.method() === 'POST' && req.url().endsWith('/claims')) requestFired = true;
    });
    await page.getByRole('button', { name: 'Register claim' }).click();
    await expect(page.getByText('Policy number is required')).toBeVisible();
    expect(requestFired).toBe(false);

    // Now fill a genuinely well-formed DEATH claim against the real seeded
    // policy and claimant -- passes every client rule, so this DOES reach the
    // network, and the backend's real business rule rejects it.
    await fillPolicyNumberManually(page, notInForce);
    await page.getByRole('button', { name: 'Search for the claimant by name' }).click();
    await page.getByPlaceholder('Type a name to search').fill('Amina');
    await page.getByText('Amina Owner').click();
    await page.getByLabel('Date of event').fill(dmy('2026-08-01'));
    await page.getByLabel('Cause of death').fill('Test');
    await page.getByLabel('Place of death').fill('Test');
    await page.getByLabel('Date of death').fill(dmy('2026-08-01'));
    await page.getByLabel('Attending physician').fill('Dr. Test');

    await page.getByRole('button', { name: 'Register claim' }).click();

    /*
      "not on risk on <date>", not the old "was not in force". Product step 0 made the check
      date-aware -- `Policy.wasOnRiskOn` reads the commencement, the maturity date and the lapse
      and suspension dates -- so the refusal now names the DAY it is talking about. Asserting the
      date too, because that is the whole difference: a message that just says "not in force"
      cannot distinguish a policy that never started from one that had already ended.
    */
    await expect(page.getByRole('alert')).toContainText(/was not on risk on 2026-08-01/i);
    // Still on the register page -- a rejected submission must not navigate away.
    await expect(page.getByRole('heading', { name: 'Register a claim' })).toBeVisible();
  });

  test('does not resurface a stale registration error on a fresh visit to the page', async ({
    page,
  }) => {
    // A genuinely not-in-force policy, so the error this test proves does not resurface is a
    // real 422. It used to name POL-6BD5702F, which no longer exists -- so the alert it was
    // clearing was a 404, and the test passed for the wrong reason.
    // Issuing that policy -- a case, a second underwriter's decision, a manual issue -- costs most
    // of the default 60s before the behaviour under test starts; its sibling above allows the same.
    test.setTimeout(180_000);
    const notInForce = await findNotInForcePolicy(page);

    await page.goto('/staff/claims/new');
    await fillPolicyNumberManually(page, notInForce);
    await page.getByRole('button', { name: 'Search for the claimant by name' }).click();
    await page.getByPlaceholder('Type a name to search').fill('Amina');
    await page.getByText('Amina Owner').click();
    await page.getByLabel('Date of event').fill(dmy('2026-08-01'));
    await page.getByLabel('Cause of death').fill('Test');
    await page.getByLabel('Place of death').fill('Test');
    await page.getByLabel('Date of death').fill(dmy('2026-08-01'));
    await page.getByLabel('Attending physician').fill('Dr. Test');
    await page.getByRole('button', { name: 'Register claim' }).click();
    await expect(page.getByRole('alert')).toBeVisible();

    // Leave and come back -- a fresh mount of the same singleton `registering`
    // resource must not carry the previous attempt's rejection forward.
    //
    // Each goto waits for its destination to actually render before the next step.
    // Firing them back to back produced net::ERR_ABORTED when the second landed
    // while the first was still settling (the silent SSO renew redirects to
    // Keycloak, which aborts an in-flight navigation). And the absence assertion
    // needs the form on screen to mean anything: checked immediately after a goto,
    // "no alert" is satisfied by a page that has not rendered yet.
    await page.goto('/staff/claims');
    await expect(page.getByRole('heading', { name: 'Claims' })).toBeVisible({ timeout: 30_000 });

    await page.goto('/staff/claims/new');
    await expect(page.getByRole('heading', { name: 'Register a claim' })).toBeVisible({ timeout: 30_000 });
    await expect(page.getByRole('alert')).not.toBeVisible();
  });

  test('the search bar finds a real claim by its policy number', async ({ page }) => {
    test.slow();
    const policyNumber = await policyWithRealClaim(page);

    await page.goto('/staff/claims');
    await expect(page.getByRole('heading', { name: 'Claims' })).toBeVisible();
    await page.getByPlaceholder('Search by policy number').fill(policyNumber);
    await expect(page).toHaveURL(new RegExp(`q=${policyNumber}`), { timeout: 5000 });
    // Stronger than the old `getByText('DEATH')`, which would have passed on any claim in an
    // unfiltered list: the row found must be the one on THIS policy.
    await expect(page.getByRole('row').filter({ hasText: policyNumber })).toBeVisible();
  });
});
