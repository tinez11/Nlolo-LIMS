import { test, expect } from '@playwright/test';
import path from 'node:path';

const BACKEND_BASE_URL = 'http://localhost:8080';
const KEYCLOAK_BASE_URL = 'http://localhost:8081';

/**
 * Direct password-grant token fetch against real Keycloak (no browser involved) -- used only to
 * prepare the fixture below, never to bypass the portal's own login for the actual test steps.
 */
async function passwordGrantToken(realm: string, username: string, clientSecret: string): Promise<string> {
  const response = await fetch(`${KEYCLOAK_BASE_URL}/realms/${realm}/protocol/openid-connect/token`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
    body: new URLSearchParams({
      grant_type: 'password',
      client_id: 'lifeplatform-app',
      client_secret: clientSecret,
      username,
      password: 'devpassword',
    }),
  });
  if (!response.ok) {
    throw new Error(`Token request failed for ${realm}/${username}: ${response.status} ${await response.text()}`);
  }
  const body = (await response.json()) as { access_token: string };
  return body.access_token;
}

/**
 * `scripts/seed-dev-data.sh` leaves `customer.owner`'s one claim SETTLED, and
 * `ClaimsApiImpl.attachEvidence` rejects new evidence on a SETTLED claim
 * (`InvalidClaimStateException`) -- read and confirmed against the real controller before writing
 * this spec. Registering a brand-new claim instead (the brief's own fallback suggestion) is not
 * available in this environment either: `ClaimsApiImpl.registerClaim` requires
 * `PolicyApi.isPolicyInForce` (ACTIVE/REINSTATED), and the only seeded policy, `POL-6BD5702F`, is
 * SURRENDERED -- a terminal status with no transition back into force
 * (`PolicyApiImpl.reinstatePolicy` only accepts LAPSED). Both were verified directly against the
 * running stack, not assumed.
 *
 * So this fixes the claim up using the platform's OWN real API -- `POST /claims/{id}/reopen` as
 * `staff.manager` (`CLAIMS_MANAGER`, `staff` realm, a real Keycloak password grant) -- rather than
 * a database edit or a seeder change. It runs once per suite invocation and is idempotent: a claim
 * already in REOPENED (e.g. a prior run of this same spec) is left alone.
 */
test.beforeAll(async () => {
  const ownerToken = await passwordGrantToken('customers', 'customer.owner', 'dev-secret-customers');
  const claimsResponse = await fetch(`${BACKEND_BASE_URL}/claims?page=0&pageSize=20`, {
    headers: { Authorization: `Bearer ${ownerToken}` },
  });
  if (!claimsResponse.ok) {
    throw new Error(`Could not list customer.owner's claims: ${claimsResponse.status}`);
  }
  const claims = (await claimsResponse.json()) as { items: { claimId: string; status: string }[] };
  if (claims.items.length === 0) {
    throw new Error("customer.owner has no claims -- did scripts/seed-dev-data.sh run?");
  }
  const claim = claims.items[0];

  if (claim.status === 'SETTLED') {
    const staffToken = await passwordGrantToken('staff', 'staff.manager', 'dev-secret-staff');
    const reopenResponse = await fetch(`${BACKEND_BASE_URL}/claims/${claim.claimId}/reopen`, {
      method: 'POST',
      headers: { Authorization: `Bearer ${staffToken}`, 'Content-Type': 'application/json' },
      body: JSON.stringify({ reason: 'E2E fixture prep: evidence round-trip spec needs a non-SETTLED claim' }),
    });
    if (!reopenResponse.ok) {
      throw new Error(`Could not reopen claim ${claim.claimId} for the evidence spec: ${reopenResponse.status}`);
    }
  }
});

/**
 * The single most valuable E2E test on the platform: it re-proves M11's headline claim (a real
 * Keycloak token reaching a real MinIO-backed download) on every run, through the customer's own
 * claim-detail screen -- not a backend integration test's direct `ClaimsApi`/`DocumentApi` calls.
 *
 * DEVIATION FROM THE BRIEF, justified: the brief's illustrative selector
 * `page.getByRole('link', { name: /evidence\.pdf/i })` assumes the uploaded file's own name
 * becomes its visible link label. It does not. `EvidencePanel`'s `onUpload` (claim detail page)
 * sends only the `file` multipart part, never `description`, so `ClaimEvidenceView.description`
 * comes back `null` and `toEvidenceItem` falls back to the opaque `documentRef` (a random UUID,
 * `DocumentApiImpl.upload`) as the label -- see `src/app/(portal)/claims/[claimId]/page.tsx` and
 * `src/components/evidence-panel.tsx`. The ORIGINAL filename is never lost, though: the backend
 * stores it as `DocumentRecord.fileName` regardless of `description`, and
 * `FileDownloadResponses.fileResponse` puts it straight into `Content-Disposition` on download
 * (`ClaimEvidenceController.downloadEvidence`) -- confirmed directly against Postgres
 * (`document.document_record.file_name = 'evidence.pdf'`) while writing this spec. So this
 * identifies the just-uploaded row by position (evidence is listed newest-first --
 * `ClaimEvidenceRepository.findByClaimIdAndTenantIdOrderByUploadedAtDesc`) rather than by label,
 * and asserts the real, load-bearing fact: the downloaded file's name.
 */
test('uploads and downloads claim evidence end to end', async ({ page }) => {
  await page.goto('/claims');

  // Wait for the initial evidence fetch to land before reading a baseline count -- otherwise a
  // count assertion taken while the panel is still loading can pass on the WRONG transient state
  // (this raced and passed against the pre-existing seeded evidence item on first attempt).
  const initialEvidenceLoad = page.waitForResponse(
    (response) => response.url().includes('/evidence') && response.request().method() === 'GET',
  );
  await page.getByRole('link', { name: /view/i }).first().click();
  await initialEvidenceLoad;

  // CardTitle renders a plain <div>, not a heading element, so this matches by text, not role.
  await expect(page.getByText('Evidence', { exact: true })).toBeVisible();

  const evidenceLinks = page.locator('a[href*="/evidence/"]');
  const countBefore = await evidenceLinks.count();

  const refetchAfterUpload = page.waitForResponse(
    (response) => response.url().includes('/evidence') && response.request().method() === 'GET',
  );
  // `import.meta.dirname` (as the brief's illustrative snippet used) requires this file to load as
  // an ES module; Playwright here transpiles test files as CommonJS (no `"type": "module"` in
  // package.json), where that throws `SyntaxError: Cannot use 'import.meta' outside a module`.
  // `__dirname` is the CJS equivalent and resolves to the same directory.
  await page.getByLabel(/attach a file/i)
    .setInputFiles(path.join(__dirname, 'fixtures/evidence.pdf'));
  await refetchAfterUpload;

  await expect(evidenceLinks).toHaveCount(countBefore + 1);

  // Newest-first ordering means the just-uploaded evidence is always the first link.
  const uploaded = evidenceLinks.first();
  await expect(uploaded).toBeVisible();

  const download = await Promise.all([
    page.waitForEvent('download'),
    uploaded.click(),
  ]).then(([d]) => d);

  expect(download.suggestedFilename()).toBe('evidence.pdf');
});
