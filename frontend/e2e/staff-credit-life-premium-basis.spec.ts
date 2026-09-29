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

    /*
     * The lender IS the agent. Spec 2.8: on credit life only the lender earns, and there is a
     * backstop that refuses commission when the scheme's agent of record is anybody else --
     * five schemes were set up naming the individual who registered the lender, and it stops
     * them collecting.
     */
    // A corporate lender is KYC-verified like anybody else before it can be an agent.
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
    expect(evidence.ok(), `kyc evidence -> ${evidence.status()}`).toBeTruthy();
    const verified = await http.post(`${API}/parties/${lenderPartyId}/kyc`, {
      headers: { Authorization: `Bearer ${token}` },
      data: { status: 'VERIFIED', evidenceDocumentRef: (await evidence.json()).documentRef },
    });
    expect(verified.ok(), `kyc -> ${verified.status()} ${await verified.text()}`).toBeTruthy();

    const agentResponse = await http.post(`${API}/agents`, {
      headers: { Authorization: `Bearer ${token}`, 'Idempotency-Key': `bumaco-${Date.now()}` },
      data: {
        partyId: lenderPartyId,
        licenseNumber: `LIC-BUMACO-${Date.now().toString().slice(-6)}`,
        licenseExpiryDate: '2027-12-31',
      },
    });
    expect(agentResponse.ok(), `agent -> ${agentResponse.status()} ${await agentResponse.text()}`)
      .toBeTruthy();
    const agent = await agentResponse.json();
    console.log(`lender agent ${agent.agentId}`);

    // 0.1500, NOT 15. The rate is a FRACTION -- CommissionCalculator does
    // `premium.multiply(rule.getRate())` -- so a 15 here would accrue 1,665,000 on this file.
    const plan = await http.post(`${API}/commission-plans`, {
      headers: { Authorization: `Bearer ${token}` },
      data: { productId, rules: [{ tierType: 'FIRST_YEAR', rate: '0.1500' }] },
    });
    expect(plan.ok(), `plan -> ${plan.status()} ${await plan.text()}`).toBeTruthy();

    // The scheme as Bumaco's terms actually are: 0.6%, charged flat on what was disbursed.
    const schemeResponse = await http.post(`${API}/group-schemes`, {
      headers: { Authorization: `Bearer ${token}` },
      data: {
        policyholderPartyId: lenderPartyId,
        agentOfRecordId: agent.agentId,
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

    /*
     * And the OTHER number on it: 111,000 gross, 16,650 commission, 94,350 net.
     *
     * The invoice stays GROSS and the commission accrues separately -- the lender owes the whole
     * premium and earns its commission as an agent, which are two movements rather than one net
     * figure. Their sheet nets them, so the insurer's invoice and the lender's remittance advice
     * will not match on their face; that is an accounting shape to agree, not a defect.
     */
    const accruals = await (
      await http.get(`${API}/commission-accruals?policyNumber=${policyNumber}`, {
        headers: { Authorization: `Bearer ${token}` },
      })
    ).json();
    console.log(`accruals: ${JSON.stringify(accruals)}`);
    const accrued = accruals as { amount: { amount: string } }[];
    const total = accrued.reduce((sum, a) => sum + Number(a.amount.amount ?? a.amount), 0);
    expect(total).toBeCloseTo(16650, 2);
  } finally {
    await http.dispose();
  }
});
