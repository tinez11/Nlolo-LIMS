import { describe, expect, it } from 'vitest';
import {
  blankCreditLifeSchemeIssueForm,
  creditLifeSchemeIssueFormSchema,
  toIssueRequest,
  type CreditLifeSchemeIssueFormValues,
} from './creditLifeSchemeIssueForm';

const TODAY = '2026-09-23';

function valid(overrides: Partial<CreditLifeSchemeIssueFormValues> = {}) {
  return {
    ...blankCreditLifeSchemeIssueForm(),
    policyholderPartyId: '18a95bce-d0fa-4aef-90db-3e34acbce71e',
    productId: 'f1b2c3d4-0000-4000-8000-000000000001',
    productVersionId: 'f1b2c3d4-0000-4000-8000-000000000002',
    premiumRatePercent: '0.5',
    premiumAmount: '52000.00',
    borrowerName: 'Amina Hassan Mwinyi',
    borrowerDateOfBirth: '1988-03-14',
    principalAmount: '2400000.00',
    termMonths: '18',
    disbursementDate: '2026-08-03',
    firstRepaymentDate: '2026-09-03',
    commencementDate: '2026-06-01',
    ...overrides,
  };
}

/**
 * The FIRST message per field, which is the one the form renders.
 *
 * Zod reports every failing rule on a path, so a blank required amount produces three -- the
 * min(1), the pattern and the floor. Keeping the last would assert against a message no user
 * ever reads, and would silently change meaning whenever a rule is appended.
 */
function errorsFor(values: CreditLifeSchemeIssueFormValues): Record<string, string> {
  const parsed = creditLifeSchemeIssueFormSchema(TODAY).safeParse(values);
  if (parsed.success) return {};
  const first: Record<string, string> = {};
  for (const issue of parsed.error.issues) {
    const path = issue.path.join('.');
    if (!(path in first)) first[path] = issue.message;
  }
  return first;
}

describe('creditLifeSchemeIssueFormSchema', () => {
  it('accepts a lender, terms and one opening borrower', () => {
    expect(errorsFor(valid())).toEqual({});
  });

  it('refuses a loan disbursed in the future', () => {
    // The same rule the enrolment pipeline applies per row, and the reason a borrower is
    // refused with DISBURSEMENT_DATE_IN_FUTURE: cover cannot start before the loan exists.
    expect(errorsFor(valid({ disbursementDate: '2027-12-01' })).disbursementDate).toMatch(
      /cannot be disbursed in the future/,
    );
  });

  it('refuses a first repayment on or before the disbursement', () => {
    expect(
      errorsFor(valid({ disbursementDate: '2026-08-03', firstRepaymentDate: '2026-08-03' }))
        .firstRepaymentDate,
    ).toMatch(/after the money goes out/);
  });

  it('requires a date of birth, because the product has entry-age bounds', () => {
    expect(errorsFor(valid({ borrowerDateOfBirth: '' })).borrowerDateOfBirth).toMatch(
      /entry-age bounds/,
    );
  });

  it('accepts a zero interest rate but not a blank one', () => {
    // Zero is the normal case on a flat-rate loan and a true statement; blank is a missing
    // answer, and the two must not look alike.
    expect(errorsFor(valid({ annualInterestRatePercent: '0' }))).toEqual({});
    expect(errorsFor(valid({ annualInterestRatePercent: '' })).annualInterestRatePercent).toMatch(
      /Zero is a real answer/,
    );
  });

  it('treats a blank free cover limit as "no limit" rather than an error', () => {
    expect(errorsFor(valid({ fclAmount: '' }))).toEqual({});
    // But zero is refused rather than reinterpreted: a limit of zero would send every
    // borrower to underwriting, which is a different scheme.
    expect(errorsFor(valid({ fclAmount: '0.00' })).fclAmount).toMatch(/at least 0.01/);
  });

  it('requires the premium rate the lender is charged', () => {
    expect(errorsFor(valid({ premiumRatePercent: '' })).premiumRatePercent).toMatch(
      /percent of each loan/,
    );
  });

  it('refuses a scheme that commences after its own opening borrower was lent to', () => {
    /*
     * The normal case, not an edge one. Onboarding a lender's existing book means every loan on
     * it predates today, so a blank commencement date -- which means "today" on the wire -- made
     * the server refuse the whole submission with "Member X cannot join on 2026-08-03, before the
     * scheme commenced on 2026-09-23": correct, and about a field the form never asked for.
     */
    expect(
      errorsFor(valid({ commencementDate: '2026-09-01', disbursementDate: '2026-08-03' }))
        .commencementDate,
    ).toMatch(/did not exist yet/);
    expect(errorsFor(valid({ commencementDate: '' })).commencementDate).toMatch(
      /on or before the oldest loan/i,
    );
  });
});

describe('toIssueRequest', () => {
  it('always sends SINGLE, the only premium frequency this product accepts', () => {
    // The service refuses any cycle on an AMORTISING_LOAN scheme outright -- a credit-life
    // premium is charged once per borrower when their loan is written. There is no field for
    // this on the form because there is no choice.
    expect(toIssueRequest(valid()).premiumFrequency).toBe('SINGLE');
  });

  it('sends the borrower as FREEFORM with their own loan, not as a party', () => {
    const request = toIssueRequest(valid());
    expect(request.benefitBasis).toBe('AMORTISING_LOAN');
    expect(request.openingSchedule).toHaveLength(1);
    const member = request.openingSchedule[0]!;
    expect(member.memberType).toBe('FREEFORM');
    expect(member.memberName).toBe('Amina Hassan Mwinyi');
    // No party id: the insurer holds no record for a borrower, and minting one would put a KYC
    // obligation on a life whose cover the lender owns.
    expect(member.memberPartyId).toBeUndefined();
    expect(member.loanTerms).toMatchObject({
      principalAmount: '2400000.00',
      termMonths: 18,
      repaymentFrequency: 'MONTHLY',
      disbursementDate: '2026-08-03',
      firstRepaymentDate: '2026-09-03',
    });
  });

  it('omits blank optionals rather than sending empty strings', () => {
    // commencementDate is included here even though the schema now requires it: the builder is
    // the wire's contract, not the form's, and "" must never reach a date field whatever refused
    // it upstream.
    const request = toIssueRequest(
      valid({ fclAmount: '', loanAccountNumber: '', reasonForManualIssue: '', commencementDate: '' }),
    );
    expect('fclAmount' in request).toBe(false);
    expect('commencementDate' in request).toBe(false);
    expect('reasonForManualIssue' in request).toBe(false);
    expect('loanAccountNumber' in request.openingSchedule[0]!).toBe(false);
  });

  it('omits the issuance basis when the scheme is being issued as an offer', () => {
    // Null is what says "ordinary offer" on this endpoint; an empty string would fail the enum.
    expect('issuanceBasis' in toIssueRequest(valid({ issuanceBasis: '' }))).toBe(false);
    expect(toIssueRequest(valid({ issuanceBasis: 'MIGRATION' })).issuanceBasis).toBe('MIGRATION');
  });

  it('carries the scheme terms no other benefit basis has', () => {
    const request = toIssueRequest(valid({ interestMethod: 'REDUCING_BALANCE' }));
    expect(request.interestMethod).toBe('REDUCING_BALANCE');
    expect(request.premiumRatePercent).toBe(0.5);
    expect(request.repaymentFrequency).toBe('MONTHLY');
  });
});
