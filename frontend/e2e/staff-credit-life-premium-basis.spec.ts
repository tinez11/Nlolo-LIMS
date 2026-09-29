import { test, expect, request as apiRequest } from '@playwright/test';
import { staffToken, seedCreditLifeFixtures } from './creditLife';

const API = 'http://localhost:8080';

/**
 * The BUMACO INSURANCE MAY file, end to end, against the real stack.
 *
 * Every figure is read off `sample data/BUMACO INSURANCE MAY.xlsx`: six loans of 1,500,000
 * to 6,000,000 over terms of 2 to 12 months, and the sheet's own premium total of 111,000
 * on 18,500,000 of principal.
 *
 * The point is the INVOICE, not the arithmetic -- CreditLifePremiumTest already pins the
 * formula. This proves the basis survives issuance, storage, the enrolment parser and
 * billing, which is the part a unit test cannot reach.
 */

/**
 * The file as the lender sent it, carrying THEIR premium column as well as the five the
 * parser requires. Those figures price nothing — the insurer charges from the scheme's own
 * rate and basis — they are read so the two sides can be reconciled.
 */
const MAY_FILE = [
  'borrower_full_name,borrower_date_of_birth,loan_principal_amount,loan_term_months,disbursement_date,lender_premium_amount',
  'JULIUS MBASHANGO MALUNDE,1960-05-12,1500000.00,4,2026-05-18,9000.00',
  'GAUDENCE PETTER CHAMI,1966-04-23,6000000.00,12,2026-05-18,36000.00',
  'AGATHA ALBERT SENDWA,1966-07-15,1000000.00,6,2026-05-18,6000.00',
  'CONSTANTINE LEONARD MALIPESA,1962-09-20,1000000.00,2,2026-05-18,6000.00',
  'SHAMTE SAID KIPAGATA,1970-07-02,4000000.00,6,2026-05-30,24000.00',
  'VULFRIDA JOHN MSELLE,1989-07-07,5000000.00,12,2026-05-30,30000.00',
].join('\n');

test('the May file invoices the lender exactly what their own sheet says', async () => {
  const http = await apiRequest.newContext();
  try {
    const token = await staffToken(http, 'staff.admin');
    const { lenderPartyId, productId, productVersionId } = await seedCreditLifeFixtures(http, token);

    // The scheme as Bumaco's terms actually are: 0.6%, charged flat on what was disbursed.
    const schemeResponse = await http.post(`${API}/group-schemes`, {
      headers: { Authorization: `Bearer ${token}` },
      data: {
        policyholderPartyId: lenderPartyId,
        productId,
        productVersionId,
        benefitBasis: 'AMORTISING_LOAN',
        currency: 'TZS',
        openingSchedule: [],
        premium: { amount: '1.00', currencyCode: 'TZS' },
        premiumFrequency: 'SINGLE',
        commencementDate: '2026-05-05',
        reasonForManualIssue: 'Bumaco May file, basis verification',
        issuanceBasis: 'MIGRATION',
        interestMethod: 'FLAT_RATE',
        repaymentFrequency: 'MONTHLY',
        premiumRatePercent: '0.6000',
        premiumBasis: 'FLAT_ON_PRINCIPAL',
      },
    });
    expect(schemeResponse.ok(), `issue -> ${schemeResponse.status()} ${await schemeResponse.text()}`)
      .toBeTruthy();
    const policyNumber = (await schemeResponse.json()).policyNumber as string;
    console.log(`\nscheme ${policyNumber} at 0.6% FLAT_ON_PRINCIPAL`);

    const upload = await http.post(`${API}/credit-life-schemes/${policyNumber}/enrolments`, {
      headers: { Authorization: `Bearer ${token}` },
      multipart: {
        file: { name: 'bumaco-may.csv', mimeType: 'text/csv', buffer: Buffer.from(MAY_FILE) },
      },
    });
    expect(upload.ok(), `upload -> ${upload.status()} ${await upload.text()}`).toBeTruthy();
    const submission = await upload.json();
    console.log(`submission ${submission.submissionId}: ${submission.rowCount ?? '?'} rows read`);

    // A SECOND person accepts it -- nobody is covered until they do.
    const secondToken = await staffToken(http, 'staff.senior');
    const accept = await http.post(
      `${API}/credit-life-schemes/${policyNumber}/enrolments/${submission.submissionId}/acceptance`,
      { headers: { Authorization: `Bearer ${secondToken}` } },
    );
    expect(accept.ok(), `accept -> ${accept.status()} ${await accept.text()}`).toBeTruthy();
    const accepted = await accept.json();
    console.log(`accepted: ${JSON.stringify(accepted)}`);

    // THE RECONCILIATION. The lender's own figures are read, summed and compared -- they used
    // to be discarded, so a file whose premium disagreed with ours was invoiced in silence.
    // Here they agree, and the agreement is the thing being evidenced: a zero variance that
    // nobody recorded is indistinguishable from never having looked.
    expect(Number(accepted.premiumTotal)).toBeCloseTo(111000, 2);
    expect(Number(accepted.statedPremiumTotal)).toBeCloseTo(111000, 2);
    expect(Number(accepted.premiumVariance)).toBeCloseTo(0, 2);

    const invoices = await (
      await http.get(`${API}/policies/${policyNumber}/invoices`, {
        headers: { Authorization: `Bearer ${token}` },
      })
    ).json();
    const list = (invoices.items ?? invoices) as { amount: { amount: string } }[];
    console.log(`invoices: ${JSON.stringify(list)}`);

    expect(list).toHaveLength(1);
    // The number on the lender's own sheet.
    expect(Number(list[0].amount.amount ?? list[0].amount)).toBeCloseTo(111000, 2);
  } finally {
    await http.dispose();
  }
});
