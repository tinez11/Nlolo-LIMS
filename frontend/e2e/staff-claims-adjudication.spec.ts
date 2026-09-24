import { expect, type Page, test } from '@playwright/test';
import { caseAwaitingManualIssue, selectUnderwritingCase } from './underwriting';
import { dmy } from './dates';
import { fillPolicyNumberManually } from './guards';

/**
 * Claims adjudication e2e coverage against the real backend.
 *
 * `CLAIMS_ASSESSOR` and `CLAIMS_MANAGER` are separate, single-role seeded staff
 * users (`backend/keycloak/staff-realm.json`) -- `staff.underwriter`, this
 * project's default identity, carries neither. So this file is the one place on
 * this console that logs in as more than one real identity within a single
 * test, via `browser.newContext({ storageState })` for the two extra
 * identities minted by auth-assessor.setup.ts / auth-manager.setup.ts, while
 * `page` stays on the default `staff.underwriter` identity (REALM_STAFF is
 * broad enough to issue a policy and register a claim, neither of which needs
 * a claims-specific role).
 *
 * NOT covered here: `ClaimsApiImpl.decideSettlement`'s separation-of-duties
 * check (the same PERSON who assessed a claim cannot also decide it). Every
 * seeded staff user holds exactly one role, so there is no real identity that
 * could even reach `decideSettlement` after assessing -- the CLAIMS_MANAGER
 * role gate alone already blocks staff.assessor from getting there. Exercising
 * the same-person branch specifically would need a seeded user holding BOTH
 * roles, which does not exist and is not worth adding realm data for one rule
 * already covered by the backend's own tests -- see the module doc, not a gap.
 *
 * The seeded policyholder, "Amina Owner" (`d9937444-3873-4336-9cb7-addb486f3e1b`),
 * is reused throughout the session's other suites; every claim registered here
 * uses a freshly-issued policy rather than the one seeded (SURRENDERED) policy,
 * which cannot support a real claim registration at all.
 */

async function issueRealPolicy(page: Page): Promise<string> {
  // Manual issue names a real, unissued case now. The policyholder and product
  // come from it by prefill, so this no longer picks them by hand. The sum assured
  // still does: the case view @JsonIgnores it, so the console cannot read it.
  const caseId = await caseAwaitingManualIssue(page);
  await page.goto('/staff/policies/new');
  await selectUnderwritingCase(page, caseId);
  await expect(page.getByText('Resolving product version…')).not.toBeVisible();
  await page.getByLabel('Sum assured').fill('2000000.00');
  await page.getByLabel('Premium', { exact: true }).fill('800.00');
  // Required, and MIGRATION so the policy is in force rather than an offer.
  await page.getByLabel('Why is this being issued by hand?').selectOption('MIGRATION');
  await page.getByLabel('Reason for manual issue').fill('E2E adjudication fixture');
  await page.getByRole('button', { name: 'Issue policy' }).click();
  await expect(page).toHaveURL(/\/staff\/policies\/POL-[A-Z0-9]+$/, { timeout: 15_000 });
  return page.url().split('/').pop() as string;
}

async function registerRealDeathClaim(page: Page, policyNumber: string): Promise<string> {
  await page.goto('/staff/claims/new');
  await fillPolicyNumberManually(page, policyNumber);
  await page.getByRole('button', { name: 'Search for the claimant by name' }).click();
  await page.getByPlaceholder('Type a name to search').fill('Amina');
  await page.getByText('Amina Owner').click();
  await page.getByLabel('Date of event').fill(dmy('2026-08-01'));
  // DEATH is the default selection, but select it explicitly so this survives a
  // reorder of CLAIM_TYPES.
  await page.getByLabel('Claim type').selectOption('DEATH');
  await page.getByLabel('Cause of death').fill('Natural causes');
  await page.getByLabel('Place of death').fill('Dar es Salaam');
  await page.getByLabel('Date of death').fill(dmy('2026-08-01'));
  await page.getByLabel('Attending physician').fill('Dr. E2E Test');
  await page.getByRole('button', { name: 'Register claim' }).click();
  await expect(page).toHaveURL(/\/staff\/claims\/[0-9a-f-]{36}$/, { timeout: 15_000 });
  return page.url().split('/').pop() as string;
}

test.describe('staff claims adjudication', () => {
  test('a staff.underwriter session sees no adjudication panels on any claim', async ({ page }) => {
    // The real seeded DEATH claim -- read-only for this test, never mutated.
    // Rows open a preview drawer ("drawer previews, page acts"); "Full detail"
    // is the drawer's own link to the actual detail page these panels live on.
    // The activation control is a <button> in the row's first cell only, not the
    // whole <tr> -- DataTable's own comment on why (a screen-reader-reachable
    // action, not a click handler nothing can find).
    await page.goto('/staff/claims');
    await page.getByRole('row').filter({ hasText: 'DEATH' }).first().getByRole('button').click();
    await page.getByRole('link', { name: 'Full detail' }).click();
    await expect(page.getByRole('heading', { name: 'DEATH' })).toBeVisible();

    await expect(page.getByRole('heading', { name: 'Submit an assessment' })).not.toBeVisible();
    await expect(page.getByRole('heading', { name: 'Decide settlement' })).not.toBeVisible();
    await expect(page.getByRole('heading', { name: 'Reopen' })).not.toBeVisible();
  });

  test('a fresh claim moves REGISTERED -> UNDER_ASSESSMENT -> SETTLED via two distinct real staff identities', async ({
    page,
    browser,
  }) => {
    // The slowest test in the suite, and it had no headroom. It drives a full policy issuance, a
    // claim registration, an assessment and a settlement across THREE authenticated browser
    // contexts -- roughly 52 seconds of genuine work against a 60-second default. It took 54.8s
    // before the notifications project and 51.4s after, so nothing made it slow: it has always
    // sat within a few seconds of its limit, and tipped over the first time the full suite ran it
    // under contention.
    //
    // test.slow() triples the budget, which nine other specs here already do for less. Trimming
    // what it covers would buy seconds by proving less about the one path where money leaves the
    // platform.
    test.slow();
    const policyNumber = await issueRealPolicy(page);
    const claimId = await registerRealDeathClaim(page, policyNumber);

    const assessorContext = await browser.newContext({ storageState: 'e2e/.auth/staff-assessor.json' });
    const assessorPage = await assessorContext.newPage();
    await assessorPage.goto(`/staff/claims/${claimId}`);

    // Role gating, from the assessor's own side: this identity can assess, not decide.
    await expect(assessorPage.getByRole('heading', { name: 'Submit an assessment' })).toBeVisible();
    await expect(assessorPage.getByRole('heading', { name: 'Decide settlement' })).not.toBeVisible();

    // The recommendation opens at the claim's cover -- the 2,000,000 sum assured, read from
    // GET /claims/{id}/claimable-cover -- and says so, rather than a blank field with an
    // invented placeholder.
    await expect(assessorPage.getByLabel('Recommended amount')).toHaveValue('2000000.00');
    await expect(assessorPage.getByText('Covered for TZS 2,000,000.00 — the most this claim can pay'))
      .toBeVisible();

    // Recommend LESS than the cover, so every figure below is distinguishable: if the manager's
    // form opened at the cover instead of this recommendation, or the record showed the sum
    // assured instead of the decided amount, 1,500,000 is what would be missing.
    await assessorPage.getByLabel('Findings').fill('Standard risk, no adverse findings');
    await assessorPage.getByLabel('Recommended amount').fill('1500000.00');
    await assessorPage.getByRole('button', { name: 'Submit assessment' }).click();
    await expect(assessorPage.getByText('Under assessment')).toBeVisible({ timeout: 15_000 });
    await assessorContext.close();

    const managerContext = await browser.newContext({ storageState: 'e2e/.auth/staff-manager.json' });
    const managerPage = await managerContext.newPage();
    await managerPage.goto(`/staff/claims/${claimId}`);

    // Role gating, from the manager's own side: this identity can decide, not assess.
    await expect(managerPage.getByRole('heading', { name: 'Decide settlement' })).toBeVisible();
    await expect(managerPage.getByRole('heading', { name: 'Submit an assessment' })).not.toBeVisible();

    // Separation of duties means this manager never saw the assessment -- so it is shown to them,
    // findings and all, and the amount they are asked to approve starts from it.
    await expect(managerPage.getByText('TZS 1,500,000.00 recommended')).toBeVisible();
    await expect(managerPage.getByText('Standard risk, no adverse findings')).toBeVisible();
    // Named as a person, from the assessor's own token -- this used to print their Keycloak
    // subject, a uuid, in both places.
    await expect(managerPage.getByText(/^Daudi Assessor · /)).toBeVisible();
    await expect(managerPage.getByText(/recommended by Daudi Assessor\./)).toBeVisible();

    // Approve is the default branch, prefilled with the recommendation -- NOT the 2,000,000 cover
    // -- and nothing is typed into it: the decision stands on the assessor's figure.
    await expect(managerPage.getByLabel('Approved amount')).toHaveValue('1500000.00');
    await expect(managerPage.getByText(/TZS 500,000\.00 less than this claim is covered for/))
      .toBeVisible();
    await managerPage.getByLabel('Payee reference').fill('MOBILE-MONEY-E2E-1');
    await managerPage.getByRole('button', { name: 'Approve claim' }).click();

    // Approving moves money AND closes the policy permanently -- Claim.reopen()'s
    // own Javadoc records that reopening never reverses the closure -- so the
    // confirmation says exactly that before the second, deliberate click.
    await expect(managerPage.getByText(/policy closes permanently/)).toBeVisible();
    await managerPage.getByRole('button', { name: 'Approve and pay' }).click();

    // decideSettlement's approve path publishes ClaimApproved AND ClaimSettlementRequested in
    // the same call, and the AFTER_COMMIT chain that follows is synchronous: payment submits the
    // disbursement to the mobile-money rail, the local mock rail answers ACCEPTED with a
    // gatewayReference, completeDisbursement fires, and claims' PaymentEventListener marks the
    // claim SETTLED -- all before this page can read the claim back.
    //
    // This assertion used to demand SETTLEMENT_REQUESTED, and it passed for two milestones for
    // the WRONG REASON: mock-mobile-money was never started, so every disbursement threw a
    // GatewayException and landed IN_DOUBT, leaving the claim stranded mid-chain. The dev
    // database still carries the evidence -- 32 IN_DOUBT instructions against 32 claims parked
    // at SETTLEMENT_REQUESTED. Bringing the rail up (it is in the documented startup sequence)
    // exposed the assertion as a test of a broken payment path.
    //
    // SETTLEMENT_REQUESTED is real, but transient here: it is what a reader sees only when the
    // rail is slow, down, or answers non-terminally. What a working stack must show is money
    // moved and the claim closed.
    await expect(managerPage.getByText('Settled', { exact: true })).toBeVisible({ timeout: 15_000 });

    // Reload from scratch -- proves this is a real Postgres row.
    await managerPage.reload();
    await expect(managerPage.getByText('Settled', { exact: true })).toBeVisible();
    // The decided amount is on the record, not just the status. Exact, because the Assessments
    // panel on the same page also names this figure ("TZS 1,500,000.00 recommended").
    await expect(managerPage.getByText('TZS 1,500,000.00', { exact: true })).toBeVisible();


    await managerContext.close();
  });

  test('rejects a claim, then reopens it -- same CLAIMS_MANAGER identity, no guard on either action', async ({
    page,
    browser,
  }) => {
    // The same story as the SETTLED journey above, and the same fix. This one issues a real
    // policy, registers a real claim and drives two further browser contexts through an
    // assessment, a rejection and a reopening -- and when it timed out, the page snapshot
    // showed "Reopened" already on screen. It ran out of clock at the last assertion rather
    // than failing one, which is a budget problem, not a behaviour problem.
    test.slow();
    const policyNumber = await issueRealPolicy(page);
    const claimId = await registerRealDeathClaim(page, policyNumber);

    const assessorContext = await browser.newContext({ storageState: 'e2e/.auth/staff-assessor.json' });
    const assessorPage = await assessorContext.newPage();
    await assessorPage.goto(`/staff/claims/${claimId}`);
    await assessorPage.getByLabel('Findings').fill('Recommend against approval');
    await assessorPage.getByLabel('Recommended amount').fill('0.01');
    await assessorPage.getByRole('button', { name: 'Submit assessment' }).click();
    await expect(assessorPage.getByText('Under assessment')).toBeVisible({ timeout: 15_000 });
    await assessorContext.close();

    const managerContext = await browser.newContext({ storageState: 'e2e/.auth/staff-manager.json' });
    const managerPage = await managerContext.newPage();
    await managerPage.goto(`/staff/claims/${claimId}`);

    await managerPage.getByRole('button', { name: 'Reject', exact: true }).click();
    await managerPage.getByLabel('Rejection reason (optional)').fill('Insufficient evidence');
    await managerPage.getByRole('button', { name: 'Reject claim' }).click();

    // Rejection IS recoverable -- a claims manager can reopen it -- and the
    // confirmation says so rather than crying wolf. That distinction is the
    // whole reason the approval warning above still means something.
    await expect(managerPage.getByText(/can reopen a rejected claim/)).toBeVisible();
    await managerPage.getByRole('button', { name: 'Record the rejection' }).click();
    await expect(managerPage.getByText('Rejected', { exact: true })).toBeVisible({ timeout: 15_000 });

    // Claim.reopen() has no separation-of-duties guard at all -- the same
    // CLAIMS_MANAGER identity that just rejected it can reopen it too.
    await expect(managerPage.getByRole('heading', { name: 'Reopen' })).toBeVisible();
    // REJECTED, not SETTLED -- the policy-closure warning must not show here.
    await expect(managerPage.getByText(/does not reverse the policy closure/)).not.toBeVisible();

    await managerPage.getByLabel('Reason').fill('New evidence submitted');
    await managerPage.getByRole('button', { name: 'Reopen claim' }).click();
    await expect(managerPage.getByText('Reopened', { exact: true })).toBeVisible({ timeout: 15_000 });

    await managerContext.close();
  });
});
