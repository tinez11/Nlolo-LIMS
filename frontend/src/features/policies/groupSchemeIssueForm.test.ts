import { describe, expect, it } from 'vitest';
import {
  blankGroupSchemeIssueForm,
  groupSchemeIssueFormSchema,
  toApiRequest,
  toProposeCaseRequest,
  type GroupSchemeIssueFormValues,
} from './groupSchemeIssueForm';

const EMPLOYER = '11111111-1111-1111-1111-111111111111';
const PERSON_A = '22222222-2222-2222-2222-222222222222';
const PERSON_B = '33333333-3333-3333-3333-333333333333';
const PRODUCT = '44444444-4444-4444-4444-444444444444';
const VERSION = '55555555-5555-5555-5555-555555555555';
const TODAY = '2026-09-03';

function flatScheme(overrides: Partial<GroupSchemeIssueFormValues> = {}): GroupSchemeIssueFormValues {
  return {
    ...blankGroupSchemeIssueForm(),
    policyholderPartyId: EMPLOYER,
    productId: PRODUCT,
    productVersionId: VERSION,
    benefitBasis: 'FLAT',
    flatBenefitAmount: '5000000.00',
    premiumAmount: '1200000.00',
    openingSchedule: [{ memberPartyId: PERSON_A, gradeCode: '', salaryAmount: '' }],
    ...overrides,
  };
}

function errorsFor(input: GroupSchemeIssueFormValues) {
  const result = groupSchemeIssueFormSchema(TODAY).safeParse(input);
  if (result.success) return {} as Record<string, string>;
  const errors: Record<string, string> = {};
  for (const issue of result.error.issues) {
    const key = issue.path.join('.');
    if (!(key in errors)) errors[key] = issue.message;
  }
  return errors;
}

describe('groupSchemeIssueFormSchema', () => {
  it('accepts a minimal flat scheme with one member', () => {
    expect(errorsFor(flatScheme())).toEqual({});
  });

  /**
   * A scheme cannot be issued empty: its sum assured is the total of its
   * members' cover, and a contract insuring nobody for nothing is not a policy.
   */
  it('refuses a scheme with nobody on it', () => {
    expect(errorsFor(flatScheme({ openingSchedule: [] })).openingSchedule)
      .toMatch(/at least one member/);
  });

  it('requires the parameter its basis calls for', () => {
    expect(errorsFor(flatScheme({ flatBenefitAmount: '' })).flatBenefitAmount)
      .toMatch(/needs the benefit/);
    expect(
      errorsFor(flatScheme({ benefitBasis: 'SALARY_MULTIPLE', flatBenefitAmount: '' })).salaryMultiple,
    ).toMatch(/needs a multiple/);
    expect(errorsFor(flatScheme({ benefitBasis: 'GRADED', flatBenefitAmount: '' })).grades)
      .toMatch(/at least one grade/);
  });

  it('refuses a grade table on a scheme that is not graded', () => {
    expect(
      errorsFor(flatScheme({ grades: [{ gradeCode: 'STAFF', benefitAmount: '1000.00' }] })).grades,
    ).toMatch(/Only a graded scheme/);
  });

  /**
   * The unique index would catch this as a 409 after a round trip. Caught here,
   * the message lands on the duplicated row, which is where the fix is.
   */
  it('catches the same person listed twice on the schedule', () => {
    const errors = errorsFor(
      flatScheme({
        openingSchedule: [
          { memberPartyId: PERSON_A, gradeCode: '', salaryAmount: '' },
          { memberPartyId: PERSON_A, gradeCode: '', salaryAmount: '' },
        ],
      }),
    );
    expect(errors['openingSchedule.1.memberPartyId']).toMatch(/already on the schedule/);
    // …and not on the first occurrence, which is not the row to remove.
    expect(errors['openingSchedule.0.memberPartyId']).toBeUndefined();
  });

  it('requires a salary per member on a salary-multiple scheme', () => {
    const errors = errorsFor(
      flatScheme({
        benefitBasis: 'SALARY_MULTIPLE',
        flatBenefitAmount: '',
        salaryMultiple: '3',
      }),
    );
    expect(errors['openingSchedule.0.salaryAmount']).toMatch(/Needs a salary/);
  });

  it('requires each member to hold a grade the scheme actually has', () => {
    const graded = flatScheme({
      benefitBasis: 'GRADED',
      flatBenefitAmount: '',
      grades: [{ gradeCode: 'STAFF', benefitAmount: '10000000.00' }],
      openingSchedule: [{ memberPartyId: PERSON_A, gradeCode: 'DIRECTORS', salaryAmount: '' }],
    });
    expect(errorsFor(graded)['openingSchedule.0.gradeCode']).toMatch(/Not a grade on this scheme/);
  });

  it('catches a duplicated grade code', () => {
    const graded = flatScheme({
      benefitBasis: 'GRADED',
      flatBenefitAmount: '',
      grades: [
        { gradeCode: 'STAFF', benefitAmount: '10000000.00' },
        { gradeCode: 'STAFF', benefitAmount: '20000000.00' },
      ],
      openingSchedule: [{ memberPartyId: PERSON_A, gradeCode: 'STAFF', salaryAmount: '' }],
    });
    expect(errorsFor(graded)['grades.1.gradeCode']).toMatch(/listed twice/);
  });

  it('refuses a commencement date in the future', () => {
    expect(errorsFor(flatScheme({ commencementDate: '2026-10-01' })).commencementDate)
      .toMatch(/cannot commence in the future/);
  });

  it('allows a backdated commencement — a schedule arrives late', () => {
    expect(errorsFor(flatScheme({ commencementDate: '2026-08-01' }))).toEqual({});
  });

  /** Blank means the scheme has no limit. Zero would mean everybody needs evidence. */
  it('accepts a blank free cover limit but refuses a zero one', () => {
    expect(errorsFor(flatScheme({ fclAmount: '' }))).toEqual({});
    expect(errorsFor(flatScheme({ fclAmount: '0.00' })).fclAmount).toMatch(/at least 0.01/);
  });
});

describe('toApiRequest', () => {
  it('sends no sum assured — the server derives it from the schedule', () => {
    expect(toApiRequest(flatScheme())).not.toHaveProperty('sumAssured');
  });

  it('omits a blank free cover limit rather than sending an empty string', () => {
    const request = toApiRequest(flatScheme({ fclAmount: '' }));
    expect(request).not.toHaveProperty('fclAmount');
    expect(toApiRequest(flatScheme({ fclAmount: '100000000.00' })).fclAmount).toBe('100000000.00');
  });

  it('sends money as decimal strings, never as numbers', () => {
    const request = toApiRequest(flatScheme({ fclAmount: '100000000.00' }));
    expect(typeof request.flatBenefitAmount).toBe('string');
    expect(typeof request.fclAmount).toBe('string');
    expect(request.premium).toEqual({ amount: '1200000.00', currencyCode: 'TZS' });
  });

  /** A multiple is a ratio, not money — it is the one number that is a number. */
  it('sends the salary multiple as a number', () => {
    const request = toApiRequest(
      flatScheme({
        benefitBasis: 'SALARY_MULTIPLE',
        flatBenefitAmount: '',
        salaryMultiple: '3.5',
        openingSchedule: [{ memberPartyId: PERSON_A, gradeCode: '', salaryAmount: '4000000.00' }],
      }),
    );
    expect(request.salaryMultiple).toBe(3.5);
    expect(request.openingSchedule?.[0]?.salaryAmount).toBe('4000000.00');
    expect(request).not.toHaveProperty('flatBenefitAmount');
  });

  /**
   * Fields belonging to another basis are dropped, not merely left blank. The
   * server rejects a grade on a flat scheme outright, so sending one that the
   * user could not even see would be a 409 nobody could explain.
   */
  it('drops fields that belong to a different basis', () => {
    const request = toApiRequest(
      flatScheme({
        salaryMultiple: '3',
        grades: [{ gradeCode: 'STAFF', benefitAmount: '10.00' }],
        openingSchedule: [{ memberPartyId: PERSON_B, gradeCode: 'STAFF', salaryAmount: '900.00' }],
      }),
    );
    expect(request).not.toHaveProperty('salaryMultiple');
    expect(request).not.toHaveProperty('grades');
    expect(request.openingSchedule?.[0]).toEqual({ memberPartyId: PERSON_B });
  });

  it('upper-cases currencies', () => {
    const request = toApiRequest(flatScheme({ currency: 'tzs', premiumCurrency: 'tzs' }));
    expect(request.currency).toBe('TZS');
    expect(request.premium?.currencyCode).toBe('TZS');
  });
});

describe('toProposeCaseRequest', () => {
  // The ORDINARY route now: a scheme is proposed as an underwriting case, an underwriter
  // decides it, and the decision issues it as an offer. toApiRequest still exists for
  // POST /group-schemes, which has become the exception path.
  it('sends the product id, which the issuance endpoint used to derive server-side', () => {
    const request = toProposeCaseRequest(flatScheme());
    expect(request.productId).toBe(PRODUCT);
    expect(request.productVersionId).toBeTruthy();
  });

  it('sends the premium as a flat amount and currency, not a nested Money', () => {
    // underwriting takes them flat; policy takes a Money. The two endpoints genuinely
    // differ, which is why this mapping exists rather than reusing toApiRequest.
    const request = toProposeCaseRequest(flatScheme());
    expect(request.premiumAmount).toBe('1200000.00');
    expect(request.premiumCurrency).toBe('TZS');
    expect(request).not.toHaveProperty('premium');
  });

  it('carries no reasonForManualIssue', () => {
    // Nothing is being done by hand on this path, so there is nothing to excuse.
    expect(toProposeCaseRequest(flatScheme())).not.toHaveProperty('reasonForManualIssue');
  });

  it('drops fields that belong to a different basis, exactly as the issuance mapping does', () => {
    const request = toProposeCaseRequest(
      flatScheme({
        salaryMultiple: '3',
        grades: [{ gradeCode: 'STAFF', benefitAmount: '10.00' }],
        openingSchedule: [{ memberPartyId: PERSON_B, gradeCode: 'STAFF', salaryAmount: '900.00' }],
      }),
    );
    expect(request).not.toHaveProperty('salaryMultiple');
    expect(request).not.toHaveProperty('grades');
    expect(request.openingSchedule?.[0]).toEqual({ memberPartyId: PERSON_B });
  });
});
