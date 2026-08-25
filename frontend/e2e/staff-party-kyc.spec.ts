import { expect, test } from '@playwright/test';

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
const REAL_PARTY_ID = 'd9937444-3873-4336-9cb7-addb486f3e1b';

const MINIMAL_PNG = Buffer.from(
  'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAAAAAA6fptVAAAACklEQVR4nGMAAQAABQABDQottAAAAABJRU5ErkJggg==',
  'base64',
);

test.describe('staff party KYC verification', () => {
  test('reaches the party by drilling in from a policy, then drives a real reject-then-verify round trip', async ({
    page,
  }) => {
    await page.goto('/staff/policies/new');
    await page.getByLabel('Policyholder party id').fill(REAL_PARTY_ID);
    await page.getByLabel('Product').selectOption({ label: 'Demo Term Life (DEMO-TERM-01)' });
    await expect(page.getByText('Resolving product version…')).not.toBeVisible();
    await page.getByLabel('Sum assured').fill('2000000.00');
    await page.getByLabel('Premium', { exact: true }).fill('800.00');
    await page.getByLabel('Reason for manual issue').fill('E2E party-KYC fixture');
    await page.getByRole('button', { name: 'Issue policy' }).click();
    await expect(page).toHaveURL(/\/staff\/policies\/POL-[A-Z0-9]+$/, { timeout: 15_000 });

    await page.getByRole('link', { name: REAL_PARTY_ID }).click();
    await expect(page).toHaveURL(`/staff/parties/${REAL_PARTY_ID}`);
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
  await expect(page.getByText('Verified')).toBeVisible({ timeout: 15_000 });
}
