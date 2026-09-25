import { expect, request as apiRequest, test, type APIRequestContext } from '@playwright/test';
import { seedCreditLifeScheme, staffToken } from './creditLife';

const API = 'http://localhost:8080';

/**
 * A credit-life invoice reconciles, on the policy page, with the monthly files behind it.
 *
 * The page used to show one number per invoice -- what was charged -- and nothing else. A lender
 * whose borrower repaid early had that premium credited back, and the page still said the whole
 * charge was due; "Request payment" asked for it; the ledger never heard of the credit; and the
 * policy summary quoted a premium typed at set-up that matched no invoice at all.
 *
 * The figures come from the fixture's file at the scheme's 0.5% a year: Juma 1,200,000 over 24
 * months is 12,000; Neema 3,400,000 over 36 is 51,000; Zainabu 800,000 over 6 is 2,000; two rows
 * are refused. 65,000 charged. Juma then repays on the day he borrowed -- no cover used, so all
 * 12,000 comes back -- and 53,000 is owed.
 */
async function acceptEnrolment(http: APIRequestContext, policyNumber: string, submissionId: string) {
  // A SECOND person: the platform refuses the uploader's own acceptance.
  const token = await staffToken(http, 'staff.manager');
  const accepted = await http.post(
    `${API}/credit-life-schemes/${policyNumber}/enrolments/${submissionId}/acceptance`,
    { headers: { Authorization: `Bearer ${token}` } },
  );
  expect(accepted.ok(), `enrolment acceptance -> ${accepted.status()} ${await accepted.text()}`).toBeTruthy();
}

async function referenceOf(http: APIRequestContext, policyNumber: string, submissionId: string, name: string) {
  const token = await staffToken(http, 'staff.admin');
  const rows = await http.get(
    `${API}/credit-life-schemes/${policyNumber}/enrolments/${submissionId}/rows`,
    { headers: { Authorization: `Bearer ${token}` } },
  );
  const row = ((await rows.json()) as Array<Record<string, unknown>>).find((r) => r.borrowerFullName === name);
  expect(row?.memberReference, `${name} should have been issued a reference`).toBeTruthy();
  return row?.memberReference as string;
}

async function exitOnTheDayTheyBorrowed(http: APIRequestContext, policyNumber: string, reference: string) {
  const admin = await staffToken(http, 'staff.admin');
  const upload = await http.post(`${API}/credit-life-schemes/${policyNumber}/exits`, {
    headers: { Authorization: `Bearer ${admin}` },
    multipart: {
      file: {
        name: 'july-exits.csv',
        mimeType: 'text/csv',
        buffer: Buffer.from(
          `member_reference,exit_date,exit_reason,outstanding_balance_at_exit\n${reference},2026-07-15,SETTLED_EARLY,0.00\n`,
          'utf8',
        ),
      },
    },
  });
  expect(upload.ok(), `exits upload -> ${upload.status()} ${await upload.text()}`).toBeTruthy();
  const submission = (await upload.json()) as Record<string, unknown>;
  const manager = await staffToken(http, 'staff.manager');
  const accepted = await http.post(
    `${API}/credit-life-schemes/${policyNumber}/exits/${submission.submissionId as string}/acceptance`,
    { headers: { Authorization: `Bearer ${manager}` } },
  );
  expect(accepted.ok(), `exits acceptance -> ${accepted.status()} ${await accepted.text()}`).toBeTruthy();
}

test.describe('staff credit-life invoices', () => {
  test('an invoice shows what was charged, credited and is still owed, and which file charged it', async ({
    page,
  }) => {
    test.slow();
    const { policyNumber, submissionId } = await seedCreditLifeScheme();
    const http = await apiRequest.newContext();
    try {
      await acceptEnrolment(http, policyNumber, submissionId);
      const juma = await referenceOf(http, policyNumber, submissionId, 'Juma Rajabu Kimaro');
      await exitOnTheDayTheyBorrowed(http, policyNumber, juma);
    } finally {
      await http.dispose();
    }

    await page.goto(`/staff/policies/${policyNumber}`);

    // The summary no longer quotes the set-up figure as the premium.
    await expect(page.getByText('Charged per monthly file')).toBeVisible({ timeout: 30_000 });

    const invoices = page.locator('section').filter({ has: page.getByRole('heading', { name: 'Invoices' }) });
    // OWED leads, with what was charged beside it.
    await expect(invoices).toContainText('TZS 53,000.00', { timeout: 30_000 });
    await expect(invoices).toContainText('due of TZS 65,000.00 charged');
    // Traceable to the file that charged it.
    await expect(invoices).toContainText('Charged by january-schedule.csv');
    // And the credit, with whom it was for and why.
    await expect(invoices).toContainText('Credited · Juma Rajabu Kimaro');
    await expect(invoices).toContainText('loan repaid early');
    await expect(invoices).toContainText('−TZS 12,000.00');
    await expect(invoices).toContainText('Balance due');
  });
});
