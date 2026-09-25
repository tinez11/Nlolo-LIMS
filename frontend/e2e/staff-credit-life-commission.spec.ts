import { expect, request as apiRequest, test, type APIRequestContext } from '@playwright/test';
import { seedCreditLifeScheme, staffToken } from './creditLife';
import { dmy } from './dates';

const API = 'http://localhost:8080';

/**
 * The lender earns commission on its scheme, at its own rate, and the scheme page says so.
 *
 * Every link of this was broken in dev, invisibly: 67 of 72 credit-life schemes had no agent, the
 * other five carried an individual who had merely registered the lender, no credit-life product
 * had a plan, and the month-end close that makes commission payable was never installed. Each
 * file earned zero and nothing on any screen said it. Spec 2.8: the lender is the agent; client
 * answer 3.1: the rate is agreed per lender.
 *
 * 12.5% of the fixture file's 65,000 of premium is 8,125.
 */
async function verifyLenderKyc(http: APIRequestContext, lenderPartyId: string) {
  // An agent must be KYC-verified before they can be onboarded -- a corporate lender included.
  const token = await staffToken(http, 'staff.admin');
  const evidence = await http.post(`${API}/parties/${lenderPartyId}/kyc-evidence`, {
    headers: { Authorization: `Bearer ${token}` },
    multipart: {
      file: {
        name: 'certificate-of-incorporation.png',
        mimeType: 'image/png',
        buffer: Buffer.from(
          'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg==',
          'base64',
        ),
      },
    },
  });
  expect(evidence.ok(), `kyc evidence -> ${evidence.status()} ${await evidence.text()}`).toBeTruthy();
  const { documentRef } = (await evidence.json()) as { documentRef: string };
  const verified = await http.post(`${API}/parties/${lenderPartyId}/kyc`, {
    headers: { Authorization: `Bearer ${token}` },
    data: { status: 'VERIFIED', evidenceDocumentRef: documentRef },
  });
  expect(verified.ok(), `kyc -> ${verified.status()} ${await verified.text()}`).toBeTruthy();
}

async function acceptEnrolment(http: APIRequestContext, policyNumber: string, submissionId: string) {
  const token = await staffToken(http, 'staff.manager');
  const accepted = await http.post(
    `${API}/credit-life-schemes/${policyNumber}/enrolments/${submissionId}/acceptance`,
    { headers: { Authorization: `Bearer ${token}` } },
  );
  expect(accepted.ok(), `acceptance -> ${accepted.status()} ${await accepted.text()}`).toBeTruthy();
}

async function lenderPartyOf(http: APIRequestContext, policyNumber: string): Promise<string> {
  const token = await staffToken(http, 'staff.admin');
  const policy = await http.get(`${API}/policies/${policyNumber}`, { headers: { Authorization: `Bearer ${token}` } });
  return ((await policy.json()) as { policyholderPartyId: string }).policyholderPartyId;
}

test.describe('staff credit-life commission', () => {
  test.use({ storageState: 'e2e/.auth/staff-finance.json' });

  test('finance makes the lender the earner at its own rate, and the next file earns it', async ({ page }) => {
    test.setTimeout(300_000);
    const { policyNumber, submissionId } = await seedCreditLifeScheme();
    const http = await apiRequest.newContext();
    try {
      await verifyLenderKyc(http, await lenderPartyOf(http, policyNumber));
    } finally {
      await http.dispose();
    }

    await page.goto(`/staff/credit-life-schemes/${policyNumber}`);
    const commission = page.locator('section').filter({ has: page.getByRole('heading', { name: 'Commission' }) });

    // SAID, where it used to be silently zero.
    await expect(commission).toContainText('No commission will accrue', { timeout: 30_000 });
    // And reinsurance is said, not assumed.
    await expect(page.getByText('Not reinsured')).toBeVisible();

    await commission.getByLabel("Lender's licence number").fill(`LIC-LENDER-${Date.now()}`);
    await commission.getByLabel('Licence expiry').fill(dmy('2030-12-31'));
    await commission.getByRole('button', { name: 'Register the lender as an agent' }).click();

    await commission.getByLabel('Commission rate (%)').fill('12.5', { timeout: 30_000 });
    await commission.getByRole('button', { name: /Set the lender.s rate/ }).click();
    await commission.getByRole('button', { name: 'Make the lender the commission earner' }).click();

    await expect(commission).toContainText('The lender earns 12.5%', { timeout: 30_000 });
    await expect(commission).toContainText('Nothing yet');

    // The next file earns it -- forward from here, as the business chose.
    const http2 = await apiRequest.newContext();
    try {
      await acceptEnrolment(http2, policyNumber, submissionId);
    } finally {
      await http2.dispose();
    }
    await page.reload();
    await expect(commission).toContainText('earned', { timeout: 30_000 });
    await expect(commission).toContainText('TZS 8,125.00');
  });
});
