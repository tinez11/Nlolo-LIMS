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
    // SITS ON THE 60s BOUNDARY AND FLIPS EITHER WAY, EVEN RUN ALONE -- measured 2026-09-28:
    // two consecutive solo runs, one pass and one timeout, with nothing else on the machine.
    // It opens an underwriting case, issues a policy through manual issue, drills into the
    // party, then pushes TWO separate PNGs through MinIO because a reject and a verify each
    // re-check evidence the way a real workflow does.
    //
    // test.slow() triples the budget. Ten other tests in this suite already take it for less
    // work, and a test that decides its own outcome on machine load is worse than a slow one:
    // it teaches whoever reads the report to discount red, which is how a real failure gets
    // waved through.
    test.slow();

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
    await page.getByRole('tab', { name: 'KYC & documents', exact: true }).click();
    await expect(page.getByRole('heading', { level: 2, name: 'KYC & documents' })).toBeVisible();

    await rejectThenVerify(page);
  });
});

/*
 * Written for the KYC panel as it is since e4fac848 and 1c037d1d: evidence is LISTED and opened beside the decision
 * (there is no "Evidence uploaded:" line any more), and a decided client puts Verify/Reject away behind "Change this
 * decision". The fixture client is shared across runs, so it may arrive already decided either way.
 */
type Page = import('@playwright/test').Page;

/** Uploads a PNG under a name no earlier run used, and waits for it to be listed -- and so opened -- in the panel. */
async function uploadEvidence(page: Page, stem: string) {
  const name = `${stem}-${Date.now()}.png`;
  await page.locator('input[type="file"]').setInputFiles({ name, mimeType: 'image/png', buffer: MINIMAL_PNG });
  await expect(page.getByText(name).first()).toBeVisible({ timeout: 15_000 });
}

/** A decided client offers the act behind one deliberate click; an undecided one offers it already. */
async function openDecision(page: Page) {
  const change = page.getByRole('button', { name: 'Change this decision' });
  if (await change.isVisible()) await change.click();
}

async function decide(page: Page, arm: 'Verify' | 'Reject', confirm: string, outcome: RegExp) {
  await page.getByRole('button', { name: arm, exact: true }).click();
  // A KYC decision gates whether this client can hold a policy, so it takes a
  // second click -- but it is genuinely re-decidable (Party.updateKycStatus
  // assigns with no guard on the previous status), and the copy says so rather
  // than claiming an irreversibility the backend does not have.
  await expect(page.getByText(/can be changed later/)).toBeVisible();
  await page.getByRole('button', { name: confirm }).click();
  await expect(page.getByText(outcome)).toBeVisible({ timeout: 15_000 });
}

async function rejectThenVerify(page: Page) {
  await uploadEvidence(page, 'id-scan');
  await openDecision(page);
  // A previous run may have left this shared client rejected, where Reject is (rightly) disabled. Verify first, so the
  // round trip below always crosses both ways.
  if (await page.getByRole('button', { name: 'Reject', exact: true }).isDisabled()) {
    await decide(page, 'Verify', 'Verify identity', /^Identity verified\./);
    await openDecision(page);
  }

  // Reject.
  await decide(page, 'Reject', 'Reject identity', /^Identity rejected\./);

  // Then verify -- against fresh evidence, as a real staff workflow re-checks evidence per decision.
  await uploadEvidence(page, 'id-scan-2');
  await openDecision(page);
  await decide(page, 'Verify', 'Verify identity', /^Identity verified\./);
}
