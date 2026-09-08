import { expect, test } from '@playwright/test';
import { caseAwaitingManualIssue, selectUnderwritingCase } from './underwriting';

/**
 * `GET /parties/{partyId}` has always existed; `POST .../kyc-evidence` (upload)
 * and `POST .../kyc` (decide) did not, until this staff-portal CRUD audit found
 * the gap -- `PartyApi.submitKycEvidence` has always required a real
 * `evidenceDocumentRef`, but there was no upload path anywhere on the platform
 * that could produce one for a KYC purpose. This test reaches the party by
 * drilling in from a policy's real policyholderPartyId link -- one of two
 * real discovery paths that now exist. `GET /parties`, the other one (see
 * `staff-kyc-review.spec.ts`), was added later once a party with nothing yet
 * referencing it turned out to be genuinely invisible otherwise.
 *
 * Drives TWO real transitions (reject, then verify) rather than asserting a
 * single end state: the shared fixture party used across this session's other
 * suites may already be VERIFIED from earlier work, so only a real, driven
 * change of state (not just a static value) proves the mechanism actually
 * works end to end.
 */
const MINIMAL_PNG = Buffer.from(
  'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAAAAAA6fptVAAAACklEQVR4nGMAAQAABQABDQottAAAAABJRU5ErkJggg==',
  'base64',
);

test.describe('staff party KYC verification', () => {
  test('reaches the party by drilling in from a policy, then drives a real reject-then-verify round trip', async ({
    page,
  }) => {
    // Manual issue names a real, unissued case now. The policyholder and product
    // come from it by prefill, so this no longer picks them by hand. The sum assured
    // still does: the case view @JsonIgnores it, so the console cannot read it.
    const caseId = await caseAwaitingManualIssue(page);
    await page.goto('/staff/policies/new');
    await selectUnderwritingCase(page, caseId);
    await expect(page.getByText('Resolving product version…')).not.toBeVisible();
    await page.getByLabel('Sum assured').fill('2000000.00');
    await page.getByLabel('Premium', { exact: true }).fill('800.00');
    await page.getByLabel('Reason for manual issue').fill('E2E party-KYC fixture');
    await page.getByRole('button', { name: 'Issue policy' }).click();
    await expect(page).toHaveURL(/\/staff\/policies\/POL-[A-Z0-9]+$/, { timeout: 15_000 });

    // Located by href, not by link text. The policyholder used to render as a raw
    // uuid and this clicked it by name; it now resolves to "Amina Owner" through
    // PartyName, and the href is both the stable handle and the thing this step
    // actually cares about -- that the policy drills in to the RIGHT party.
    //
    // Worth knowing why this only started failing once PartyName gained a cache:
    // before, every mount refetched, so there was always a brief frame rendering the
    // raw uuid fallback, and the by-name click won that race. The cache removed the
    // frame and turned a latent flake into a deterministic failure.
    // Matched by party ROUTE rather than by a written-down uuid. `REAL_PARTY_ID` was the
    // literal d9937444-3873-4336-9cb7-addb486f3e1b, and party ids are minted per seed run --
    // Amina is a different uuid now, so this failed for a reason with nothing to do with KYC.
    // The third instance of that trap on this branch, after the hard-coded AGENT_SENIOR_ID and
    // the same literal in staff-issue-policy.
    //
    // The by-href match still matters and is kept: clicking by name alone was already found to
    // race PartyName's cache, and the comment above explains why.
    const policyholderLink = page.locator('a[href^="/staff/parties/"]').first();
    await expect(policyholderLink).toBeVisible();
    const partyHref = await policyholderLink.getAttribute('href');
    await policyholderLink.click();
    await expect(page).toHaveURL(partyHref as string);
    await expect(page.getByRole('heading', { level: 2, name: 'KYC verification' })).toBeVisible();

    await rejectThenVerify(page);
  });
});

async function rejectThenVerify(page: import('@playwright/test').Page) {
  // Reject.
  await page.locator('input[type="file"]').setInputFiles({
    name: 'id-scan.png',
    mimeType: 'image/png',
    buffer: MINIMAL_PNG,
  });
  await expect(page.getByText(/Evidence uploaded:/)).toBeVisible({ timeout: 15_000 });
  await page.getByRole('button', { name: 'Reject' }).click();
  // A KYC decision gates whether this client can hold a policy, so it takes a
  // second click -- but it is genuinely re-decidable (Party.updateKycStatus
  // assigns with no guard on the previous status), and the copy says so rather
  // than claiming an irreversibility the backend does not have.
  await expect(page.getByText(/can be changed later/)).toBeVisible();
  await page.getByRole('button', { name: 'Reject identity' }).click();
  await expect(page.getByText('Rejected')).toBeVisible({ timeout: 15_000 });

  // Then verify -- a fresh upload, since the same documentRef could in
  // principle be reused, but a real staff workflow re-checks evidence per
  // decision, and the dropzone always starts empty again after a decision.
  await page.locator('input[type="file"]').setInputFiles({
    name: 'id-scan-2.png',
    mimeType: 'image/png',
    buffer: MINIMAL_PNG,
  });
  await expect(page.getByText(/Evidence uploaded:/)).toBeVisible({ timeout: 15_000 });
  await page.getByRole('button', { name: 'Verify' }).click();
  await page.getByRole('button', { name: 'Verify identity' }).click();
  await expect(page.getByText('Verified')).toBeVisible({ timeout: 15_000 });
}
