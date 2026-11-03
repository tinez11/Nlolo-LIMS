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
  it('accepts a lender and terms, with no borrowers and no price at all', () => {
    // The shape a lender relationship actually starts in: the limit and the start are set, the year's
    // price is agreed on the scheme page, and the book arrives later by file.
    expect(errorsFor(valid())).toEqual({});
  });

  it('treats a blank free cover limit as "no limit" rather than an error', () => {
    expect(errorsFor(valid({ fclAmount: '' }))).toEqual({});
    // But zero is refused rather than reinterpreted: a limit of zero would send every
    // borrower to underwriting, which is a different scheme.
    expect(errorsFor(valid({ fclAmount: '0.00' })).fclAmount).toMatch(/at least 0.01/);
  });

  it('requires a commencement date, and refuses a future one', () => {
    /*
     * Required here where the employer-scheme form lets it default. A credit-life scheme is
     * almost always a book that already exists, so leaving it to default to today means the
     * first file's loans all predate the scheme and the service refuses every row with
     * "cannot join before the scheme commenced" -- about a field the form never asked for.
     */
    expect(errorsFor(valid({ commencementDate: '' })).commencementDate).toMatch(
      /on or before the oldest loan/i,
    );
    expect(errorsFor(valid({ commencementDate: '2026-12-01' })).commencementDate).toMatch(
      /cannot commence in the future/,
    );
  });
});

describe('toIssueRequest', () => {
  it('sends an EMPTY opening schedule', () => {
    /*
     * The point of the whole form. The service permits this on AMORTISING_LOAN and only there:
     * an employer scheme's schedule is its contract, while a lender's book arrives by file.
     * Requiring one borrower here made somebody type a life they then met again on the member
     * roll without recognising them.
     */
    expect(toIssueRequest(valid()).openingSchedule).toEqual([]);
  });

  it('names the lender as the agent when the lender is a registered agent', () => {
    // On credit life the lender earns the commission (spec 2.8). This was null always.
    expect(toIssueRequest(valid(), 'lender-agent-1').agentOfRecordId).toBe('lender-agent-1');
  });

  it('issues direct when the lender is not an agent yet -- not a missing field', () => {
    expect(toIssueRequest(valid()).agentOfRecordId).toBeNull();
  });

  it('always sends SINGLE, the only premium frequency this product accepts', () => {
    // The service refuses any cycle on an AMORTISING_LOAN scheme outright -- a credit-life
    // premium is charged once per borrower when their loan is written. There is no field for
    // this on the form because there is no choice.
    expect(toIssueRequest(valid()).premiumFrequency).toBe('SINGLE');
  });

  it('sends no price, repayment terms or premium of its own: the yearly agreement prices every file', () => {
    // 2026-11-01. The server records the loans flat and monthly and the contract's premium as nil.
    const request = toIssueRequest(valid());
    expect(request.benefitBasis).toBe('AMORTISING_LOAN');
    for (const field of ['premiumRatePercent', 'premiumBasis', 'interestMethod', 'repaymentFrequency', 'premium']) {
      expect(field in request, field).toBe(false);
    }
  });

  it('omits blank optionals rather than sending empty strings', () => {
    const request = toIssueRequest(valid({ fclAmount: '', reasonForManualIssue: '' }));
    expect('fclAmount' in request).toBe(false);
    expect('reasonForManualIssue' in request).toBe(false);
  });

  it('sends no issuance basis: a credit-life scheme is in force on set-up', () => {
    // 2026-11-02, the user. The question only ever recorded "migration" for lenders nobody migrated.
    expect('issuanceBasis' in toIssueRequest(valid())).toBe(false);
  });
});
