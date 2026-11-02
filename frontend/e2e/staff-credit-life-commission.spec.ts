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
 * answer 3.1: the rate is agreed per lender -- in its yearly agreement, the only place a lender's
 * commission comes from (2026-11-01).
 *
 * 12.5% of the fixture file's 65,000 of premium is 8,125, earned once the lender pays the file.
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
  const token = await staffToken(http, 'staff.underwriter');
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

  test('finance makes the lender the earner, and its file earns the agreement’s rate', async ({ page }) => {
    test.setTimeout(300_000);
    const { policyNumber, submissionId } = await seedCreditLifeScheme({ commissionPercent: '12.5' });
    const http = await apiRequest.newContext();
    try {
      await verifyLenderKyc(http, await lenderPartyOf(http, policyNumber));
    } finally {
      await http.dispose();
    }

    // Commission is its own tab (2026-11-02).
    await page.goto(`/staff/credit-life-schemes/${policyNumber}?tab=commission`);
    // The facts, in the record rail.
    // Exact: "Who is paid the commission" would match too, and two sections are not one.
    const commission = page
      .locator('section')
      .filter({ has: page.getByRole('heading', { name: 'Commission', exact: true }) });
    // The acts, in the work column (the Record-Rail Rule).
    const earner = page
      .locator('section')
      .filter({ has: page.getByRole('heading', { name: 'Who is paid the commission' }) });

    // SAID, where it used to be silently zero -- and the rate is the agreement's, shown before anyone earns it.
    await expect(commission).toContainText('No commission will accrue', { timeout: 30_000 });
    await expect(commission).toContainText('12.5%, as agreed');

    await earner.getByLabel("Lender's licence number").fill(`LIC-LENDER-${Date.now()}`);
    await earner.getByLabel('Licence expiry').fill(dmy('2030-12-31'));
    await earner.getByRole('button', { name: 'Register the lender as an agent' }).click();
    await earner.getByRole('button', { name: 'Make the lender the commission earner' }).click({ timeout: 30_000 });

    await expect(commission).toContainText('The lender earns 12.5%', { timeout: 30_000 });
    await expect(commission).toContainText('Nothing yet');
    // Nothing left to set up, so the panel that set it up is gone.
    await expect(earner).toHaveCount(0);

    // The next file is priced with it -- forward from here, as the business chose -- and earned once the lender
    // pays: its quotation states the commission, and nothing is earned before the money arrives.
    const http2 = await apiRequest.newContext();
    try {
      await acceptEnrolment(http2, policyNumber, submissionId);
    } finally {
      await http2.dispose();
    }
    await page.reload();
    await expect(commission).toContainText('Nothing yet', { timeout: 30_000 });
    // The file's quotation, on the Monthly files tab, states the commission it earns.
    await page.getByRole('tab', { name: 'Monthly files' }).click();
    await expect(page.getByText('TZS 8,125.00').first()).toBeVisible({ timeout: 30_000 });
    // And reinsurance is said, not assumed.
    await page.getByRole('tab', { name: 'Scheme details' }).click();
    await expect(page.getByText('Not reinsured')).toBeVisible();
  });
});
